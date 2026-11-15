package org.kazusa.matter.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.zIndex
import com.kyant.backdrop.backdrops.LayerBackdrop
import kotlinx.coroutines.launch
import org.kazusa.matter.data.model.HotFeedCategory
import org.kazusa.matter.data.model.HotSearchChannel
import org.kazusa.matter.ui.effect.liquid.LegadoLiquidFloatingBottomBar
import org.kazusa.matter.ui.effect.liquid.LiquidGlassIcon
import org.kazusa.matter.ui.effect.liquid.LiquidGlassIconButton
import org.kazusa.matter.ui.effect.liquid.LiquidGlassMorphMenu
import org.kazusa.matter.ui.effect.liquid.LiquidNavItem
import org.kazusa.matter.ui.effect.liquid.legadoBarWidth
import org.kazusa.matter.ui.effect.liquid.morphMenuPanelHeight
import org.kazusa.matter.ui.effect.liquid.morphMenuPanelLift
import org.kazusa.matter.ui.effect.liquid.morphMenuPanelWidth
import org.kazusa.matter.ui.hotfeed.HotFeedUiState
import org.kazusa.matter.ui.media.morph.MORPH_SETTLE_SPEC
import org.kazusa.matter.ui.search.HotSearchBoardViewModel

/** 底栏行的声明高度：与胶囊的视觉高度对齐，用于内容避让与隐藏动画的距离。 */
internal val BAR_ROW_HEIGHT = 56.dp

/** 底栏与导航栏之间的留白。 */
internal val BAR_BOTTOM_PADDING = 12.dp

/** 悬浮胶囊与两侧悬浮按钮之间的间距。 */
private val BAR_BUTTON_GAP = 10.dp

/** 悬浮按钮直径。 */
private val BAR_BUTTON_SIZE = 56.dp

/** 悬浮按钮与屏幕边缘至少要留出的距离。 */
private val BAR_EDGE_MARGIN = 16.dp

/** 胶囊与两侧按钮同高 56dp，底部留白一致，自然同心对齐。 */
private val BAR_BUTTON_CENTER_INSET = 0.dp

/**
 * 悬浮底栏层（全屏 overlay）：胶囊 + 左右两颗悬浮按钮。
 *
 * - 左侧按钮在动态与热门之间切换。
 * - 右侧按钮在动态模式下打开发微博，在热门或热搜中打开各自的分类菜单。
 */
