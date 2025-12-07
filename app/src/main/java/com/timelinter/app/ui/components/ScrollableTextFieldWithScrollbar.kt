package com.timelinter.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

@Composable
fun ScrollableTextFieldWithScrollbar(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Notes"
) {
    val scrollState = rememberScrollState()
    var viewportPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    Box(
        modifier
            .onSizeChanged { viewportPx = it.height } // height of visible area
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState), // important: make the text scrollable
            maxLines = Int.MAX_VALUE,
        )

        // Draw a thumb if overflowed
        if (scrollState.maxValue > 0 && viewportPx > 0) {
            val viewportF = viewportPx.toFloat()
            val contentF = viewportF + scrollState.maxValue.toFloat()

            val proportion = (viewportF / contentF).coerceIn(0f, 1f)
            val thumbHeightPx = (viewportF * proportion)
                .coerceAtLeast(with(density) { 24.dp.toPx() })

            val trackRange = viewportF - thumbHeightPx
            val progress = scrollState.value.toFloat() / scrollState.maxValue.toFloat()
            val thumbOffsetPx = (trackRange * progress)

            Box(
                Modifier
                    .fillMaxHeight()
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp) // leave room outside the border
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .offset { IntOffset(0, thumbOffsetPx.toInt()) }
                        .height(with(density) { thumbHeightPx.toDp() })
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                )
            }
        }
    }
}


