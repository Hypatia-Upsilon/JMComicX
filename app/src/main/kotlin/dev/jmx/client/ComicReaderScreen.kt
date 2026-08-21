package dev.jmx.client

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.BatteryManager
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import coil3.ImageLoader
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.imageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.request.transformations
import coil3.size.Size
import coil3.transform.Transformation
import dev.jmx.client.core.api.AlbumChapter
import dev.jmx.client.core.api.AlbumDetail
import dev.jmx.client.core.chapter.ChapterTemplate
import dev.jmx.client.core.download.ImageHttpHeaders
import dev.jmx.client.core.image.ImagePipeline
import dev.jmx.client.core.image.ImagePlan
import dev.jmx.client.core.protocol.JmxProtocolConstants
import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.runtime.JmxCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class ReaderLaunchRequest(
    val album: HomeAlbum,
    val detail: AlbumDetail,
    val initialChapterId: String,
    val initialPageIndex: Int = 0,
)

internal data class ReaderProgressUpdate(
    val album: HomeAlbum,
    val chapterId: String,
    val chapterName: String,
    val pageIndex: Int,
    val pageCount: Int,
)

internal data class ReaderPage(
    val index: Int,
    val url: String,
    val plan: ImagePlan,
    val headers: NetworkHeaders,
)

internal sealed interface ReaderChapterState {
    data object Loading : ReaderChapterState
    data class Content(
        val template: ChapterTemplate,
        val pages: List<ReaderPage>,
    ) : ReaderChapterState
    data class Error(val message: String) : ReaderChapterState
}

@Composable
internal fun ComicReaderScreen(
    request: ReaderLaunchRequest,
    repository: ComicReaderRepository,
    onProgress: (ReaderProgressUpdate) -> Unit,
    onBack: () -> Unit,
) {
    ComicReaderContent(
        request = request,
        repository = repository,
        onProgress = onProgress,
        onBack = onBack,
    )
}

