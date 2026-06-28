package com.surveycontroller.android.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.surveycontroller.android.R
import com.surveycontroller.android.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onConfigure: () -> Unit,
    onScan: () -> Unit,
) {
    val state by viewModel.workbench.collectAsState()
    val recent by viewModel.recentSurveys.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painter = painterResource(R.drawable.app_logo), contentDescription = null, modifier = Modifier.size(44.dp))
            Spacer(Modifier.size(12.dp))
            Column {
                Text("SurveyController", style = MaterialTheme.typography.titleLarge)
                Text("一站式问卷自动化 · 问卷星/腾讯问卷/见数", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = state.url,
            onValueChange = viewModel::setUrl,
            label = { Text("问卷链接") },
            placeholder = { Text("粘贴问卷链接，或扫描二维码") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            maxLines = 3,
        )
        // 链接即时校验提示
        val trimmed = state.url.trim()
        if (trimmed.isNotEmpty()) {
            val supported = viewModel.supportedUrl(trimmed)
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (supported) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(6.dp))
                    Text("识别为 ${platformLabel(detectPlatform(trimmed))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                } else {
                    Text("链接格式暂不识别，请确认是问卷星/腾讯问卷/见数的链接", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onScan) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("扫码")
            }
            Button(
                onClick = viewModel::parse,
                enabled = !state.parsing && state.url.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.parsing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("解析中…")
                } else {
                    Text("自动配置问卷")
                }
            }
        }

        state.error?.let {
            Spacer(Modifier.height(12.dp))
            Card { Text(it, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
        }

        state.draft?.let { draft ->
            Spacer(Modifier.height(20.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(draft.definition.title.ifEmpty { "未命名问卷" }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Text("平台：${platformLabel(draft.definition.provider.id)}", style = MaterialTheme.typography.bodyMedium)
                    Text("可作答题目：${draft.questions.size} 题", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onConfigure, modifier = Modifier.fillMaxWidth()) { Text("调整配置并运行") }
                }
            }
        }

        // 最近问卷
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("最近问卷", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = viewModel::clearHistory) { Text("清空") }
            }
            recent.forEach { r ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { viewModel.useRecent(r.url) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(r.title.ifEmpty { r.url }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${platformLabel(r.provider)} · ${r.questionCount} 题 · ${formatTime(r.timestamp)}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("使用提示", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text("仅供 HTTP 接口自动化学习与测试使用。请确保拥有目标问卷授权，严禁污染他人问卷数据。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun detectPlatform(url: String): String =
    com.surveycontroller.android.provider.SurveyProviderType.detect(url).id

private fun formatTime(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

private fun platformLabel(id: String): String = when (id) {
    "wjx" -> "问卷星"
    "qq" -> "腾讯问卷"
    "credamo" -> "Credamo 见数"
    else -> id
}
