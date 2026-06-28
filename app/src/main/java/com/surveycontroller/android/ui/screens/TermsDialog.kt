package com.surveycontroller.android.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 服务条款与隐私声明对话框。1:1 对齐桌面端 TermsOfServiceDialog，
 * 文本内容来自 assets/legal/terms_of_service.txt。
 */
@Composable
fun TermsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text by produceState(initialValue = "正在加载…") {
        value = runCatching {
            context.assets.open("legal/terms_of_service.txt").bufferedReader().use { it.readText() }
        }.getOrDefault("条款文本加载失败")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("服务条款与隐私声明") },
        text = {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