@Composable
private fun ComicReaderContent(
    request: ReaderLaunchRequest,
    repository: ComicReaderRepository,
    onProgress: (ReaderProgressUpdate) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val chapters = remember(request.detail) { request.detail.readingChapters() }
    val initialChapterIndex = remember(request.initialChapterId, chapters) {
        chapters.indexOfFirst { it.id == request.initialChapterId }.takeIf { it >= 0 } ?: 0
    }
    var selectedChapterIndex by rememberSaveable(request.album.id) {
        mutableIntStateOf(initialChapterIndex)
    }
    var initialProgressConsumed by rememberSaveable(request.album.id) { mutableStateOf(false) }
    selectedChapterIndex = selectedChapterIndex.coerceIn(chapters.indices)
    val selectedChapter = chapters[selectedChapterIndex]
    val initialPageForChapter = if (
        !initialProgressConsumed && selectedChapter.id == request.initialChapterId
    ) {
        request.initialPageIndex.coerceAtLeast(0)
    } else {
        0
    }
    var chapterState by remember(request.album.id) {
        mutableStateOf<ReaderChapterState>(ReaderChapterState.Loading)
    }
    var chapterRetryKey by remember(request.album.id) { mutableIntStateOf(0) }
    var controlsVisible by rememberSaveable(request.album.id) { mutableStateOf(true) }
    var showCatalog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var currentPageIndex by remember(request.album.id) {
        mutableIntStateOf(request.initialPageIndex.coerceAtLeast(0))
    }
    var sliderDraft by remember { mutableStateOf<Float?>(null) }
    val failedPages = remember(selectedChapter.id) { mutableStateMapOf<Int, String>() }
    val loadedPages = remember(selectedChapter.id) { mutableStateMapOf<Int, Boolean>() }
    val pageRetryKeys = remember(selectedChapter.id) { mutableStateMapOf<Int, Int>() }
    val listState = key(selectedChapter.id) {
        rememberLazyListState(initialFirstVisibleItemIndex = initialPageForChapter)
    }
    val coroutineScope = rememberCoroutineScope()
    val settingsStore = remember(context) { ReaderSettingsStore(context) }
    var settings by remember { mutableStateOf(settingsStore.load()) }

    fun updateSettings(updated: ReaderSettings) {
        settings = updated
        settingsStore.save(updated)
    }

    fun selectChapter(index: Int) {
        val safeIndex = index.coerceIn(chapters.indices)
        if (safeIndex == selectedChapterIndex) return
        selectedChapterIndex = safeIndex
        initialProgressConsumed = true
        currentPageIndex = 0
        sliderDraft = null
        showCatalog = false
    }

    LaunchedEffect(selectedChapter.id, chapterRetryKey, repository) {
        chapterState = ReaderChapterState.Loading
        currentPageIndex = initialPageForChapter
        val loadedState = repository.loadChapter(
            chapterId = selectedChapter.id,
            imageHostHint = request.album.imageHost,
        )
        chapterState = loadedState
        if (loadedState is ReaderChapterState.Content) {
            val restoredPage = initialPageForChapter.coerceIn(loadedState.pages.indices)
            currentPageIndex = restoredPage
            listState.scrollToReaderPage(restoredPage)
            initialProgressConsumed = true
        }
    }

    val pages = (chapterState as? ReaderChapterState.Content)?.pages.orEmpty()
    val latestOnProgress by rememberUpdatedState(onProgress)

    fun reportProgress() {
        if (pages.isEmpty()) return
        latestOnProgress(
            ReaderProgressUpdate(
                album = request.album,
                chapterId = selectedChapter.id,
                chapterName = selectedChapter.displayName(selectedChapterIndex),
                pageIndex = currentPageIndex.coerceIn(pages.indices),
                pageCount = pages.size,
            ),
        )
    }

    fun closeReader() {
        reportProgress()
        onBack()
    }

    LaunchedEffect(listState, pages) {
        if (pages.isEmpty()) return@LaunchedEffect
        snapshotFlow {
            selectCurrentReaderPage(
                visiblePages = listState.layoutInfo.visibleItemsInfo.map {
                    ReaderVisiblePage(index = it.index, offset = it.offset, size = it.size)
                },
                viewportStart = listState.layoutInfo.viewportStartOffset,
                viewportEnd = listState.layoutInfo.viewportEndOffset,
            )
        }.distinctUntilChanged().collect { index ->
            currentPageIndex = index.coerceIn(pages.indices)
        }
    }

    LaunchedEffect(currentPageIndex, pages, loadedPages[currentPageIndex], repository) {
        if (pages.isNotEmpty() && loadedPages[currentPageIndex] == true) {
            repository.prefetchNext(pages, currentPageIndex)
        }
    }

    LaunchedEffect(request.album.id, selectedChapter.id, currentPageIndex, pages.size) {
        if (pages.isEmpty()) return@LaunchedEffect
        delay(READER_PROGRESS_SAVE_DELAY_MILLIS)
        reportProgress()
    }

    fun scrollToPage(index: Int) {
        if (pages.isEmpty()) return
        val target = index.coerceIn(pages.indices)
        // 先把页码推到目标值，再去滚动。
        // scrollToItem 是挂起函数，而进度条显示的是 currentPageIndex——后者要等列表真正落位、
        // snapshotFlow 再算一轮才会更新。若在这之前就交还控制权（松手时 sliderDraft 已被清空），
        // 进度条会先读到旧页码，然后被 MIUIX Slider 的非拖拽动画（stiffness=322，约半秒）
        // 慢慢地"退回"原处——表现就是"拖到 30 松手，进度条自己滑回 2"。
        // 万一列表到不了目标（章节末尾余量不足），后续 snapshotFlow 会把它纠正回真实页。
        currentPageIndex = target
        coroutineScope.launch {
            listState.scrollToReaderPage(target)
        }
    }

    val latestPreviousPage by rememberUpdatedState(newValue = { scrollToPage(currentPageIndex - 1) })
    val latestNextPage by rememberUpdatedState(newValue = { scrollToPage(currentPageIndex + 1) })
    DisposableEffect(settings.volumeKeyPaging, pages) {
        ReaderVolumeKeyDispatcher.handler = if (settings.volumeKeyPaging && pages.isNotEmpty()) {
            { keyCode ->
                when (keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        latestPreviousPage()
                        true
                    }
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        latestNextPage()
                        true
                    }
                    else -> false
                }
            }
        } else {
            null
        }
        onDispose { ReaderVolumeKeyDispatcher.handler = null }
    }

    val systemBarColor = MiuixTheme.colorScheme.background
    ReaderSystemBarsEffect(
        immersive = !controlsVisible && !showCatalog && !showSettings,
        barColor = systemBarColor.toArgb(),
        useDarkIcons = systemBarColor.luminance() > 0.5f,
    )
    BackHandler(onBack = ::closeReader)

    Scaffold(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface),
        ) {
            when (val state = chapterState) {
                ReaderChapterState.Loading -> ReaderLoading(chapterName = selectedChapter.displayName(selectedChapterIndex))
                is ReaderChapterState.Error -> ReaderError(
                    message = state.message,
                    onRetry = { chapterRetryKey++ },
                    onBack = ::closeReader,
                )
                is ReaderChapterState.Content -> ReaderPages(
                    pages = state.pages,
                    listState = listState,
                    failedPages = failedPages,
                    retryKeys = pageRetryKeys,
                    onPageFailed = { index, message -> failedPages[index] = message },
                    onPageLoaded = { index ->
                        failedPages.remove(index)
                        loadedPages[index] = true
                    },
                    onRetryPage = { index ->
                        failedPages.remove(index)
                        pageRetryKeys[index] = (pageRetryKeys[index] ?: 0) + 1
                    },
                    onToggleControls = { controlsVisible = !controlsVisible },
                )
            }

            if (!controlsVisible) {
                ImmersiveStatus(
                    showBatteryTime = settings.showBatteryTime,
                    showPageNumber = settings.showPageNumber,
                    currentPage = currentPageIndex + 1,
                    totalPages = pages.size,
                )
            }

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                ReaderTopBar(
                    chapter = selectedChapter,
                    chapterIndex = selectedChapterIndex,
                    chapterCount = chapters.size,
                    onBack = ::closeReader,
                )
            }

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ReaderControlPanel(
                    currentPageIndex = currentPageIndex,
                    totalPages = pages.size,
                    sliderDraft = sliderDraft,
                    onSliderChange = { sliderDraft = it },
                    onSliderFinished = {
                        // 顺序要紧：scrollToPage 会同步把 currentPageIndex 推到目标页，
                        // 之后清空草稿才不会让进度条露出一帧旧页码。
                        scrollToPage(readerPageFromSlider(sliderDraft ?: currentPageIndex.toFloat(), pages.size))
                        sliderDraft = null
                    },
                    canPreviousChapter = selectedChapterIndex > 0,
                    canNextChapter = selectedChapterIndex < chapters.lastIndex,
                    failedPage = failedPages.keys.minOrNull(),
                    onPreviousChapter = { selectChapter(selectedChapterIndex - 1) },
                    onNextChapter = { selectChapter(selectedChapterIndex + 1) },
                    onShowCatalog = { showCatalog = true },
                    onShowSettings = { showSettings = true },
                )
            }

            ReaderCatalogSheet(
                show = showCatalog,
                chapters = chapters,
                selectedChapterIndex = selectedChapterIndex,
                onSelect = ::selectChapter,
                onDismiss = { showCatalog = false },
            )
            ReaderSettingsSheet(
                show = showSettings,
                settings = settings,
                onSettingsChange = ::updateSettings,
                onDismiss = { showSettings = false },
            )
        }
    }
}

