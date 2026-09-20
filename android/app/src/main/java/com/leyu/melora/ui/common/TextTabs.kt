package com.leyu.melora.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 轻量级"呼吸感"文本 Tab：无底槽、无涟漪
// 选中 = 钴蓝文字 + 底部微弧指示条；未选中 = 静谧灰。可带小号计数（存活才显示）。
@Composable
fun TextTabItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
) {
    val active = BrandBlue
    val textColor by animateColorAsState(
        targetValue = if (selected) active else TextSub,
        animationSpec = tween(durationMillis = 200),
        label = "textTabColor",
    )
    val countColor by animateColorAsState(
        targetValue = if (selected) active.copy(alpha = 0.72f) else TextMuted,
        animationSpec = tween(durationMillis = 200),
        label = "textTabCount",
    )
    val indicatorAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "textTabIndicator",
    )
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onClick() }
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = countedTabLabel(label, count, countColor),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = textColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .size(width = 16.dp, height = 3.dp)
                .alpha(indicatorAlpha)
                .clip(RoundedCornerShape(percent = 50))
                .background(active),
        )
    }
}

/** 分类按内容宽度排布；窄屏/大字体/长计数时横向滚动，禁止把数字挤到第二行。 */
@Composable
fun TextTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    counts: List<Int>? = null,
) {
    val requesters = remember(labels.size) { List(labels.size) { BringIntoViewRequester() } }
    LaunchedEffect(selected, labels, counts) {
        // 初始选中末项或计数异步变化后，等布局完成再确保选中分类可见。
        withFrameNanos { }
        requesters.getOrNull(selected)?.bringIntoView()
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        labels.forEachIndexed { index, label ->
            TextTabItem(
                label = label,
                selected = selected == index,
                count = counts?.getOrNull(index),
                onClick = { onSelect(index) },
                modifier = Modifier.bringIntoViewRequester(requesters[index]),
            )
        }
    }
}

/** 同一个Text中的不同字号共享基线与单行约束，保留完整计数而不是按位截断。 */
internal fun countedTabLabel(label: String, count: Int?, countColor: Color) = buildAnnotatedString {
    append(label)
    if (count != null && count > 0) {
        withStyle(SpanStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = countColor)) {
            append(" $count")
        }
    }
}
