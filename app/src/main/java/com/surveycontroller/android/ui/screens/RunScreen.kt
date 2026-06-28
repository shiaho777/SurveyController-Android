package com.surveycontroller.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import com.surveycontroller.android.core.engine.RunStatus
import com.surveycontroller.android.core.engine.SlotStatus
import com.surveycontroller.android.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val progress by viewModel.runProgress.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("运行监控") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { OverviewCard(progress) }
            item { ControlRow(progress.status, viewModel) }
            if (progress.failureReasons.isNotEmpty()) item { FailureBreakdownCard(progress.failureReasons) }
            if (progress.slotStatuses.isNotEmpty()) {
                item { Text("并发槽", style = MaterialTheme.typography.titleSmall) }
                items(progress.slotStatuses.values.sortedBy { it.name }) { SlotCard(it) }
            }
            item { Text("运行日志", style = MaterialTheme.typography.titleSmall) }
            item { LogCard(progress.recentLogs) }
        }
    }
}

@Composable
private fun OverviewCard(p: com.surveycontroller.android.core.engine.RunProgress) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SuccessRing(success = p.success, fail = p.fail, target = p.target)
            Spacer(Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(statusLabel(p.status), style = MaterialTheme.typography.titleMedium)
                Text(p.message, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Stat("成功", p.success.toString())
                    Stat("失败", p.fail.toString())
                    Stat("目标", p.target.toString())
                }
                Spacer(Modifier.height(6.dp))
                val elapsedSec = p.elapsedMs / 1000
                val rate = if (elapsedSec > 0) "%.1f".format(p.success * 60.0 / elapsedSec) else "—"
                Text("用时 ${formatDuration(p.elapsedMs)} · 约 $rate 份/分", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** 成功率环形图：成功(绿)/失败(红)/剩余(灰)。 */
@Composable
private fun SuccessRing(success: Int, fail: Int, target: Int) {
    val total = maxOf(target, success + fail, 1)
    val successColor = Color(0xFF2E9E5B)
    val failColor = MaterialTheme.colorScheme.error
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 14f)
            val sweepSuccess = 360f * success / total
            val sweepFail = 360f * fail / total
            drawArc(trackColor, -90f, 360f, false, style = stroke)
            drawArc(successColor, -90f, sweepSuccess, false, style = stroke)
            drawArc(failColor, -90f + sweepSuccess, sweepFail, false, style = stroke)
        }
        val pct = if (target > 0) (success * 100 / target) else 0
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$pct%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("完成", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ControlRow(status: RunStatus, viewModel: MainViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val running = status == RunStatus.RUNNING
        val paused = status == RunStatus.PAUSED
        if (running) OutlinedButton(onClick = viewModel::pauseRun, modifier = Modifier.weight(1f)) { Text("暂停") }
        if (paused) OutlinedButton(onClick = viewModel::resumeRun, modifier = Modifier.weight(1f)) { Text("继续") }
        if (running || paused) Button(onClick = viewModel::stopRun, modifier = Modifier.weight(1f)) { Text("停止") }
    }
}

@Composable
private fun FailureBreakdownCard(reasons: Map<String, Int>) {
    val total = reasons.values.sum().coerceAtLeast(1)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("失败原因", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            reasons.entries.sortedByDescending { it.value }.forEach { (reason, count) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(reason, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.32f))
                    Box(
                        Modifier.weight(0.5f).height(8.dp).clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            Modifier.fillMaxWidth(count.toFloat() / total).height(8.dp)
                                .background(MaterialTheme.colorScheme.error),
                        )
                    }
                    Text("$count", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.12f).padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun SlotCard(slot: SlotStatus) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(10.dp).clip(RoundedCornerShape(5.dp))
                    .background(if (slot.running) Color(0xFF2E9E5B) else MaterialTheme.colorScheme.outline),
            )
            Spacer(Modifier.size(10.dp))
            Text(slot.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.3f))
            Text(slot.step, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.4f))
            Text("✓${slot.success} ✗${slot.fail}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(0.3f))
        }
    }
}

@Composable
private fun LogCard(logs: List<String>) {
    Card(Modifier.fillMaxWidth().height(260.dp)) {
        LazyColumn(Modifier.fillMaxSize().padding(12.dp), reverseLayout = true) {
            items(logs.asReversed()) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val m = s / 60
    val sec = s % 60
    return if (m > 0) "${m}分${sec}秒" else "${sec}秒"
}

private fun statusLabel(status: RunStatus): String = when (status) {
    RunStatus.IDLE -> "空闲"
    RunStatus.RUNNING -> "运行中"
    RunStatus.PAUSED -> "已暂停"
    RunStatus.STOPPING -> "停止中"
    RunStatus.FINISHED -> "已完成"
    RunStatus.FAILED -> "已停止"
}
