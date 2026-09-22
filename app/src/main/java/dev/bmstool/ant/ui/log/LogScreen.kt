package dev.bmstool.ant.ui.log

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bmstool.ant.AppViewModel
import dev.bmstool.ant.ble.LogLine
import dev.bmstool.ant.ui.theme.Amber
import dev.bmstool.ant.ui.theme.Blue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The raw traffic log: '>' sent, '<' received, '!' event. Newest last, auto-scrolled. */
@Composable
fun LogScreen(vm: AppViewModel, modifier: Modifier) {
    val log by vm.log.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val formatter = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${log.size} lines · > sent · < received · ! event",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    val text = log.joinToString("\n") { format(formatter, it) }
                    clipboard.setText(AnnotatedString(text))
                    vm.say("Log copied (${log.size} lines)")
                },
                enabled = log.isNotEmpty(),
            ) { Text("Copy") }
        }
        HorizontalDivider()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            if (log.isEmpty()) {
                item {
                    Text(
                        text = "No traffic yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(log) { line ->
                Text(
                    text = format(formatter, line),
                    color = kindColor(line.kind),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun format(formatter: SimpleDateFormat, line: LogLine): String =
    "${formatter.format(Date(line.time))} ${line.kind} ${line.text}"

@Composable
private fun kindColor(kind: Char): Color = when (kind) {
    '>' -> Blue
    '!' -> Amber
    else -> MaterialTheme.colorScheme.onSurface
}