@Composable
internal fun MainBottomBarLayer(
    pagerState: PagerState,
    visible: Boolean,
    unreadCount: Int,
    backdrop: LayerBackdrop?,
    onReselect: (Int) -> Unit,
    showHotFeed: Boolean,
    hotFeedUiState: State<HotFeedUiState>,
    hotSearchViewModel: HotSearchBoardViewModel,
    onToggleTimelineMode: () -> Unit,
    onHotCategorySelected: (HotFeedCategory) -> Unit,
    onHotSearchChannelSelected: (HotSearchChannel) -> Unit,
    onComposeClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val navigationBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val selectedIndex = pagerState.currentPage
    val isTimelineActive = selectedIndex == MainTab.Timeline.ordinal
    val isHotSearchActive = selectedIndex == MainTab.HotSearch.ordinal
    val isHotFeedActive = isTimelineActive && showHotFeed

    // 只订阅「分类列表 + 当前选中项」，热门流每加载一页不会带着底栏一起重组
    val hotCategories by remember(hotFeedUiState) {
        derivedStateOf { hotFeedUiState.value.categories }
    }
    val hotSelectedContainerId by remember(hotFeedUiState) {
        derivedStateOf { hotFeedUiState.value.selectedCategory?.containerId }
    }

    val hotSearchUi by hotSearchViewModel.uiState.collectAsStateWithLifecycle()
    val categoryLabels: List<String> = if (isHotFeedActive) {
        hotCategories.map { it.title }
    } else {
        hotSearchUi.channels.map { it.name }
    }
    val selectedCategoryIndex: Int = if (isHotFeedActive) {
        hotCategories.indexOfFirst { it.containerId == hotSelectedContainerId }
    } else {
        hotSearchUi.channels.indexOfFirst { it.containerid == hotSearchUi.selectedContainerId }
    }

    val isCategoryActive = isHotFeedActive || isHotSearchActive
    val isComposeActive = isTimelineActive && !showHotFeed
    val sideButtonActive = isTimelineActive || isHotSearchActive

    // 分类菜单的展开状态只服务于这一层：返回键收起、切 Tab / 切模式收起
    var menuExpanded by remember { mutableStateOf(false) }
    BackHandler(enabled = menuExpanded) { menuExpanded = false }
    LaunchedEffect(selectedIndex, showHotFeed) { menuExpanded = false }

    // 下滑隐藏：整层一起位移淡出（距离含导航栏，保证完全移出屏幕）
    val barHeightPx = with(density) {
        (BAR_ROW_HEIGHT + BAR_BOTTOM_PADDING + navigationBarBottom).toPx()
    }
    val barTranslationY by animateFloatAsState(
        targetValue = if (visible) 0f else barHeightPx + 150f,
        animationSpec = spring(stiffness = 400f, dampingRatio = 0.85f),
        label = "BottomBarTranslation"
    )

    // 0 = 圆形按钮，1 = 分类面板；驱动同一块玻璃的形变（几何在绘制阶段求，不逐帧重组）
    val menuProgress = animateFloatAsState(
        targetValue = if (menuExpanded) 1f else 0f,
        animationSpec = MORPH_SETTLE_SPEC,
        label = "CategoryMenuMorph"
    )

    val navItems = remember(showHotFeed) {
        MainTab.entries.map { tab ->
            if (tab == MainTab.Timeline && showHotFeed) {
                LiquidNavItem(
                    title = "热门",
                    icon = Icons.Outlined.Explore,
                    selectedIcon = Icons.Filled.Explore
                )
            } else {
                LiquidNavItem(
                    title = tab.title,
                    icon = tab.unselectedIcon,
                    selectedIcon = tab.selectedIcon
                )
            }
        }
    }

    val positionFraction = remember(pagerState) {
        { pagerState.currentPage + pagerState.currentPageOffsetFraction }
    }

    // 底栏恒居中，两侧按钮必须自己找位置：
    // 屏幕半宽 − 胶囊半宽 − 间距 − 按钮直径 ≥ 16dp 边距，不够就把胶囊横向挤压（变窄）让位
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val fullCapsuleWidth = legadoBarWidth(navItems.size)
    val maxCapsuleHalf = screenWidthDp / 2 - BAR_BUTTON_GAP - BAR_BUTTON_SIZE - BAR_EDGE_MARGIN
    val capsuleScaleTarget = (maxCapsuleHalf.value / (fullCapsuleWidth.value / 2f)).coerceIn(0.7f, 1f)

    // 1 = 原始宽度（按钮收起）；<1 = 被按钮挤压
    // 阻尼设为 0.66，挤压与还原时伴随果冻般 Q 弹的有机形变
    val capsuleScale by animateFloatAsState(
        targetValue = if (sideButtonActive) capsuleScaleTarget else 1f,
        animationSpec = spring(stiffness = 300f, dampingRatio = 0.66f),
        label = "BottomBarCapsuleScale"
    )

    // 按钮停在胶囊侧缘外侧：胶囊半宽 + 间距 + 自身半径
    val buttonOffsetX = fullCapsuleWidth * capsuleScale / 2 + BAR_BUTTON_GAP + BAR_BUTTON_SIZE / 2

    // 1 = 已完全显示；0 = 隐去收起。采用果冻弹簧，保留过冲回弹带来鲜活 Q 弹质感
    val categoryReveal by animateFloatAsState(
        targetValue = if (isCategoryActive) 1f else 0f,
        animationSpec = if (isCategoryActive) {
            spring(stiffness = 280f, dampingRatio = 0.62f)
        } else {
            spring(stiffness = 340f, dampingRatio = 0.68f)
        },
        label = "CategoryButtonReveal"
    )

    val timelineToggleReveal by animateFloatAsState(
        targetValue = if (isTimelineActive) 1f else 0f,
        animationSpec = spring(stiffness = 280f, dampingRatio = 0.62f),
        label = "TimelineToggleReveal"
    )

    // 「发微博」与分类按钮共用右侧位置，切换时分别弹入和收回。
    val composeReveal by animateFloatAsState(
        targetValue = if (isComposeActive) 1f else 0f,
        animationSpec = if (isComposeActive) {
            spring(stiffness = 280f, dampingRatio = 0.62f)
        } else {
            spring(stiffness = 340f, dampingRatio = 0.68f)
        },
        label = "ComposeButtonReveal"
    )

    // 菜单玻璃的占位盒 = 面板 + 展开时的上移距离。占位盒的右下角对齐到按钮，
    // 于是按钮与展开后的面板都落在盒子内部，也就都落在这一层（全屏）的 bounds 里。
    val menuBoxWidth = morphMenuPanelWidth
    val menuBoxHeight = morphMenuPanelHeight(categoryLabels.size) + morphMenuPanelLift
    val menuTransformOrigin = remember(menuBoxWidth, menuBoxHeight) {
        TransformOrigin(
            pivotFractionX = (menuBoxWidth - BAR_BUTTON_SIZE / 2) / menuBoxWidth,
            pivotFractionY = (menuBoxHeight - BAR_BUTTON_SIZE / 2) / menuBoxHeight
        )
    }

    // 菜单展开时提权至 zIndex = 2f（在底栏与遮罩之上）；正常收回时 zIndex = 0f
    val menuElevated = isCategoryActive && (menuExpanded || menuProgress.value > 0.01f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = barTranslationY
                alpha = (1f - (barTranslationY / barHeightPx)).coerceIn(0f, 1f)
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        if (timelineToggleReveal > 0.01f) {
            // Layer 1：左侧「动态 / 热门」切换按钮 —— 图标显示的是「将要切到的模式」
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(
                        x = screenWidthDp / 2 - buttonOffsetX - BAR_BUTTON_SIZE / 2,
                        y = -(navigationBarBottom + BAR_BOTTOM_PADDING + BAR_BUTTON_CENTER_INSET)
                    )
                    .zIndex(if (menuElevated) 2f else 0f)
                    .graphicsLayer {
                        val p = timelineToggleReveal
                        alpha = p.coerceIn(0f, 1f)
                        val scale = (0.15f + 0.85f * p).coerceAtLeast(0f)
                        scaleX = scale
                        scaleY = scale
                        val offsetFactor = 1f - p
                        translationX = -offsetFactor * 14.dp.toPx()
                        translationY = offsetFactor * 18.dp.toPx()
                    }
            ) {
                LiquidGlassIconButton(
                    onClick = onToggleTimelineMode,
                    enabled = isTimelineActive,
                    backdrop = backdrop,
                    size = BAR_BUTTON_SIZE,
                    glassAlpha = 1f,
                    tintAlpha = 1f
                ) {
                    LiquidGlassIcon(
                        imageVector = if (showHotFeed) {
                            Icons.Filled.Home
                        } else {
                            Icons.Outlined.Explore
                        },
                        contentDescription = if (showHotFeed) "切换到动态" else "切换到热门",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        if (categoryReveal > 0.01f) {
            // Layer 2：右侧分类按钮 + 菜单（同一块液态玻璃的形变容器）
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    // 盒子右下角 = 按钮右下角：到屏幕右缘 / 底边的距离由胶囊位置推出。
                    // 用 offset 而不是 padding：胶囊缩放动画途中该值会短暂为负，padding 不接受负值。
                    .offset(
                        x = -(screenWidthDp / 2 - buttonOffsetX - BAR_BUTTON_SIZE / 2),
                        y = -(navigationBarBottom + BAR_BOTTOM_PADDING + BAR_BUTTON_CENTER_INSET)
                    )
                    .zIndex(if (menuElevated) 2f else if (isCategoryActive) 0.5f else 0f)
                    .graphicsLayer {
                        transformOrigin = menuTransformOrigin
                        val p = categoryReveal
                        alpha = p.coerceIn(0f, 1f)
                        // 保留弹性过冲，使弹出与缩放呈现真实饱满的 Q 弹果冻质感
                        val scale = (0.15f + 0.85f * p).coerceAtLeast(0f)
                        scaleX = scale
                        scaleY = scale
                        val offsetFactor = 1f - p
                        translationX = offsetFactor * 14.dp.toPx()
                        translationY = offsetFactor * 18.dp.toPx()
                    }
            ) {
                LiquidGlassMorphMenu(
                    items = categoryLabels,
                    selectedIndex = selectedCategoryIndex,
                    progress = menuProgress,
                    expanded = menuExpanded,
                    onToggle = { if (isCategoryActive) menuExpanded = !menuExpanded },
                    onItemClick = { index ->
                        menuExpanded = false
                        if (isHotFeedActive) {
                            hotCategories.getOrNull(index)?.let(onHotCategorySelected)
                        } else if (isHotSearchActive) {
                            hotSearchUi.channels.getOrNull(index)?.let(onHotSearchChannelSelected)
                        }
                    },
                    backdrop = backdrop,
                    emptyLabel = if (isHotFeedActive) "分类加载中…" else "榜单加载中…",
                    icon = {
                        LiquidGlassIcon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = if (isHotFeedActive) "切换热门分类" else "切换热搜榜单"
                        )
                    }
                )
            }
        }

        // Layer 2b：动态 Tab 的右侧「发微博」按钮 —— 与热门 Tab 的右侧按钮同位同尺寸
        if (composeReveal > 0.01f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    // 与分类按钮同一套定位：盒子右下角 = 按钮右下角
                    .offset(
                        x = -(screenWidthDp / 2 - buttonOffsetX - BAR_BUTTON_SIZE / 2),
                        y = -(navigationBarBottom + BAR_BOTTOM_PADDING + BAR_BUTTON_CENTER_INSET)
                    )
                    .zIndex(if (isComposeActive) 0.5f else 0f)
                    .graphicsLayer {
                        val p = composeReveal
                        alpha = p.coerceIn(0f, 1f)
                        val scale = (0.15f + 0.85f * p).coerceAtLeast(0f)
                        scaleX = scale
                        scaleY = scale
                        val offsetFactor = 1f - p
                        translationX = offsetFactor * 14.dp.toPx()
                        translationY = offsetFactor * 18.dp.toPx()
                    }
            ) {
                LiquidGlassIconButton(
                    onClick = onComposeClick,
                    enabled = isComposeActive,
                    backdrop = backdrop,
                    size = BAR_BUTTON_SIZE,
                    glassAlpha = 1f,
                    tintAlpha = 1f
                ) {
                    LiquidGlassIcon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "发微博"
                    )
                }
            }
        }

        // Layer 3：胶囊底栏本体（zIndex = 1f，在按钮滑入滑出时保持在上方）
        LegadoLiquidFloatingBottomBar(
            items = navItems,
            widthScale = capsuleScale,
            selectedIndex = selectedIndex,
            positionFraction = positionFraction,
            onItemSelected = { index ->
                coroutineScope.launch {
                    pagerState.animateScrollToPage(index)
                }
            },
            onItemReselected = onReselect,
            backdrop = backdrop,
            badge = { index ->
                if (index == MainTab.Timeline.ordinal && !showHotFeed && unreadCount > 0) {
                    {
                        Badge {
                            Text(
                                text = if (unreadCount > 99) "99+" else unreadCount.toString()
                            )
                        }
                    }
                } else {
                    null
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = BAR_BOTTOM_PADDING)
                .zIndex(1f)
        )

        // Layer 4：空白点击收起层（zIndex = 1.5f，在底栏之上、菜单之下）
        if (isCategoryActive && (menuExpanded || menuProgress.value > 0.001f)) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .zIndex(1.5f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { menuExpanded = false }
                    )
            )
        }
    }
}
