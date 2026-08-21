package dev.jmx.client

import dev.jmx.client.core.api.ActionResult
import dev.jmx.client.core.api.DailyCheckInfo
import dev.jmx.client.core.protocol.JmxMagicConstants
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.runtime.JmxCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class AccountCollectionKind { FAVORITES, HISTORY }

/**
 * 收藏页排序方式，对应收藏接口的 `o` 参数。
 *
 * 声明顺序即下拉菜单里的展示顺序；默认值单独由 [FavoriteSortOrder.Default] 给出，
 * 保持与服务端默认（收藏时间）一致，不因为菜单排序而变。
 */
internal enum class FavoriteSortOrder(val apiOrder: String, val label: String) {
    UPDATE_TIME(JmxMagicConstants.FAVORITE_ORDER_BY_UPDATE_TIME, "更新时间"),
    FAVORITE_TIME(JmxMagicConstants.FAVORITE_ORDER_BY_FAVORITE_TIME, "收藏时间"),
    ;

    companion object {
        val Default = FAVORITE_TIME

        fun fromName(name: String?): FavoriteSortOrder =
            entries.firstOrNull { it.name == name } ?: Default
    }
}

internal data class AccountAlbumPage(
    val albums: List<HomeAlbum>,
    val total: Int?,
)

internal class AccountDataRepository(
    private val core: JmxCore,
    private val homeRepository: HomeRepository,
    private val accountRepository: AccountRepository,
) {
    /**
     * 拉一页收藏/观看历史。
     *
     * 整段放在 IO 线程上：出网之后还要把响应映射成 [HomeAlbum]（其中 `toRawMap` 会把嵌套的
     * JSON 重新串成字符串），留在调用方线程上就会在"内容即将出现"的那一刻卡住界面。
     */
    suspend fun loadCollection(
        kind: AccountCollectionKind,
        page: Int,
        favoriteOrder: FavoriteSortOrder = FavoriteSortOrder.Default,
    ): JmxResult<AccountAlbumPage> = withContext(Dispatchers.IO) {
        val result = accountRepository.withSessionRecovery {
            when (kind) {
                AccountCollectionKind.FAVORITES -> core.libraryApi.favoriteAlbums(
                    page = page,
                    order = favoriteOrder.apiOrder,
                    folderId = 0,
                )
                AccountCollectionKind.HISTORY -> core.libraryApi.watchList(page)
            }
        }
        when (result) {
            is JmxResult.Success -> JmxResult.Success(
                AccountAlbumPage(
                    albums = result.value.content
                        .filter { it.id.isNotBlank() }
                        .distinctBy { it.id }
                        .map { it.toHomeAlbum(homeRepository.currentImageHost) },
                    total = result.value.total,
                ),
            )
            is JmxResult.Failure -> result
        }
    }

    suspend fun dailyInfo(profile: AccountProfile): JmxResult<DailyCheckInfo?> {
        val id = profile.id?.toString()
            ?: return JmxResult.Failure(JmxError.Schema("用户资料缺少 UID", field = "uid"))
        // 会话过期时 /daily 不回 401，而是回空载荷（与"当期无活动"同形），
        // 所以这里要显式把空载荷也当成会话失效的可能原因交给恢复逻辑判定，
        // 否则自动签到只会静默地什么都不做。
        return accountRepository.withSessionRecovery(treatEmptyDataAsSessionLoss = true) {
            core.libraryApi.dailyInfo(id)
        }
    }

    suspend fun checkIn(profile: AccountProfile, dailyId: Int?): JmxResult<ActionResult> {
        val id = profile.id?.toString()
            ?: return JmxResult.Failure(JmxError.Schema("用户资料缺少 UID", field = "uid"))
        val eventId = dailyId?.toString()
            ?: return JmxResult.Failure(JmxError.Schema("签到活动编号缺失", field = "dailyId"))
        val submit = accountRepository.withSessionRecovery(treatEmptyDataAsSessionLoss = true) {
            core.libraryApi.dailyCheck(id, eventId)
        }
        if (submit is JmxResult.Failure && submit.error is JmxError.EmptyData) {
            // 提交签到也可能返回空载荷：以重新查询到的当日记录为准，不能只信提交响应
            return when (val verified = resolveCheckInAfterEmptyPayload(dailyInfo(profile))) {
                is JmxResult.Success -> verified
                is JmxResult.Failure -> submit
            }
        }
        return submit
    }

    suspend fun autoCheckIn(profile: AccountProfile): AutoCheckInResult {
        // 稳定度保护：同一时刻只允许一次自动签到流程（跳过而非排队），
        // 且两次真实尝试之间保持最小间隔，防止触发变敏感后重复提交或频繁失败
        if (!autoCheckInInFlight.compareAndSet(false, true)) {
            return AutoCheckInResult.FAILED
        }
        try {
            val now = System.currentTimeMillis()
            if (now - lastAutoCheckInAttemptAtMillis < AUTO_CHECK_IN_MIN_INTERVAL_MILLIS) {
                return AutoCheckInResult.FAILED
            }
            lastAutoCheckInAttemptAtMillis = now
            val info = when (val infoResult = dailyInfo(profile)) {
                is JmxResult.Success -> infoResult.value ?: return AutoCheckInResult.NO_ACTIVE_EVENT
                is JmxResult.Failure -> return AutoCheckInResult.FAILED
            }
            if (info.isSignedToday()) return AutoCheckInResult.ALREADY_SIGNED
            return when (val result = checkIn(profile, info.dailyId)) {
                is JmxResult.Success -> if (result.value.isAcceptedCheckInResult()) {
                    AutoCheckInResult.COMPLETED
                } else {
                    AutoCheckInResult.FAILED
                }
                is JmxResult.Failure -> AutoCheckInResult.FAILED
            }
        } finally {
            autoCheckInInFlight.set(false)
        }
    }
}

