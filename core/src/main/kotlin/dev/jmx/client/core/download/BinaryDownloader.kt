package dev.jmx.client.core.download

import dev.jmx.client.core.image.ImageHostRegistry
import dev.jmx.client.core.network.defaultOkHttpClient
import dev.jmx.client.core.protocol.JmxProtocolConstants
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

interface Downloader {
    suspend fun download(request: DownloadRequest, sink: ByteSink): JmxResult<DownloadResult>
}

interface TruncatingSink {
    fun truncate()
}

class BinaryDownloader(
    private val okHttpClient: OkHttpClient = defaultOkHttpClient(),
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    /**
     * 单次 [download] 最多尝试几个地址（含首个）。大于 1 时启用图片 CDN / 后缀故障转移，
     * 候补由 [ImageHostFailover] 按错误类型给出；非图片地址不会产生候补，等于不受影响。
     *
     * 默认只给 3：每个失败的候补在最坏情况下要等满一个超时，而阅读器不能为一张图等上一分钟。
     * 常见故障（连不上、5xx、后缀猜错）第一或第二个候补就能救回来。
     */
    private val maxAttemptsPerDownload: Int = 3,
    /**
     * 故障转移时可换的图片主机表。测试传入 MockWebServer 的主机名，
     * 免得单测在失败路径上真的去打线上 CDN。给了 [imageHostRegistry] 时以后者为准。
     */
    private val imageHosts: List<String> = JmxProtocolConstants.DefaultImageHosts,
    /**
     * 共享的图片线路表。给了它就有两个好处：换机时按健康度挑而不是按内置表轮转，
     * 以及把下载的成败记回去——下载是图片流量的大头，阅读器与封面预热据此少踩一遍同样的坑。
     */
    private val imageHostRegistry: ImageHostRegistry? = null
) : Downloader {
    override suspend fun download(request: DownloadRequest, sink: ByteSink): JmxResult<DownloadResult> =
        withContext(Dispatchers.IO) {
            val observer = request.observer
            observer.onEvent(DownloadEvent.Started(request.url))
            // 中间态一律不外泄：每个候补内部都会发自己的 Started/Failed/Completed，
            // 这层只把进度透传出去，并统一换成调用方给的原始地址，
            // 否则 UI 会因为换了一台 CDN 就以为在下另一张图。
            val progress = ProgressOnlyObserver(observer, request.url)
            val triedUrls = linkedSetOf(request.url)
            var currentUrl = request.url
            var lastFailure: JmxResult.Failure = JmxResult.Failure(
                JmxError.Unknown("下载未执行；" + DownloadFailureContext(url = request.url).describe())
            )
            for (attempt in 1..maxAttemptsPerDownload.coerceAtLeast(1)) {
                currentCoroutineContext().ensureActive()
                val isFirstAttempt = attempt == 1
                val attemptRequest = request.copy(
                    url = currentUrl,
                    observer = progress,
                    // 换地址后必须从头下：另一台机器/另一个后缀的字节流与已落盘的部分无关。
                    rangeStartInclusive = if (isFirstAttempt) request.rangeStartInclusive else null,
                    preferRangeResume = isFirstAttempt && request.preferRangeResume
                )
                when (val result = attemptDownload(attemptRequest, sink)) {
                    is JmxResult.Success -> {
                        reportHostResult(currentUrl, error = null)
                        observer.onEvent(DownloadEvent.Completed(request.url, result.value))
                        return@withContext result
                    }
                    is JmxResult.Failure -> {
                        lastFailure = result
                        reportHostResult(currentUrl, result.error)
                        val nextUrl = nextCandidateUrl(
                            currentUrl = currentUrl,
                            error = result.error,
                            triedUrls = triedUrls,
                            bytesWritten = progress.bytesWritten,
                            sink = sink
                        ) ?: break
                        (sink as? TruncatingSink)?.truncate()
                        progress.reset()
                        triedUrls += nextUrl
                        currentUrl = nextUrl
                    }
                }
            }
            observer.onEvent(DownloadEvent.Failed(request.url, lastFailure.error))
            lastFailure
        }

    private fun nextCandidateUrl(
        currentUrl: String,
        error: JmxError,
        triedUrls: Set<String>,
        bytesWritten: Long,
        sink: ByteSink
    ): String? {
        // 换地址前要能把已写入的部分清掉；清不掉就只能放弃，否则会拼出一个坏文件。
        if (sink !is TruncatingSink && bytesWritten > 0L) return null
        val registry = imageHostRegistry
            ?: return ImageHostFailover.next(currentUrl, error, triedUrls, hosts = imageHosts)
        val triedHosts = triedUrls.mapNotNullTo(mutableSetOf()) { it.toHttpUrlOrNull()?.host }
        return ImageHostFailover.next(
            currentUrl = currentUrl,
            error = error,
            triedUrls = triedUrls,
            hosts = registry.candidates(triedHosts),
            preserveHostOrder = true
        )
    }

    /**
     * 把这次尝试的结果记进线路表。
     *
     * 只记"机器的账"：后缀猜错（404/410）与业务错误换机器也救不回来，
     * 算成机器失败会把整张表逐个冤枉降级。成功不带延迟——下载耗时主要由图片大小决定，
     * 拿它当线路延迟会让大图所在的机器无端被扣分（首字节耗时由
     * [dev.jmx.client.core.image.ImageHostRoutingInterceptor] 在取图路径上采集）。
     */
    private fun reportHostResult(url: String, error: JmxError?) {
        val registry = imageHostRegistry ?: return
        val host = url.toHttpUrlOrNull()?.host ?: return
        if (!registry.knows(host)) return
        if (error == null) {
            registry.markSuccess(host)
        } else if (ImageHostFailover.indicatesHostFailure(error)) {
            registry.markFailure(host, error.message)
        }
    }

    private fun attemptDownload(request: DownloadRequest, sink: ByteSink): JmxResult<DownloadResult> {
        request.observer.onEvent(DownloadEvent.Started(request.url))
        return try {
            val offset = request.rangeStartInclusive?.takeIf { it > 0L }
            val wantRange = request.preferRangeResume && offset != null
            if (wantRange) {
                open(request, offset).use { response ->
                    when (response.code) {
                        206 -> return writeBody(
                            request, response, sink,
                            resumedFromOffset = offset,
                            usedRange = true
                        )
                        200 -> {
                            (sink as? TruncatingSink)?.truncate()
                            return writeBody(
                                request, response, sink,
                                resumedFromOffset = 0L,
                                usedRange = false
                            )
                        }
                        416 -> Unit
                        else -> {
                            if (!response.isSuccessful) {
                                return failHttp(request, response)
                            }
                        }
                    }
                }
                open(request, rangeStart = null).use { response ->
                    (sink as? TruncatingSink)?.truncate()
                    return writeBody(
                        request, response, sink,
                        resumedFromOffset = 0L,
                        usedRange = false
                    )
                }
            } else {
                open(request, rangeStart = null).use { response ->
                    return writeBody(
                        request, response, sink,
                        resumedFromOffset = 0L,
                        usedRange = false
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (it: Throwable) {
            val error = if (it is IOException) {
                JmxError.Network(
                    "下载网络请求失败；" + DownloadFailureContext(url = request.url).describe(),
                    it
                )
            } else {
                JmxError.Unknown(
                    (it.message ?: "下载未知错误") + "；" +
                        DownloadFailureContext(url = request.url).describe(),
                    it
                )
            }
            request.observer.onEvent(DownloadEvent.Failed(request.url, error))
            JmxResult.Failure(error)
        }
    }

    private fun open(request: DownloadRequest, rangeStart: Long?): Response {
        val builder = Request.Builder().url(request.url).get()
        request.headers.forEach { (key, value) ->
            if (!key.equals("Range", ignoreCase = true)) {
                builder.header(key, value)
            }
        }
        if (rangeStart != null && rangeStart > 0L) {
            builder.header("Range", "bytes=$rangeStart-")
        }
        return okHttpClient.newCall(builder.build()).execute()
    }

    private fun failHttp(request: DownloadRequest, response: Response): JmxResult<DownloadResult> {
        val contentType = response.body.contentType()?.toString()
        val contentLength = response.body.contentLength()
        val error = JmxError.Http(
            code = response.code,
            message = "下载请求失败：${response.code}；" + DownloadFailureContext(
                url = response.request.url.toString(),
                statusCode = response.code,
                contentType = contentType,
                contentLength = contentLength
            ).describe()
        )
        request.observer.onEvent(DownloadEvent.Failed(request.url, error))
        return JmxResult.Failure(error)
    }

    private fun writeBody(
        request: DownloadRequest,
        response: Response,
        sink: ByteSink,
        resumedFromOffset: Long,
        usedRange: Boolean
    ): JmxResult<DownloadResult> {
        val body = response.body
        val contentType = body.contentType()?.toString()
        val contentLength = body.contentLength()
        val status = response.code
        if (status != 200 && status != 206) {
            return failHttp(request, response)
        }
        if (!request.acceptedContentTypes.matches(contentType)) {
            val error = JmxError.Schema(
                "下载响应类型不匹配：${contentType ?: "unknown"}；" + DownloadFailureContext(
                    url = response.request.url.toString(),
                    statusCode = status,
                    contentType = contentType,
                    contentLength = contentLength
                ).describe(),
                field = "content-type"
            )
            request.observer.onEvent(DownloadEvent.Failed(request.url, error))
            return JmxResult.Failure(error)
        }
        val effectiveLength = if (status == 206 && contentLength >= 0 && resumedFromOffset > 0) {
            contentLength + resumedFromOffset
        } else {
            contentLength
        }
        if (request.maxBytes != null && effectiveLength > request.maxBytes) {
            val error = JmxError.Schema(
                "下载响应超过大小限制：$effectiveLength > ${request.maxBytes}；" + DownloadFailureContext(
                    url = response.request.url.toString(),
                    statusCode = status,
                    contentType = contentType,
                    contentLength = contentLength,
                    maxBytes = request.maxBytes
                ).describe(),
                field = "content-length"
            )
            request.observer.onEvent(DownloadEvent.Failed(request.url, error))
            return JmxResult.Failure(error)
        }
        val buffer = ByteArray(bufferSize.coerceAtLeast(1))
        var written = 0L
        body.byteStream().use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                val totalAfter = resumedFromOffset + written + read
                if (request.maxBytes != null && totalAfter > request.maxBytes) {
                    val error = JmxError.Schema(
                        "下载数据超过大小限制：$totalAfter > ${request.maxBytes}；" + DownloadFailureContext(
                            url = response.request.url.toString(),
                            statusCode = status,
                            contentType = contentType,
                            contentLength = contentLength,
                            bytesRead = totalAfter,
                            maxBytes = request.maxBytes
                        ).describe(),
                        field = "body"
                    )
                    request.observer.onEvent(DownloadEvent.Failed(request.url, error))
                    return JmxResult.Failure(error)
                }
                sink.write(buffer.copyOf(read))
                written += read
                request.observer.onEvent(
                    DownloadEvent.Progress(
                        url = request.url,
                        bytesRead = resumedFromOffset + written,
                        contentLength = effectiveLength
                    )
                )
            }
        }
        val result = DownloadResult(
            url = response.request.url.toString(),
            statusCode = status,
            contentType = contentType,
            contentLength = effectiveLength,
            bytesWritten = written,
            resumedFromOffset = resumedFromOffset,
            usedRange = usedRange && status == 206
        )
        // 空响应体：部分 CDN 内部出错时回 200 + 0 字节，HTTP 层完全看不出异常，
        // 落盘就是一张 0 字节的"成功"图片，用户看到的是"下载完成但打不开"。
        // 按可重试的网络错误上报，好让 download() 换一台机器重下。
        // （Content-Length 声明得比实际内容长的截断由 OkHttp 自己抛 ProtocolException，
        //   走上面的 IOException 分支，这里不重复判断。）
        if (written == 0L && resumedFromOffset == 0L) {
            val error = JmxError.Network(
                message = "下载内容为空；" + DownloadFailureContext(
                    url = response.request.url.toString(),
                    statusCode = status,
                    contentType = contentType,
                    contentLength = contentLength,
                    bytesRead = written
                ).describe(),
                retryable = true
            )
            request.observer.onEvent(DownloadEvent.Failed(request.url, error))
            return JmxResult.Failure(error)
        }
        request.observer.onEvent(DownloadEvent.Completed(request.url, result))
        return JmxResult.Success(result)
    }

    /**
     * 故障转移期间只放行进度事件，并把地址统一换回调用方给的原始地址。
     * Started / Completed / Failed 由 [download] 统一各发一次——候补内部发的那些是实现细节，
     * 让 UI 看见会变成"同一张图失败了三次又成功了"。
     */
    private class ProgressOnlyObserver(
        private val delegate: DownloadObserver,
        private val displayUrl: String
    ) : DownloadObserver {
        @Volatile
        var bytesWritten: Long = 0L
            private set

        override fun onEvent(event: DownloadEvent) {
            if (event !is DownloadEvent.Progress) return
            bytesWritten = event.bytesRead
            delegate.onEvent(event.copy(url = displayUrl))
        }

        fun reset() {
            bytesWritten = 0L
        }
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 8 * 1024
    }
}

private fun Set<String>.matches(contentType: String?): Boolean {
    if (isEmpty()) return true
    val actual = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
    return any { expected ->
        val value = expected.substringBefore(';').trim().lowercase()
        value == actual || value.endsWith("/*") && actual.startsWith("${value.substringBefore('/')}/")
    }
}
