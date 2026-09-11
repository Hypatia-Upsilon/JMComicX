package dev.jmx.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.menu.WindowIconCascadingDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AccountCollectionScreen(
    innerPadding: PaddingValues,
    kind: AccountCollectionKind,
    repository: AccountDataRepository,
    sessionRevision: Int,
    liftedAlbumId: String?,
    onAlbumSelected: (HomeAlbum, Rect) -> Unit,
    onRequireLogin: () -> Unit,
    backdrop: LayerBackdrop? = null,
    favoriteOrder: FavoriteSortOrder = FavoriteSortOrder.Default,
    favoriteDirection: FavoriteSortDirection = FavoriteSortDirection.Default,
    updateRecords: Map<String, AlbumUpdateRecord> = emptyMap(),
) {
    // 换排序等于换一份列表：连 retryKey 一起重置，让页面立刻回到加载态而不是把旧顺序留在屏上。
    var state by remember(kind, favoriteOrder, favoriteDirection) {
        mutableStateOf<AccountCollectionState>(AccountCollectionState.Loading)
    }
    var retryKey by remember(kind, favoriteOrder, favoriteDirection) { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(kind, favoriteOrder, favoriteDirection, retryKey, sessionRevision, repository) {
        val result = repository.loadFavoriteLogicalPage(
            kind = kind,
            logicalPage = 1,
            favoriteOrder = favoriteOrder,
            direction = favoriteDirection,
            knownServerPageCount = null,
        )
        state = when (result) {
            is JmxResult.Success -> {
                val total = result.value.total
                AccountCollectionState.Content(
                    albums = result.value.albums,
                    total = total,
                    nextPage = 2,
                    serverPageCount = result.value.serverPageCount,
                    endReached = result.value.albums.isEmpty() ||
                        (total != null && result.value.albums.size >= total),
                )
            }
            is JmxResult.Failure -> {
                if (result.error.requiresSessionRecovery()) onRequireLogin()
                AccountCollectionState.Error(
                    if (result.error.requiresSessionRecovery()) {
                        "登录状态已失效，请重新登录"
                    } else {
                        result.error.toUiMessage()
                    },
                )
            }
        }
    }

    fun loadMore() {
        val content = state as? AccountCollectionState.Content ?: return
        if (content.loadingMore || content.endReached) return
        state = content.copy(loadingMore = true, loadMoreError = null)
        coroutineScope.launch {
            val result = repository.loadFavoriteLogicalPage(
                kind = kind,
                logicalPage = content.nextPage,
                favoriteOrder = favoriteOrder,
                direction = favoriteDirection,
                knownServerPageCount = content.serverPageCount,
            )
            val latest = state as? AccountCollectionState.Content ?: return@launch
            state = when (result) {
                is JmxResult.Success -> {
                    val total = result.value.total
                    val existingIds = latest.albums.mapTo(hashSetOf()) { it.id }
                    val incoming = result.value.albums.filter { it.id !in existingIds }
                    val merged = latest.albums + incoming
                    latest.copy(
                        albums = merged,
                        total = total ?: latest.total,
                        nextPage = latest.nextPage + 1,
                        serverPageCount = result.value.serverPageCount ?: latest.serverPageCount,
                        loadingMore = false,
                        endReached = incoming.isEmpty() ||
                            (total != null && merged.size >= total),
                    )
                }
                is JmxResult.Failure -> latest.copy(
                    loadingMore = false,
                    loadMoreError = if (result.error.requiresSessionRecovery()) {
                        "登录状态已失效，请重新登录"
                    } else {
                        result.error.toUiMessage()
                    },
                ).also {
                    if (result.error.requiresSessionRecovery()) onRequireLogin()
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
            .background(MiuixTheme.colorScheme.surface),
    ) {
        when (val current = state) {
            AccountCollectionState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            is AccountCollectionState.Error -> CollectionMessage(
                message = current.message,
                action = "重试",
                onAction = { retryKey++ },
            )
            is AccountCollectionState.Content -> if (current.albums.isEmpty()) {
                CollectionMessage(
                    message = if (kind == AccountCollectionKind.FAVORITES) "暂无漫画收藏" else "暂无观看历史",
                )
            } else {
                AccountCollectionGrid(
                    innerPadding = innerPadding,
                    state = current,
                    liftedAlbumId = liftedAlbumId,
                    onAlbumSelected = onAlbumSelected,
                    onLoadMore = ::loadMore,
                    updateChaptersOf = { albumId ->
                        // 只有收藏页需要"更新 N 章"：观看历史不是订阅关系，标更新没有意义。
                        if (kind == AccountCollectionKind.FAVORITES) {
                            updateRecords[albumId]?.pendingChapters ?: 0
                        } else {
                            0
                        }
                    },
                )
            }
        }
    }
}

/**
 * 收藏页顶栏右上角的排序菜单。
 *
 * 排序字段由服务端完成（`o` 参数），所以选完必须重新拉第一页——收藏摘要里没有任何时间字段，
 * 本地排不出来。方向做成二级子菜单：MIUIX 级联菜单最深两级，正好够"字段 + 方向"。
 */
@Composable
internal fun FavoriteSortAction(
    order: FavoriteSortOrder,
    direction: FavoriteSortDirection,
    onOrderSelected: (FavoriteSortOrder) -> Unit,
    onDirectionSelected: (FavoriteSortDirection) -> Unit,
) {
    val entry = DropdownEntry(
        items = buildList {
            FavoriteSortOrder.entries.forEach { option ->
                add(
                    DropdownItem(
                        text = option.label,
                        selected = option == order,
                        onClick = { onOrderSelected(option) },
                    ),
                )
            }
            add(
                DropdownItem(
                    text = "排序方向",
                    summary = direction.label,
                    children = FavoriteSortDirection.entries.map { option ->
                        DropdownItem(
                            text = option.label,
                            selected = option == direction,
                            onClick = { onDirectionSelected(option) },
                        )
                    },
                ),
            )
        },
    )
    WindowIconCascadingDropdownMenu(entry = entry) {
        Icon(
            imageVector = MiuixIcons.Sort,
            contentDescription = "排序：${order.label} · ${direction.label}",
            tint = MiuixTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun AccountCollectionGrid(
    innerPadding: PaddingValues,
    state: AccountCollectionState.Content,
    liftedAlbumId: String?,
    onAlbumSelected: (HomeAlbum, Rect) -> Unit,
    onLoadMore: () -> Unit,
    updateChaptersOf: (String) -> Int,
) {
    val gridState = rememberLazyGridState()
    val footerVisible by remember(gridState) {
        derivedStateOf {
            gridState.layoutInfo.visibleItemsInfo.any { it.key == ACCOUNT_COLLECTION_FOOTER }
        }
    }
    var loadMoreArmed by remember { mutableStateOf(true) }
    LaunchedEffect(footerVisible) { if (!footerVisible) loadMoreArmed = true }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp,
            top = innerPadding.calculateTopPadding() + 12.dp,
            end = 12.dp,
            bottom = innerPadding.calculateBottomPadding() + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(state.albums, key = { it.id }) { album ->
            AlbumItem(
                album = album,
                coverLifted = album.id == liftedAlbumId,
                onSelected = onAlbumSelected,
                updateChapters = updateChaptersOf(album.id),
            )
        }
        item(key = ACCOUNT_COLLECTION_FOOTER, span = { GridItemSpan(maxLineSpan) }) {
            LaunchedEffect(
                state.nextPage,
                state.loadingMore,
                state.loadMoreError,
                state.endReached,
                footerVisible,
            ) {
                if (
                    footerVisible && loadMoreArmed && !state.loadingMore &&
                    state.loadMoreError == null && !state.endReached
                ) {
                    loadMoreArmed = false
                    onLoadMore()
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.loadingMore -> CircularProgressIndicator(size = 24.dp, strokeWidth = 3.dp)
                    state.loadMoreError != null -> TextButton(text = "加载失败，重试", onClick = onLoadMore)
                    state.endReached -> Text(
                        text = "已经到底了",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
private fun CollectionMessage(
    message: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (action != null) TextButton(text = action, onClick = onAction)
        }
    }
}

private sealed interface AccountCollectionState {
    data object Loading : AccountCollectionState
    data class Error(val message: String) : AccountCollectionState
    data class Content(
        val albums: List<HomeAlbum>,
        val total: Int?,
        val nextPage: Int,
        /** 服务端总页数，正序翻页要用它把逻辑页号映射回服务端页号；未知时为 null。 */
        val serverPageCount: Int? = null,
        val loadingMore: Boolean = false,
        val loadMoreError: String? = null,
        val endReached: Boolean = false,
    ) : AccountCollectionState
}

/** 一"逻辑页"，也就是按当前方向摆好之后要追加到列表尾部的那一段。 */
private data class FavoriteLogicalPage(
    val albums: List<HomeAlbum>,
    val total: Int?,
    val serverPageCount: Int?,
)

/**
 * 按方向取一页。
 *
 * 倒序（以及观看历史）就是服务端原始顺序，直接照搬。正序要把分页整体翻过来：
 * 逻辑第 1 页取服务端最后一页并反转页内顺序。第一次进正序时总页数还不知道，
 * 先探服务端第一页把 `total` 拿回来——刚好只有一页时这一页反转后就是答案，不用再发第二次请求。
 */
private suspend fun AccountDataRepository.loadFavoriteLogicalPage(
    kind: AccountCollectionKind,
    logicalPage: Int,
    favoriteOrder: FavoriteSortOrder,
    direction: FavoriteSortDirection,
    knownServerPageCount: Int?,
): JmxResult<FavoriteLogicalPage> {
    if (kind != AccountCollectionKind.FAVORITES || direction == FavoriteSortDirection.DESCENDING) {
        return when (val result = loadCollection(kind, logicalPage, favoriteOrder)) {
            is JmxResult.Success -> JmxResult.Success(
                FavoriteLogicalPage(
                    albums = result.value.albums,
                    total = result.value.total,
                    serverPageCount = favoriteServerPageCount(result.value.total),
                ),
            )
            is JmxResult.Failure -> result
        }
    }
    var pageCount = knownServerPageCount
    if (pageCount == null) {
        val probe = when (val result = loadCollection(kind, page = 1, favoriteOrder = favoriteOrder)) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        pageCount = favoriteServerPageCount(probe.total)
        if (pageCount == null || pageCount <= 1) {
            return JmxResult.Success(
                FavoriteLogicalPage(
                    albums = probe.albums.reversed(),
                    total = probe.total,
                    serverPageCount = pageCount ?: 1,
                ),
            )
        }
    }
    val serverPage = favoriteServerPage(logicalPage, direction, pageCount)
        ?: return JmxResult.Success(
            FavoriteLogicalPage(albums = emptyList(), total = null, serverPageCount = pageCount),
        )
    return when (val result = loadCollection(kind, serverPage, favoriteOrder)) {
        is JmxResult.Success -> JmxResult.Success(
            FavoriteLogicalPage(
                albums = result.value.albums.reversed(),
                total = result.value.total,
                serverPageCount = pageCount,
            ),
        )
        is JmxResult.Failure -> result
    }
}

private const val ACCOUNT_COLLECTION_FOOTER = "account-collection-footer"