internal enum class AutoCheckInResult { COMPLETED, ALREADY_SIGNED, NO_ACTIVE_EVENT, FAILED }

private val autoCheckInInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

@Volatile
private var lastAutoCheckInAttemptAtMillis = 0L

private const val AUTO_CHECK_IN_MIN_INTERVAL_MILLIS = 15_000L

internal fun resolveCheckInAfterEmptyPayload(
    refreshedInfo: JmxResult<DailyCheckInfo?>
): JmxResult<ActionResult> {
    val info = when (refreshedInfo) {
        is JmxResult.Failure -> return refreshedInfo
        is JmxResult.Success -> refreshedInfo.value
            ?: return JmxResult.Failure(JmxError.EmptyData("签到提交返回空数据，且服务端未返回活动信息"))
    }
    if (!info.isSignedToday()) {
        return JmxResult.Failure(JmxError.EmptyData("签到提交返回空数据，且当日记录未确认"))
    }
    return JmxResult.Success(
        ActionResult(
            status = "ok",
            message = "签到成功",
            type = null,
            raw = emptyMap()
        )
    )
}

internal fun DailyCheckInfo.isSignedToday(now: Date = Date()): Boolean {
    val todayValues = todayDateValues(now)
    val todayDay = SimpleDateFormat("d", Locale.US).format(now).toInt()
    return records.any { record ->
        if (record.signed != true) return@any false
        val date = record.date?.trim().orEmpty()
        date in todayValues || date.substringAfterLast('-').toIntOrNull() == todayDay
    }
}

private fun ActionResult.isAcceptedCheckInResult(): Boolean {
    val normalizedStatus = status?.trim()?.lowercase(Locale.ROOT)
    return normalizedStatus.isNullOrEmpty() ||
        normalizedStatus in AUTO_CHECK_IN_SUCCESS_STATUSES ||
        message.orEmpty().containsAlreadySignedMessage()
}

private fun String.containsAlreadySignedMessage(): Boolean {
    val normalized = lowercase(Locale.ROOT)
    return "已签到" in normalized || "已簽到" in normalized || "already" in normalized
}

private val AUTO_CHECK_IN_SUCCESS_STATUSES = setOf("ok", "success", "true", "1")

internal fun todayDate(now: Date = Date()): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)

internal fun todayDateValues(now: Date = Date()): Set<String> {
    val fullDate = todayDate(now)
    val day = SimpleDateFormat("dd", Locale.US).format(now)
    return setOf(fullDate, day, day.toIntOrNull()?.toString().orEmpty())
}
