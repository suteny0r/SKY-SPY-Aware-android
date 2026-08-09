package com.suteny0r.skyspyaware.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private val DetectionColor = Color(0xFF00E5FF)
private val OtherColor = Color(0xFF9E9E9E)
private val EvenRow = Color(0xFF0A0A0A)
private val OddRow = Color(0xFF00141A)

@Composable
fun ConsoleScreen(lines: List<String>) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(lines) { i, line ->
            val isDetection = line.startsWith("{\"mac\"")
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (isDetection) DetectionColor else OtherColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (i % 2 == 0) EvenRow else OddRow)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}
