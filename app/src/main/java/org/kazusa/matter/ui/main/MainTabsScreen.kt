package org.kazusa.matter.ui.main

import org.kazusa.matter.data.model.VideoStreamInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material3.Scaffold
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.kazusa.matter.ui.components.LocalCardTextSelectionEnabled
import org.kazusa.matter.ui.effect.rememberBlurEffectsEnabled
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.data.repository.AuthRepository
import org.kazusa.matter.data.repository.PublishRepository
import org.kazusa.matter.ui.theme.ThemePreferences
import org.kazusa.matter.data.model.HotSearchChannel
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.ui.group.GroupViewModel
import org.kazusa.matter.ui.hotfeed.HotFeedScreen
import org.kazusa.matter.ui.hotfeed.HotFeedViewModel
import org.kazusa.matter.ui.media.morph.LocalImageMorphState
import org.kazusa.matter.ui.media.morph.rememberImageMorphPresent
import org.kazusa.matter.ui.navigation.LocalSharedTransitionSourceActive
import org.kazusa.matter.ui.profile.ProfileScreen
import org.kazusa.matter.ui.search.HotSearchBoardViewModel
import org.kazusa.matter.ui.search.HotSearchScreen
import org.kazusa.matter.ui.search.currentLatLon
import org.kazusa.matter.ui.search.hasCoarseLocationPermission
import org.kazusa.matter.ui.timeline.TimelineScreen
import org.kazusa.matter.ui.timeline.TimelineViewModel

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 服务端「同城」榜的标识（`filter_type=region`）。
 *
 * 只有这个榜必须带 `lat` / `lon` 才返回真实城市榜，因此用它判断要不要先申请定位权限；
 * 这是容器参数层面的判断，不涉及任何榜单文案的硬编码。
 */
private const val REGION_FILTER = "filter_type=region"

internal enum class MainTab(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    Timeline("动态", Icons.Filled.Home, Icons.Outlined.Home),
    HotSearch("热搜", Icons.Filled.LocalFireDepartment, Icons.Outlined.LocalFireDepartment),
    Profile("我的", Icons.Filled.Person, Icons.Outlined.Person)
}

/**
 * 主框架：三个 Tab 的横向分页 + 内容区之上的悬浮底栏层。
 *
 * `Scaffold` 只用来兜底背景色 / insets，**不再使用 `bottomBar` 槽**：槽的高度是写死的，
 * 而展开后的榜单菜单必须从按钮位置上长出去，靠 `requiredSize` 硬撑出槽外的节点虽然画得出来，
 * 却收不到触摸事件。底栏改用 [MainBottomBarLayer] 作为内容区的全屏 overlay 呈现，
 * 内容避让（[bottomBarPadding]）于是改为按同一份几何自行计算。
 */
