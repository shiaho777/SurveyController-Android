package com.surveycontroller.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 数值步进输入：− [可直接键入的数字框] +。
 * 用于份数 / 并发 / 时长等需要精确取值的场景，取代大范围难拖准的滑块。
 */
@Composable
fun Stepper(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    step: Int = 1,
    suffix: String = "",
    helper: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            helper?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalIconButton(
                onClick = { onValueChange((value - step).coerceIn(range.first, range.last)) },
                enabled = value > range.first,
                modifier = Modifier.size(38.dp),
            ) { Icon(Icons.Filled.Remove, contentDescription = "减少") }

            OutlinedTextField(
                value = value.toString(),
                onValueChange = { s ->
                    val v = s.filter { it.isDigit() }.take(6).toIntOrNull()
                    if (v != null) onValueChange(v.coerceIn(range.first, range.last))
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
                suffix = if (suffix.isNotEmpty()) ({ Text(suffix, style = MaterialTheme.typography.labelMedium) }) else null,
                modifier = Modifier.width(if (suffix.isNotEmpty()) 104.dp else 84.dp),
            )

            FilledTonalIconButton(
                onClick = { onValueChange((value + step).coerceIn(range.first, range.last)) },
                enabled = value < range.last,
                modifier = Modifier.size(38.dp),
            ) { Icon(Icons.Filled.Add, contentDescription = "增加") }
        }
    }
}
