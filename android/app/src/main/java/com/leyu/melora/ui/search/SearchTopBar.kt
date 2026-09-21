package com.leyu.melora.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leyu.melora.ui.common.TextMain
import com.leyu.melora.ui.common.TextTabItem
import com.leyu.melora.ui.common.titleScrollToTop

internal val SearchTopBarHeight = 64.dp

// 精致顶栏：左侧 ☰ + 大标题"搜索"，右侧与标题同基准线的轻量文本 Tab（钴蓝高亮 + 底部微弧指示条）
@Composable
fun SearchTopBar(
    onOpenDrawer: () -> Unit,
    selectedCategory: SearchCategory,
    onCategoryChange: (SearchCategory) -> Unit,
    onTitleClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SearchTopBarHeight)
            .padding(start = 4.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenDrawer) {
            Icon(Icons.Rounded.Menu, contentDescription = "打开侧栏", tint = TextMain)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 4.dp)
                .titleScrollToTop(onTitleClick),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = "搜索",
                fontSize = 19.sp,
                fontWeight = FontWeight.Medium,
                color = TextMain,
            )
        }
        SearchCategory.values().forEach { category ->
            TextTabItem(
                label = category.label,
                selected = selectedCategory == category,
                onClick = { onCategoryChange(category) },
                modifier = Modifier
                    .padding(start = 12.dp, top = 16.dp)
                    .alignByBaseline(),
            )
        }
    }
}
