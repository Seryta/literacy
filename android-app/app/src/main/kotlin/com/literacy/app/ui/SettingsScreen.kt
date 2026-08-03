package com.literacy.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.literacy.app.settings.AppSettings
import com.literacy.app.ui.theme.LiteracyDimens

/**
 * 设置页：LLM Provider 配置（API key / baseUrl / model）。
 * key 仅存本机（SharedPreferences），不上传。
 * 开发模式（debug 直达学习阶段）也收纳在此，普通用户不可见。
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onDebugStartLearning: ((String) -> Unit)? = null,   // debug 构建的直达学习入口
) {
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var model by remember { mutableStateOf(settings.model) }
    var saved by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }   // review-09 P2-8

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(LiteracyDimens.ScreenPadding)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("设置", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.padding(LiteracyDimens.CardPadding)) {
                Text("AI 老师配置", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "首次使用需要由家人帮忙配置一次",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; saved = false; saveFailed = false },
                    label = { Text("API Key（如 sk-...）") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),   // review-05 P2-3：掩码显示
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; saved = false },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    shape = MaterialTheme.shapes.small,
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it; saved = false },
                    label = { Text("模型") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    shape = MaterialTheme.shapes.small,
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(LiteracyDimens.ActionButtonHeight),
                    shape = MaterialTheme.shapes.large,
                ) { Text("保存", fontSize = 20.sp) }

                if (saved) {
                    Spacer(Modifier.height(8.dp))
                    Text("已保存（仅存本机）", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }
                if (saveFailed) {
                    Spacer(Modifier.height(8.dp))
                    Text("保存失败：加密存储不可用，请检查设备安全存储", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "说明：API Key 仅存储在本机，用于调用 AI 老师。\n" +
                        "默认 deepseek（OpenAI 兼容格式），支持改为其他兼容 Provider。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 开发模式（仅 debug 构建）：直达各阶段，便于测试独立写/复习等流程
        if (com.literacy.app.BuildConfig.DEBUG && onDebugStartLearning != null) {
            Spacer(Modifier.height(20.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(Modifier.padding(LiteracyDimens.CardPadding)) {
                    Text("开发模式（debug 构建）", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.height(8.dp))
                    listOf("guided_write" to "直达跟写", "independent_write" to "直达独立写", "review" to "直达复习").forEach { (stage, label) ->
                        OutlinedButton(
                            onClick = { onDebugStartLearning("家:$stage") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .padding(vertical = 2.dp),
                            shape = MaterialTheme.shapes.medium,
                        ) { Text("$label「家」", fontSize = 16.sp) }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onBack) { Text("← 返回", fontSize = 17.sp) }
        Spacer(Modifier.height(16.dp))
    }
}