@Composable
private fun ReaderPages(
    pages: List<ReaderPage>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    failedPages: Map<Int, String>,
    retryKeys: Map<Int, Int>,
    onPageFailed: (Int, String) -> Unit,
    onPageLoaded: (Int) -> Unit,
    onRetryPage: (Int) -> Unit,
    onToggleControls: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var scale by remember(pages) { mutableFloatStateOf(READER_MIN_ZOOM) }
    var offset by remember(pages) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember(pages) { mutableStateOf(IntSize.Zero) }
    var zoomAnimation by remember(pages) { mutableStateOf<Job?>(null) }
    DisposableEffect(pages) {
        onDispose { zoomAnimation?.cancel() }
    }

    fun animateZoom(targetScale: Float, focus: Offset) {
        zoomAnimation?.cancel()
        val startScale = scale
        val startOffset = offset
        val targetOffset = if (!isReaderZoomed(targetScale)) {
            Offset.Zero
        } else {
            readerZoomOffsetAfterGesture(
                currentOffset = ReaderZoomOffset(startOffset.x, startOffset.y),
                currentScale = startScale,
                requestedScale = targetScale,
                focusX = focus.x,
                focusY = focus.y,
                viewportWidth = viewportSize.width.toFloat(),
                viewportHeight = viewportSize.height.toFloat(),
            ).offset.toOffset()
        }
        zoomAnimation = coroutineScope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(READER_DOUBLE_TAP_ANIMATION_MILLIS),
            ) { progress, _ ->
                scale = startScale + (targetScale - startScale) * progress
                offset = Offset(
                    x = startOffset.x + (targetOffset.x - startOffset.x) * progress,
                    y = startOffset.y + (targetOffset.y - startOffset.y) * progress,
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { size ->
                viewportSize = size
                offset = constrainReaderZoomOffset(
                    offset = ReaderZoomOffset(offset.x, offset.y),
                    scale = scale,
                    viewportWidth = size.width.toFloat(),
                    viewportHeight = size.height.toFloat(),
                ).toOffset()
            }
            .pointerInput(pages) {
                detectTapGestures(
                    onTap = { onToggleControls() },
                    onDoubleTap = { position ->
                        animateZoom(
                            targetScale = if (isReaderZoomed(scale)) {
                                READER_MIN_ZOOM
                            } else {
                                READER_DOUBLE_TAP_ZOOM
                            },
                            focus = position,
                        )
                    },
                )
            }
            .pointerInput(pages) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var transforming = false
                    do {
                        val event = awaitPointerEvent()
                        val pressedPointers = event.changes.count { it.pressed }
                        if (pressedPointers > 0 && (pressedPointers >= 2 || transforming)) {
                            transforming = true
                            zoomAnimation?.cancel()
                            val centroid = event.calculateCentroid()
                            val pan = event.calculatePan()
                            val next = readerZoomOffsetAfterGesture(
                                currentOffset = ReaderZoomOffset(offset.x, offset.y),
                                currentScale = scale,
                                requestedScale = scale * event.calculateZoom(),
                                focusX = centroid.x,
                                focusY = centroid.y,
                                viewportWidth = viewportSize.width.toFloat(),
                                viewportHeight = viewportSize.height.toFloat(),
                                panX = pan.x,
                                panY = pan.y,
                            )
                            scale = next.scale
                            offset = next.offset.toOffset()
                            event.changes.forEach { it.consume() }
                        } else if (pressedPointers == 1 && isReaderZoomed(scale)) {
                            zoomAnimation?.cancel()
                            val pan = event.calculatePan()
                            val nextOffset = constrainReaderZoomOffset(
                                offset = ReaderZoomOffset(offset.x + pan.x, offset.y),
                                scale = scale,
                                viewportWidth = viewportSize.width.toFloat(),
                                viewportHeight = viewportSize.height.toFloat(),
                            )
                            offset = nextOffset.toOffset()
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            userScrollEnabled = true,
        ) {
            itemsIndexed(
                items = pages,
                key = { _, page -> page.plan.cacheKey },
                contentType = { _, _ -> "reader-page" },
            ) { index, page ->
                ReaderPageImage(
                    page = page,
                    retryKey = retryKeys[index] ?: 0,
                    knownError = failedPages[index],
                    onError = { message -> onPageFailed(index, message) },
                    onLoaded = { onPageLoaded(index) },
                    onRetry = { onRetryPage(index) },
                )
            }
        }
    }
}

/**
 * 一页漫画。
 *
 * 关键约束：**这个 item 从第一次测量起就必须有非零高度。**
 *
 * [SubcomposeAsyncImage] 在 Coil 的 `State.Empty`（请求还没发出的那一帧）既不会调 `loading`
 * 槽也没有可用的固有尺寸，此时它量出来是 0 高。而 `LazyListState.scrollToItem` 会
 * `forceRemeasure()`——恰好在目标页首次被组合的那一次测量里同步跑完。于是 LazyColumn 看到
 * 目标页往后全是 0 高，就一路往后组合（整话的图片请求被一次性全部发出，这就是"跳转后
 * 下面的漫画加载极慢"），仍填不满视口，最后按 LazyList 的既有行为**往回**补页，
 * 直到撞上已经加载好、有真实高度的那几页为止——落点因此变成第 2 页、再试变成第 7 页，
 * 每试一次前进一点；手动翻到第 27 页（沿途每页都量过真实高度）之后拖动就正常了。
 * 落位成功的那次则是另一半症状：下方各页还是 0 高，列表以为已经到底，于是只能往上翻，
 * 等图片陆续加载出高度才恢复。
 *
 * 因此在这一页量出真实高度之前，用 [READER_PAGE_PLACEHOLDER_HEIGHT] 兜住最小高度。
 * 用 `heightIn(min=)` 而不是固定 `height()`：长图页比占位更高时不会被裁掉。
 */
@Composable
private fun ReaderPageImage(
    page: ReaderPage,
    retryKey: Int,
    knownError: String?,
    onError: (String) -> Unit,
    onLoaded: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val request = remember(page, retryKey) { buildReaderImageRequest(context, page, retryKey) }
    // 按 item 实例记，不能用外层按页码记的 loadedPages：那份记录在页面被回收后仍是 true，
    // 回翻时新组合的 item 又会从 0 高开始。
    var hasIntrinsicHeight by remember(page.plan.cacheKey, retryKey) { mutableStateOf(false) }
    if (knownError != null) {
        ReaderPageError(pageNumber = page.index + 1, message = knownError, onRetry = onRetry)
        return
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = "第 ${page.index + 1} 页",
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (hasIntrinsicHeight) {
                    Modifier
                } else {
                    Modifier.heightIn(min = READER_PAGE_PLACEHOLDER_HEIGHT)
                },
            ),
        contentScale = ContentScale.FillWidth,
        loading = { ReaderPageLoading(page.index + 1) },
        error = { state ->
            LaunchedEffect(state.result.throwable) {
                onError(state.result.throwable.message ?: "图片请求失败")
            }
            ReaderPageError(
                pageNumber = page.index + 1,
                message = state.result.throwable.message ?: "图片请求失败",
                onRetry = onRetry,
            )
        },
        success = {
            LaunchedEffect(page.plan.cacheKey) {
                hasIntrinsicHeight = true
                onLoaded()
            }
            SubcomposeAsyncImageContent(modifier = Modifier.fillMaxWidth())
        },
    )
}

@Composable
private fun ReaderPageLoading(pageNumber: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(READER_PAGE_PLACEHOLDER_HEIGHT)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(size = 28.dp, strokeWidth = 3.dp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "正在加载第 $pageNumber 页",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun ReaderPageError(pageNumber: Int, message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(READER_PAGE_PLACEHOLDER_HEIGHT)
            .background(MiuixTheme.colorScheme.errorContainer)
            .clickable(onClick = onRetry)
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = MiuixIcons.Refresh,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MiuixTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "第 $pageNumber 页加载失败，点击重试",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onErrorContainer,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = message,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ReaderTopBar(
    chapter: AlbumChapter,
    chapterIndex: Int,
    chapterCount: Int,
    onBack: () -> Unit,
) {
    Surface(color = MiuixTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 36.dp, end = 16.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MiuixIcons.Back,
                    contentDescription = "返回详情",
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "第 ${chapterIndex + 1} / $chapterCount 话",
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onBackground,
                )
                Text(
                    text = chapter.displayName(chapterIndex),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ReaderControlPanel(
    currentPageIndex: Int,
    totalPages: Int,
    sliderDraft: Float?,
    onSliderChange: (Float) -> Unit,
    onSliderFinished: () -> Unit,
    canPreviousChapter: Boolean,
    canNextChapter: Boolean,
    failedPage: Int?,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onShowCatalog: () -> Unit,
    onShowSettings: () -> Unit,
) {
    val safeTotal = totalPages.coerceAtLeast(1)
    val sliderEnd = (safeTotal - 1).toFloat().coerceAtLeast(1f)
    val displayedPage = readerPageFromSlider(sliderDraft ?: currentPageIndex.toFloat(), safeTotal) + 1
    Surface(color = MiuixTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (totalPages > 0) "$displayedPage / $totalPages" else "正在加载章节",
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                )
                Text(
                    text = if (totalPages > 0) "${displayedPage * 100 / totalPages}%" else "",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (totalPages > 0) {
                Slider(
                    value = (sliderDraft ?: currentPageIndex.toFloat()).coerceIn(0f, sliderEnd),
                    onValueChange = onSliderChange,
                    onValueChangeFinished = onSliderFinished,
                    enabled = totalPages > 1,
                    valueRange = 0f..sliderEnd,
                    steps = (safeTotal - 2).coerceAtLeast(0),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Spacer(modifier = Modifier.height(28.dp))
            }
            if (failedPage != null) {
                Text(
                    text = "第 ${failedPage + 1} 页加载失败，可在原位置点击重试",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReaderIconButton(
                    icon = MiuixIcons.ChevronForward,
                    description = "上一话",
                    enabled = canPreviousChapter,
                    mirrorHorizontally = true,
                    onClick = onPreviousChapter,
                )
                ReaderIconButton(
                    icon = MiuixIcons.ListView,
                    description = "选择章节",
                    enabled = true,
                    onClick = onShowCatalog,
                )
                ReaderIconButton(
                    icon = MiuixIcons.ChevronForward,
                    description = "下一话",
                    enabled = canNextChapter,
                    onClick = onNextChapter,
                )
                ReaderIconButton(
                    icon = MiuixIcons.Settings,
                    description = "阅读设置",
                    enabled = true,
                    onClick = onShowSettings,
                )
            }
        }
    }
}

@Composable
private fun ReaderIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    mirrorHorizontally: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.graphicsLayer { scaleX = if (mirrorHorizontally) -1f else 1f },
            tint = if (enabled) {
                MiuixTheme.colorScheme.onSurface
            } else {
                MiuixTheme.colorScheme.disabledOnSurface
            },
        )
    }
}

@Composable
private fun ReaderCatalogSheet(
    show: Boolean,
    chapters: List<AlbumChapter>,
    selectedChapterIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayBottomSheet(
        show = show,
        title = "选择章节",
        onDismissRequest = onDismiss,
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = 560.dp)) {
            itemsIndexed(
                items = chapters,
                key = { _, chapter -> chapter.id },
            ) { index, chapter ->
                BasicComponent(
                    title = chapter.displayName(index),
                    summary = "JM${chapter.id}",
                    onClick = { onSelect(index) },
                    startAction = {
                        Icon(
                            imageVector = MiuixIcons.ListView,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 14.dp),
                            tint = if (index == selectedChapterIndex) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                        )
                    },
                    endActions = {
                        if (index == selectedChapterIndex) {
                            Text(
                                text = "当前",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ReaderSettingsSheet(
    show: Boolean,
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var statusOptionsExpanded by rememberSaveable { mutableStateOf(false) }
    OverlayBottomSheet(
        show = show,
        title = "阅读设置",
        onDismissRequest = onDismiss,
    ) {
        Column {
            SwitchPreference(
                title = "音量键翻页",
                summary = "音量上键上一页，音量下键下一页",
                checked = settings.volumeKeyPaging,
                onCheckedChange = { onSettingsChange(settings.copy(volumeKeyPaging = it)) },
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.VolumeUp,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 14.dp),
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                },
            )
            BasicComponent(
                title = "沉浸阅读时状态显示",
                summary = "选择隐藏操作栏后仍保留的信息",
                onClick = { statusOptionsExpanded = !statusOptionsExpanded },
                endActions = {
                    Icon(
                        imageVector = MiuixIcons.ChevronForward,
                        contentDescription = if (statusOptionsExpanded) "收起" else "展开",
                        modifier = Modifier.graphicsLayer {
                            rotationZ = if (statusOptionsExpanded) 90f else 0f
                        },
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                },
            )
            AnimatedVisibility(visible = statusOptionsExpanded) {
                Column(modifier = Modifier.padding(start = 24.dp)) {
                    SwitchPreference(
                        title = "电量、充放电状态与时间",
                        summary = "显示在右上角",
                        checked = settings.showBatteryTime,
                        onCheckedChange = { onSettingsChange(settings.copy(showBatteryTime = it)) },
                    )
                    SwitchPreference(
                        title = "当前话页码",
                        summary = "以 当前页/总页数 显示在右下角",
                        checked = settings.showPageNumber,
                        onCheckedChange = { onSettingsChange(settings.copy(showPageNumber = it)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ImmersiveStatus(
    showBatteryTime: Boolean,
    showPageNumber: Boolean,
    currentPage: Int,
    totalPages: Int,
) {
    val battery = rememberBatteryStatus()
    val currentTime by produceState(initialValue = formatReaderTime()) {
        while (true) {
            value = formatReaderTime()
            delay(30_000L)
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        if (showBatteryTime) {
            Text(
                text = "${if (battery.isCharging) "充电" else "放电"} ${battery.level}%  $currentTime",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
        if (showPageNumber && totalPages > 0) {
            Text(
                text = "$currentPage/$totalPages",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ReaderLoading(chapterName: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "正在准备 $chapterName",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun ReaderError(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "章节加载失败",
            style = MiuixTheme.textStyles.title3,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(text = "返回", onClick = onBack)
            TextButton(text = "重试", onClick = onRetry)
        }
    }
}

internal class ComicReaderRepository(
    context: Context,
    private val core: JmxCore,
) {
    private val applicationContext = context.applicationContext
    private val imageLoader: ImageLoader = applicationContext.imageLoader
    private val imagePipeline = ImagePipeline()

    /**
     * 载入一话。
     *
     * 整段在 IO 线程上跑：除了出网与解密，[ImagePipeline.plan] 还要按页算一次 MD5，
     * 一话上百页就是上百次——留在调用方（Compose 的 LaunchedEffect，主线程）上，
     * 表现就是"进阅览页转圈快转完时卡一下"。
     */
    suspend fun loadChapter(
        chapterId: String,
        imageHostHint: String?,
    ): ReaderChapterState = withContext(Dispatchers.IO) {
        try {
            loadChapterFromApi(chapterId, imageHostHint)?.let { return@withContext it }
            val templateResult = withTimeoutOrNull(READER_TEMPLATE_TIMEOUT_MILLIS) {
                core.chapterApi.template(chapterId, shunt = DEFAULT_IMAGE_SHUNT)
            } ?: return@withContext ReaderChapterState.Error("章节准备超时，请检查网络后重试。")
            when (val result = templateResult) {
                is JmxResult.Success -> result.value.toReaderState()
                is JmxResult.Failure -> ReaderChapterState.Error(result.error.toUiMessage())
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ReaderChapterState.Error(error.message ?: "章节加载出现未知异常。")
        }
    }

    private suspend fun loadChapterFromApi(
        chapterId: String,
        imageHostHint: String?,
    ): ReaderChapterState? {
        val photo = when (val result = core.chapterApi.detail(chapterId)) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> {
                return null
            }
        }
        if (photo.pageArr.isEmpty()) return null
        val numericId = photo.id.toIntOrNull() ?: chapterId.toIntOrNull() ?: return null
        val imageHost = photo.imageDomain
            ?: imageHostHint
            // 兜底改为线路表当前最优的那台，而不是内置表的第一台：
            // 后者是写死的顺序，被墙或过载时整章白屏且不会自愈。
            ?: core.imageHostRegistry.current()
        val scrambleId = photo.scrambleId
            ?: core.chapterApi.cachedScrambleId(photo.id, photo.albumId)
            ?: JmxProtocolConstants.Scramble220980
        return ChapterTemplate(
            albumId = numericId,
            scrambleId = scrambleId,
            speed = "",
            imageHost = imageHost,
            chapterId = photo.id,
            cacheSuffix = "",
            imageFileNames = photo.pageArr,
        ).toReaderState()
    }

    private fun ChapterTemplate.toReaderState(): ReaderChapterState {
        // /chapter 给的 data_original_domain 未必在内置线路表里，先并进去，
        // 这一章的取图才会被选路拦截器接管（失败自动换机、成败计入健康度）。
        core.imageHostRegistry.rememberHost(imageHost)
        val headers = ImageHttpHeaders.default(refererHost = imageHost).toCoilHeaders()
        val pages = imageUrls.mapIndexed { index, url ->
            ReaderPage(
                index = index,
                url = url,
                plan = imagePipeline.plan(url, albumId, scrambleId),
                headers = headers,
            )
        }
        return if (pages.isEmpty()) {
            ReaderChapterState.Error("章节没有返回可阅读的图片。")
        } else {
            ReaderChapterState.Content(template = this, pages = pages)
        }
    }

    /**
     * 预取当前页之后的若干页。
     *
     * 原来只预取一页：连续翻页时用户几乎总是"翻到下一页 → 等它下载"，
     * 预取的那一页刚好被立刻消费掉，等于没有缓冲。取 [PREFETCH_AHEAD_PAGES] 页是因为
     * 单页解码后常驻内存不小（长图尤甚），再多就要和内存缓存里已看过的页互相挤。
     * 顺序发起，让更近的那页先拿到带宽。
     */
    suspend fun prefetchNext(pages: List<ReaderPage>, currentIndex: Int) {
        for (offset in 1..PREFETCH_AHEAD_PAGES) {
            val nextIndex = currentIndex + offset
            if (nextIndex !in pages.indices) return
            imageLoader.execute(buildReaderImageRequest(applicationContext, pages[nextIndex], retryKey = 0))
        }
    }

    private companion object {
        const val PREFETCH_AHEAD_PAGES = 2
    }
}

internal fun buildReaderImageRequest(context: Context, page: ReaderPage, retryKey: Int): ImageRequest {
    val builder = ImageRequest.Builder(context)
        .data(page.url)
        .httpHeaders(page.headers)
        .allowHardware(!page.plan.requiresRestore)
        .crossfade(false)
        .memoryCacheKey(
            "${page.plan.cacheKey}:reader:${page.plan.segmentCount}:$READER_RESTORE_VERSION:$retryKey",
        )
        .diskCacheKey(page.plan.cacheKey)
    if (page.plan.requiresRestore) {
        builder
            .size(Size.ORIGINAL)
            .transformations(JmxUnscrambleTransformation(page.plan))
    }
    return builder.build()
}

private class JmxUnscrambleTransformation(
    private val plan: ImagePlan,
    private val pipeline: ImagePipeline = ImagePipeline(),
) : Transformation() {
    override val cacheKey: String = "$READER_RESTORE_VERSION:${plan.segmentCount}"
    private val restorePaint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
        isDither = false
    }

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val moves = pipeline.restoreMoves(input.height, plan.segmentCount)
        if (moves.isEmpty()) return input

        val output = createBitmap(
            input.width,
            input.height,
            input.config ?: Bitmap.Config.ARGB_8888,
        ).apply {
            density = input.density
            setHasAlpha(input.hasAlpha())
        }
        val canvas = Canvas(output)
        moves.forEach { move ->
            canvas.drawBitmap(
                input,
                Rect(0, move.sourceY, input.width, move.sourceY + move.height),
                Rect(0, move.targetY, input.width, move.targetY + move.height),
                restorePaint,
            )
        }
        return output
    }
}

internal data class ReaderVisiblePage(
    val index: Int,
    val offset: Int,
    val size: Int,
)

internal fun selectCurrentReaderPage(
    visiblePages: List<ReaderVisiblePage>,
    viewportStart: Int,
    viewportEnd: Int,
): Int {
    if (visiblePages.isEmpty()) return 0
    return visiblePages.maxByOrNull { page ->
        val visibleStart = maxOf(page.offset, viewportStart)
        val visibleEnd = minOf(page.offset + page.size, viewportEnd)
        (visibleEnd - visibleStart).coerceAtLeast(0)
    }?.index ?: visiblePages.first().index
}

internal fun readerPageFromSlider(value: Float, totalPages: Int): Int {
    if (totalPages <= 1) return 0
    return value.roundToInt().coerceIn(0, totalPages - 1)
}

/**
 * 落位到目标页，并在随后一两帧里确认落点。
 *
 * 目标页首次被组合的那一次测量（`scrollToItem` 内部的 `forceRemeasure()` 就发生在那里）
 * 未必量得到真实高度，LazyColumn 会因为填不满视口而把落点往回拉，见 ReaderPageImage 的说明。
 * [ReaderPageImage] 已经用占位高度堵住了这个洞，这里只作为兜底再确认一次。
 *
 * 次数刻意压得很小：真到了章节末尾余量不足时，落点本就该被夹住，不该无限纠正。
 * 用户此时若已开始拖动列表，[LazyListState.scrollToItem] 会被优先级更高的手势抢掉滚动权
 * 并抛出取消，这个循环随之安静结束。
 */
private suspend fun LazyListState.scrollToReaderPage(target: Int) {
    scrollToItem(target)
    repeat(READER_JUMP_SETTLE_ATTEMPTS) {
        withFrameNanos { }
        if (firstVisibleItemIndex == target) return
        scrollToItem(target)
    }
}

internal fun isReaderZoomed(scale: Float): Boolean =
    scale > READER_MIN_ZOOM + READER_ZOOM_EPSILON

internal data class ReaderZoomOffset(
    val x: Float,
    val y: Float,
) {
    fun toOffset(): Offset = Offset(x, y)
}

internal data class ReaderZoomTransform(
    val scale: Float,
    val offset: ReaderZoomOffset,
)

internal fun constrainReaderZoomOffset(
    offset: ReaderZoomOffset,
    scale: Float,
    viewportWidth: Float,
    viewportHeight: Float,
): ReaderZoomOffset {
    val safeScale = scale.coerceIn(READER_MIN_ZOOM, READER_MAX_ZOOM)
    val maxX = (viewportWidth * (safeScale - 1f) / 2f).coerceAtLeast(0f)
    val maxY = (viewportHeight * (safeScale - 1f) / 2f).coerceAtLeast(0f)
    return ReaderZoomOffset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY),
    )
}

internal fun readerZoomOffsetAfterGesture(
    currentOffset: ReaderZoomOffset,
    currentScale: Float,
    requestedScale: Float,
    focusX: Float,
    focusY: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    panX: Float = 0f,
    panY: Float = 0f,
): ReaderZoomTransform {
    val nextScale = requestedScale.coerceIn(READER_MIN_ZOOM, READER_MAX_ZOOM)
    if (!isReaderZoomed(nextScale) || viewportWidth <= 0f || viewportHeight <= 0f) {
        return ReaderZoomTransform(READER_MIN_ZOOM, ReaderZoomOffset(0f, 0f))
    }
    val safeCurrentScale = currentScale.coerceIn(READER_MIN_ZOOM, READER_MAX_ZOOM)
    val ratio = nextScale / safeCurrentScale
    val centerX = viewportWidth / 2f
    val centerY = viewportHeight / 2f
    val relativeFocusX = if (focusX.isFinite()) focusX - centerX else 0f
    val relativeFocusY = if (focusY.isFinite()) focusY - centerY else 0f
    val candidate = ReaderZoomOffset(
        x = currentOffset.x * ratio + relativeFocusX * (1f - ratio) + panX,
        y = currentOffset.y * ratio + relativeFocusY * (1f - ratio) + panY,
    )
    return ReaderZoomTransform(
        scale = nextScale,
        offset = constrainReaderZoomOffset(
            offset = candidate,
            scale = nextScale,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        ),
    )
}

internal object ReaderVolumeKeyDispatcher {
    var handler: ((Int) -> Boolean)? = null

    fun shouldConsume(keyCode: Int): Boolean {
        return handler != null && keyCode in READER_VOLUME_KEY_CODES
    }

    fun dispatch(keyCode: Int): Boolean = handler?.invoke(keyCode) == true
}

private data class ReaderSettings(
    val volumeKeyPaging: Boolean = false,
    val showBatteryTime: Boolean = true,
    val showPageNumber: Boolean = true,
)

private class ReaderSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        READER_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): ReaderSettings {
        return ReaderSettings(
            volumeKeyPaging = preferences.getBoolean(READER_VOLUME_KEYS, false),
            showBatteryTime = preferences.getBoolean(READER_BATTERY_TIME, true),
            showPageNumber = preferences.getBoolean(READER_PAGE_NUMBER, true),
        )
    }

    fun save(settings: ReaderSettings) {
        preferences.edit {
            putBoolean(READER_VOLUME_KEYS, settings.volumeKeyPaging)
            putBoolean(READER_BATTERY_TIME, settings.showBatteryTime)
            putBoolean(READER_PAGE_NUMBER, settings.showPageNumber)
        }
    }
}

private data class ReaderBatteryStatus(val level: Int, val isCharging: Boolean)

@Composable
private fun rememberBatteryStatus(): ReaderBatteryStatus {
    val context = LocalContext.current
    var status by remember { mutableStateOf(context.readBatteryStatus(null)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                status = context.readBatteryStatus(intent)
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        if (sticky != null) status = context.readBatteryStatus(sticky)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return status
}

private fun Context.readBatteryStatus(intent: Intent?): ReaderBatteryStatus {
    val source = intent ?: registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = source?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
    val scale = source?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
    val state = source?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
    return ReaderBatteryStatus(
        level = (level * 100 / scale).coerceIn(0, 100),
        isCharging = state == BatteryManager.BATTERY_STATUS_CHARGING ||
            state == BatteryManager.BATTERY_STATUS_FULL,
    )
}

@Composable
private fun ReaderSystemBarsEffect(
    immersive: Boolean,
    barColor: Int,
    useDarkIcons: Boolean,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity, immersive, barColor, useDarkIcons) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val previousLightStatusBars = controller?.isAppearanceLightStatusBars
        val previousLightNavigationBars = controller?.isAppearanceLightNavigationBars
        @Suppress("DEPRECATION")
        val previousStatusBarColor = window?.statusBarColor
        @Suppress("DEPRECATION")
        val previousNavigationBarColor = window?.navigationBarColor
        @Suppress("DEPRECATION")
        if (window != null) {
            window.statusBarColor = barColor
            window.navigationBarColor = barColor
        }
        controller?.isAppearanceLightStatusBars = useDarkIcons
        controller?.isAppearanceLightNavigationBars = useDarkIcons
        if (immersive) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (previousLightStatusBars != null) {
                controller.isAppearanceLightStatusBars = previousLightStatusBars
            }
            if (previousLightNavigationBars != null) {
                controller.isAppearanceLightNavigationBars = previousLightNavigationBars
            }
            @Suppress("DEPRECATION")
            if (window != null && previousStatusBarColor != null && previousNavigationBarColor != null) {
                window.statusBarColor = previousStatusBarColor
                window.navigationBarColor = previousNavigationBarColor
            }
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

private fun Map<String, String>.toCoilHeaders(): NetworkHeaders {
    return NetworkHeaders.Builder().apply {
        forEach { (name, value) -> add(name, value) }
    }.build()
}

private fun formatReaderTime(): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
}

private val READER_PAGE_PLACEHOLDER_HEIGHT = 720.dp
private const val READER_PREFERENCES_NAME = "reader_settings"
private const val READER_VOLUME_KEYS = "volume_key_paging"
private const val READER_BATTERY_TIME = "show_battery_time"
private const val READER_PAGE_NUMBER = "show_page_number"
private const val READER_PROGRESS_SAVE_DELAY_MILLIS = 350L

/** 跳页后重新落位的确认次数，见 scrollToPage。 */
private const val READER_JUMP_SETTLE_ATTEMPTS = 2
private const val READER_MIN_ZOOM = 1f
private const val READER_MAX_ZOOM = 4f
private const val READER_DOUBLE_TAP_ZOOM = 2.5f
private const val READER_ZOOM_EPSILON = 0.01f
private const val READER_DOUBLE_TAP_ANIMATION_MILLIS = 180
private const val READER_TEMPLATE_TIMEOUT_MILLIS = 15_000L
private val READER_VOLUME_KEY_CODES = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)
private const val READER_RESTORE_VERSION = "decoded-v1"
