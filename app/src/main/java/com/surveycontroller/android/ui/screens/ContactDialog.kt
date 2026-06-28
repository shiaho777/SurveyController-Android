package com.surveycontroller.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.surveycontroller.android.core.backend.ContactMessageType
import com.surveycontroller.android.ui.MainViewModel

/**
 * 联系开发者对话框。1:1 对齐桌面端 ContactForm 核心字段：消息类型 / 标题 / 邮箱 / 正文。
 */
@Composable
fun ContactDialog(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val state by viewModel.contact.collectAsState()
    var type by remember { mutableStateOf(ContactMessageType.BUG_REPORT) }
    var title by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!state.sending) { viewModel.clearContactState(); onDismiss() } },
        title = { Text("联系开发者") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("反馈类型", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ContactMessageType.entries.forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) })
                    }
                }
                if (type == ContactMessageType.BUG_REPORT) {
                    OutlinedTextField(title, { title = it }, label = { Text("问题标题（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(email, { email = it }, label = { Text("联系邮箱（可选，便于回复）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    message, { message = it },
                    label = { Text("消息内容") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                state.result?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (state.sending) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else if (state.success) {
                TextButton(onClick = { viewModel.clearContactState(); onDismiss() }) { Text("完成") }
            } else {
                TextButton(onClick = { viewModel.submitContact(type, message, email, title) }) { Text("发送") }
            }
        },
        dismissButton = {
            if (!state.sending && !state.success) {
                TextButton(onClick = { viewModel.clearContactState(); onDismiss() }) { Text("取消") }
            }
        },
    )
}