@Composable
fun MainTabsScreen(
    onNavigateToGroups: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToHistory: () -> Unit = {},
    onNavigateToSavedPosts: () -> Unit = {},
    onNavigateToMyPosts: () -> Unit = {},
    onNavigateToFollowing: () -> Unit = {},
    onNavigateToFollowers: () -> Unit = {},
    onNavigateToMessages: () -> Unit = {},
    /** 打开发微博编辑器（动态 Tab 底栏右侧按钮） */
    onNavigateToComposer: () -> Unit = {},
    onUserClick: (Long?) -> Unit = {},
    onPostClick: (WeiboStatusDto) -> Unit,
    onCommentClick: (WeiboStatusDto) -> Unit = onPostClick,
    onImageClick: (urls: List<String>, index: Int) -> Unit,
    onVideoClick: (url: String, title: String, streams: List<VideoStreamInfo>, coverUrl: String, mid: String) -> Unit,
    onMentionClick: (String) -> Unit = {},
    onTopicClick: (String) -> Unit = {},
    onSearchSubmit: (String) -> Unit = {},
    /** 打开搜索展示页（空关键词进入，展示搜索记录） */
    onOpenSearch: () -> Unit = {},
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    authPreferences: AuthPreferences = koinInject(),
    authRepository: AuthRepository = koinInject(),
    themePreferences: ThemePreferences = koinInject(),
    timelineViewModel: TimelineViewModel = koinViewModel(),
    groupViewModel: GroupViewModel = koinViewModel(),
    hotFeedViewModel: HotFeedViewModel = koinViewModel(),
    // 热搜榜的状态由 ViewModel 持有：榜单切换由悬浮底栏右侧的分类菜单触发，与热搜列表共用同一份
    hotSearchViewModel: HotSearchBoardViewModel = koinViewModel()
) {
    val showHotFeed = themePreferences.homeShowsHotFeed
        .collectAsStateWithLifecycle(initialValue = null).value ?: return
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { MainTab.entries.size })
    val coroutineScope = rememberCoroutineScope()

    // 跨导航与切 Tab 记忆主页瀑布流的精准滚动偏移行数与位置
    val timelineListState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }

    val hotFeedListState = remember { mutableStateOf<LazyListState?>(null) }
    val hotSearchListState = remember { mutableStateOf<LazyListState?>(null) }

    // 只把 State 往下传：分类菜单在悬浮层里读取，热门流每加载一页不会带着主框架重组
    val hotFeedState = hotFeedViewModel.uiState.collectAsStateWithLifecycle()

    // 「同城」榜要经纬度才有数据（无定位时服务端只回一条「位置信息获取失败」），
    // 因此只在用户主动切到该榜时才申请 COARSE_LOCATION；授权后再取一次位置并切榜。
    val context = LocalContext.current
    var pendingRegionBoard by remember { mutableStateOf<HotSearchChannel?>(null) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val channel = pendingRegionBoard
        pendingRegionBoard = null
        if (channel != null) {
            coroutineScope.launch {
                hotSearchViewModel.select(
                    channel,
                    latLon = if (granted) currentLatLon(context) else null
                )
            }
        }
    }

    // 热搜榜单切换：同城榜需要位置，没授权就先申请，回调里再切
    val selectHotSearchChannel: (HotSearchChannel) -> Unit = { channel ->
        if (channel.containerid.contains(REGION_FILTER) &&
            !hasCoarseLocationPermission(context)
        ) {
            pendingRegionBoard = channel
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        } else {
            coroutineScope.launch {
                hotSearchViewModel.select(channel, latLon = currentLatLon(context))
            }
        }
    }

    // 主框架只关心少量时间线字段；订阅整个 uiState 会导致列表刷新/滚动时整屏重组，
    // 因此用 derivedStateOf 收窄到真正使用的字段。
    val timelineState by timelineViewModel.uiState.collectAsStateWithLifecycle()
    val pendingRestoreIndex by remember { derivedStateOf { timelineState.pendingRestoreIndex } }
    val currentGid by remember { derivedStateOf { timelineState.currentGid } }
    val unreadCount by remember { derivedStateOf { timelineState.unreadCount } }

    // 大图在场期间禁止横向切页：收起飞行阶段垂直滚动是放开的（见 ImageMorphState.allowsParentScroll），
    // 但横向滑动此时只会把页面横移走，属于纯误触。
    val morphState = LocalImageMorphState.current
    val morphPresent by rememberImageMorphPresent(morphState)

    // 只订阅「顶栏随滑动收起」这一个开关：整个 AppThemeState 的变化（换调色板、改种子色等）
    // 不该带着主框架和三个分页一起重组。
    val topBarAutoHide by remember(themePreferences) {
        themePreferences.themeStateFlow
            .map { it.topBarAutoHide }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    // 发布成功后回到动态页并刷新：新微博在时间线顶部，用户能立刻看到自己的内容。
    // 编辑器盖在本页之上时本页已经离开组合，所以按仓库的只增计数补处理（见 PublishRepository）。
    val publishRepository: PublishRepository = koinInject()
    val publishedCount by publishRepository.publishedCount.collectAsStateWithLifecycle()
    var handledPublishCount by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(publishedCount) {
        if (publishedCount > handledPublishCount) {
            handledPublishCount = publishedCount
            coroutineScope.launch { pagerState.animateScrollToPage(MainTab.Timeline.ordinal) }
            timelineViewModel.loadTimeline(isRefresh = true)
        }
    }

    // 检测瀑布流是否在向下滚动（向下滚时隐藏底栏以充分利用屏幕，向上滚或在顶部时展示底栏）
    var isBottomBarVisible by remember { mutableStateOf(true) }
    // 顶栏跟随同一份判定（同一累积位移、同一阈值），因此两者始终同进同退；
    // 是否真的收起还要看「滑动时隐藏顶栏」开关（topBarAutoHide）。
    var isTopBarVisible by remember { mutableStateOf(true) }
    val scrollThreshold = with(LocalDensity.current) { 32.dp.toPx() }
    val scrollTracker = remember(scrollThreshold) { BottomBarScrollTracker(scrollThreshold) }
    val currentPendingRestore by rememberUpdatedState(pendingRestoreIndex)
    val currentShowHotFeed by rememberUpdatedState(showHotFeed)
    val bottomBarScrollConnection = remember(pagerState, timelineListState, scrollTracker) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    // 三个列表走同一套规则：能拿到当前列表状态就顺带判断「是否在顶部」，
                    // 到顶立即显示底栏；否则按滚动增量（32dp 阈值）决定显示 / 隐藏
                    val activeListState = when (MainTab.entries[pagerState.currentPage]) {
                        MainTab.Timeline -> when {
                            currentShowHotFeed -> hotFeedListState.value
                            currentPendingRestore == null -> timelineListState
                            else -> null
                        }
                        MainTab.HotSearch -> hotSearchListState.value
                        MainTab.Profile -> null
                    }
                    if (activeListState != null) {
                        if (activeListState.firstVisibleItemIndex == 0 &&
                            activeListState.firstVisibleItemScrollOffset == 0
                        ) {
                            isBottomBarVisible = true
                            isTopBarVisible = true
                            scrollTracker.reset()
                        } else {
                            scrollTracker.onScroll(consumed.y)?.let { visible ->
                                isBottomBarVisible = visible
                                isTopBarVisible = visible
                            }
                        }
                    }
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(pagerState.currentPage, currentGid, showHotFeed) {
        isBottomBarVisible = true
        isTopBarVisible = true
        scrollTracker.reset()
    }

    // Backdrop 分层架构（两处录制是结构决定的，不是疏忽）：
    //
    // 1. bottomBarBackdrop 挂在 HorizontalPager 上，供悬浮底栏与「发微博」按钮采样。
    //    它必须是「横向滚动之上的那一层」：底栏要跟着实时滑动的内容走，
    //    若改用某一页自己的那层，横划时屏幕上滑的是两页内容、而样本是静止的一页，
    //    底栏模糊就会「黏住不动」。
    // 2. 各页面再各自挂一层，录「顶栏以下的内容区」，供本页顶栏玻璃采样。
    //    顶栏本身在 Pager 的录制范围里，如果它去采样 Pager 那层就是在采自己（自反馈）。
    val runtimeShaderSupported = rememberBlurEffectsEnabled()
    val bottomBarBackdrop = if (runtimeShaderSupported) rememberLayerBackdrop() else null
    val timelineBackdrop = if (runtimeShaderSupported) rememberLayerBackdrop() else null
    val searchBackdrop = if (runtimeShaderSupported) rememberLayerBackdrop() else null
    val profileBackdrop = if (runtimeShaderSupported) rememberLayerBackdrop() else null

    // 内容避让：底栏不在 Scaffold 的槽里，这里按悬浮层的同一份几何自行计算
    val navigationBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomBarPadding = BAR_ROW_HEIGHT + BAR_BOTTOM_PADDING + navigationBarBottom

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier.fillMaxSize()
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                userScrollEnabled = !morphPresent,
                key = { it },
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(bottomBarScrollConnection)
                    .then(if (bottomBarBackdrop != null) Modifier.layerBackdrop(bottomBarBackdrop) else Modifier)
            ) { page ->
                val isPageVisible = pagerState.currentPage == page
                CompositionLocalProvider(
                    LocalSharedTransitionSourceActive provides isPageVisible,
                    // 信息流分页统一关掉「逐卡文本选区」：SelectionContainer 会为每张卡片
                    // 建一份选区注册器 + 手势节点，一屏十几张是纯开销。
                    // 正文页不受影响（正文与转发原文仍可长按选词复制）。
                    LocalCardTextSelectionEnabled provides false
                ) {
                    when (MainTab.entries[page]) {
                        MainTab.Timeline -> AnimatedContent(
                            targetState = showHotFeed,
                            transitionSpec = {
                                fadeIn(animationSpec = tween(240)) togetherWith
                                    fadeOut(animationSpec = tween(160))
                            },
                            label = "TimelineMode",
                            modifier = Modifier.fillMaxSize()
                        ) { hotFeed ->
                            val isCurrentMode = hotFeed == showHotFeed
                            // 淡出中的列表不再注册共享元素，避免同一条微博出现两个形变来源。
                            CompositionLocalProvider(
                                LocalSharedTransitionSourceActive provides (isPageVisible && isCurrentMode)
                            ) {
                                if (hotFeed) {
                                    HotFeedScreen(
                                        viewModel = hotFeedViewModel,
                                        onUserClick = onUserClick,
                                        onPostClick = onPostClick,
                                        onCommentClick = onCommentClick,
                                        onImageClick = onImageClick,
                                        onVideoClick = onVideoClick,
                                        onMentionClick = onMentionClick,
                                        onTopicClick = onTopicClick,
                                        backdrop = timelineBackdrop.takeIf { isCurrentMode },
                                        bottomBarPadding = bottomBarPadding,
                                        topBarHidden = topBarAutoHide && !isTopBarVisible,
                                        isActive = isCurrentMode,
                                        isPageVisible = isPageVisible && isCurrentMode,
                                        onActiveListStateChanged = { state ->
                                            if (hotFeedListState.value !== state) {
                                                hotFeedListState.value = state
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    TimelineScreen(
                                        viewModel = timelineViewModel,
                                        groupViewModel = groupViewModel,
                                        onUserClick = onUserClick,
                                        onPostClick = onPostClick,
                                        onCommentClick = onCommentClick,
                                        onImageClick = onImageClick,
                                        onVideoClick = onVideoClick,
                                        onMentionClick = onMentionClick,
                                        onTopicClick = onTopicClick,
                                        onSearchClick = onOpenSearch,
                                        onLogout = onLogout,
                                        onNavigateToGroups = onNavigateToGroups,
                                        listState = timelineListState,
                                        backdrop = timelineBackdrop.takeIf { isCurrentMode },
                                        bottomBarPadding = bottomBarPadding,
                                        topBarHidden = topBarAutoHide && !isTopBarVisible,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                        MainTab.HotSearch -> HotSearchScreen(
                            hotSearchViewModel = hotSearchViewModel,
                            onSearchSubmit = onSearchSubmit,
                            backdrop = searchBackdrop,
                            bottomBarPadding = bottomBarPadding,
                            topBarHidden = topBarAutoHide && !isTopBarVisible,
                            isActive = isPageVisible,
                            onActiveListStateChanged = { hotSearchListState.value = it },
                            modifier = Modifier.fillMaxSize()
                        )
                        MainTab.Profile -> {
                            ProfileScreen(
                                authPreferences = authPreferences,
                                authRepository = authRepository,
                                onNavigateToSettings = onNavigateToSettings,
                                onNavigateToHistory = onNavigateToHistory,
                                onNavigateToSavedPosts = onNavigateToSavedPosts,
                                onNavigateToMyPosts = onNavigateToMyPosts,
                                onNavigateToFollowing = onNavigateToFollowing,
                                onNavigateToFollowers = onNavigateToFollowers,
                                onNavigateToMessages = onNavigateToMessages,
                                onLogout = onLogout,
                                backdrop = profileBackdrop,
                                bottomBarPadding = bottomBarPadding,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }

            // 悬浮底栏叠在分页内容上，左右按钮随当前页面切换。
            MainBottomBarLayer(
                pagerState = pagerState,
                visible = isBottomBarVisible,
                unreadCount = unreadCount,
                backdrop = bottomBarBackdrop,
                showHotFeed = showHotFeed,
                hotFeedUiState = hotFeedState,
                hotSearchViewModel = hotSearchViewModel,
                onToggleTimelineMode = { coroutineScope.launch { themePreferences.toggleHomeFeed() } },
                onHotCategorySelected = { category ->
                    hotFeedViewModel.selectCategory(category)
                },
                onHotSearchChannelSelected = selectHotSearchChannel,
                onComposeClick = onNavigateToComposer,
                onReselect = { index ->
                    when (MainTab.entries[index]) {
                        MainTab.Timeline -> if (showHotFeed) {
                            hotFeedViewModel.refresh()
                        } else coroutineScope.launch {
                            val isAtTop = timelineListState.firstVisibleItemIndex == 0 &&
                                timelineListState.firstVisibleItemScrollOffset < 10
                            if (isAtTop) {
                                // 处于顶部时，再次点击触发刷新
                                timelineViewModel.loadTimeline(isRefresh = true)
                            } else {
                                // 处于滑动浏览中时，点击一次平滑回到顶部（不刷新）
                                if (timelineListState.firstVisibleItemIndex > 12) {
                                    timelineListState.scrollToItem(10)
                                }
                                timelineListState.animateScrollToItem(0)
                            }
                        }
                        MainTab.HotSearch -> hotSearchViewModel.refresh()
                        MainTab.Profile -> Unit
                    }
                }
            )

        }
    }
}

