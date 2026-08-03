package com.literacy.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.literacy.app.settings.AppSettings

/**
 * 设置页：LLM Provider 配置（API key / baseUrl / model）。
 * key 仅存本机（SharedPreferences），不上传。
 */
@Composable
fun SettingsScreen(settings: AppSettings, onBack: () -> Unit) {
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var model by remember { mutableStateOf(settings.model) }
    var saved by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }   // review-09 P2-8

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        Text("LLM Provider 配置", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; saved = false; saveFailed = false },
            label = { Text("API Key（如 sk-...）") },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),   // review-05 P2-3：掩码显示
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; saved = false },
            label = { Text("Base URL") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it; saved = false },
            label = { Text("模型") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                // review-09 P2-8：api_key 加密写入结果反馈（失败不再假"已保存"）
                val keySaved = settings.saveApiKey(apiKey)
                settings.baseUrl = baseUrl.trim()
                settings.model = model.trim()
                saved = keySaved
                saveFailed = !keySaved
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("保存") }

        if (saved) {
            Spacer(Modifier.height(8.dp))
            Text("已保存（仅存本机）", color = MaterialTheme.colorScheme.primary)
        }
        if (saveFailed) {
            Spacer(Modifier.height(8.dp))
            Text("保存失败：加密存储不可用，请检查设备安全存储", color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "说明：API Key 仅存储在本机，用于调用 LLM Provider。\n" +
                "默认 deepseek（OpenAI 兼容格式），支持改为其他兼容 Provider。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onBack) { Text("← 返回") }
    }
}
