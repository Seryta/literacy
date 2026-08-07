package com.literacy.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.literacy.app.ui.theme.LiteracyDimens
import com.literacy.app.ui.voice.VoiceHub

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    listening: Boolean,
    onSkip: () -> Unit,
    onStepChanged: (OnboardingViewModel.Step) -> Unit = {},
    onRobotSpeak: () -> Unit = {},
) {
    BackHandler(onBack = onSkip)
    val ui = viewModel.ui
    val stepOrder = listOf(
        OnboardingViewModel.Step.PICK_MASCOT,
        OnboardingViewModel.Step.ASK_NAME,
        OnboardingViewModel.Step.CONFIRM_NAME,
        OnboardingViewModel.Step.CONFIG_LLM,
        OnboardingViewModel.Step.GUIDE_START,
    )
    val currentStepIndex = stepOrder.indexOf(ui.step).coerceAtLeast(0)

    // 引导 step 变化 → 通知 MainActivity 更新 VoiceFlow 的 idlePrompt（分阶段说不同的话，
    // 避免 PICK_MASCOT 过了还在说"第一个"的通用提示 + 循环播报）
    LaunchedEffect(ui.step) { onStepChanged(ui.step) }

    // TTS：机器人说话（生命周期随页面）
    val context = androidx.compose.ui.platform.LocalContext.current
    val tts = remember { LocalTts(context) }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }
    // robotText 变化 → 朗读 + 重置沉默计时（机器人说话算主动沟通，不被自己的 TTS 触发沉默提示循环）
    LaunchedEffect(ui.robotText) {
        if (ui.robotText.isNotBlank()) {
            tts.speak(ui.robotText)
            onRobotSpeak()
        }
    }
    // 用户开口（实时转写非空）→ 打断机器人说话，让位给用户（实时对话）
    LaunchedEffect(viewModel.partialText) {
        if (viewModel.partialText.isNotBlank()) tts.stop()
    }
    // 说明文字点读：所有说明/帮助/占位 Text 统一包一层可点击 → TTS 朗读（修 3：点击说明无语音）
    val TtsHelpText: @Composable (String, androidx.compose.ui.text.TextStyle, Color) -> Unit = { text, style, color ->
        Text(
            text = text,
            style = style,
            color = color,
            modifier = Modifier.clickable(
                onClick = { tts.speak(text) },
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            ),
        )
    }

    // 机器人浮动动画（活泼感）
    val transition = rememberInfiniteTransition(label = "obFloat")
    val floatY by transition.animateFloat(
        initialValue = 0f, targetValue = -8f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "obFloatY",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(LiteracyDimens.ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        // 进度点
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            stepOrder.forEachIndexed { i, _ ->
                Box(
                    modifier = Modifier
                        .size(if (i == currentStepIndex) 12.dp else 10.dp)
                        .clip(CircleShape)
                        .background(
                            if (i <= currentStepIndex) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                )
            }
        }
        Spacer(Modifier.height(24.dp))

        // ── 对话区：机器人 + 气泡 ──
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .offset(y = floatY.dp)
                    .size(72.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                MascotAvatar(variant = Mascots.candidates[ui.mascotIndex].variant, size = 58.dp)
            }
            Spacer(Modifier.width(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large,
                shadowElevation = 3.dp,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 6.dp),
            ) {
                Text(
                    ui.robotText,
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 28.sp,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }

        // ── 实时字幕：用户正在说的话（边说边显示，实时对话感）──
        Spacer(Modifier.height(12.dp))
        if (viewModel.partialText.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "🎤 ${viewModel.partialText}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        } else if (listening && ui.step != OnboardingViewModel.Step.VOICE_PREP) {
            // VOICE_PREP 第一屏专注下载：不显示"我在听…"，避免暗示语音已可用
            Text(
                "🎤 我在听…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))

        // ── 选宠物（PICK_MASCOT）：纵向大卡片列表（从上到下，说"第几个"）──
        if (ui.step == OnboardingViewModel.Step.PICK_MASCOT) {   // 仅选宠物步显示列表（语音包准备步不显示）
            Spacer(Modifier.height(16.dp))
            Mascots.candidates.forEachIndexed { index, mascot ->
                val selected = index == ui.mascotIndex
                Card(
                    onClick = { viewModel.onSelectMascot(index) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    ),
                    border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.titleLarge,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(32.dp),
                        )
                        MascotAvatar(variant = mascot.variant, size = 56.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(mascot.variant.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(mascot.variant.tagline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (selected) {
                            Spacer(Modifier.weight(1f))
                            Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            TtsHelpText(
                "从上往下数，说“第几个”也可以选，比如“第一个”",
                MaterialTheme.typography.bodySmall,
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── 配置 AI 老师（CONFIG_LLM）：baseUrl+model+apiKey，首次引导必须完成 ──
        if (ui.step == OnboardingViewModel.Step.CONFIG_LLM) {
            Spacer(Modifier.height(20.dp))
            var apiKey by remember { mutableStateOf(ui.tempApiKey) }
            var baseUrl by remember { mutableStateOf(ui.tempBaseUrl) }
            var model by remember { mutableStateOf(ui.tempModel) }
            var saved by remember { mutableStateOf(false) }
            var saveFailed by remember { mutableStateOf(false) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(Modifier.padding(LiteracyDimens.CardPadding)) {
                    Text("AI 老师配置", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    TtsHelpText(
                        "首次使用请由家人帮忙填一次；保存后才能继续学习",
                        MaterialTheme.typography.bodySmall,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it; saved = false; saveFailed = false
                            viewModel.onTypedLlmConfig(apiKey = it, baseUrl = baseUrl, model = model)
                        },
                        label = { Text("API Key") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                    )
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = {
                            baseUrl = it; saved = false
                            viewModel.onTypedLlmConfig(apiKey = apiKey, baseUrl = it, model = model)
                        },
                        label = { Text("Base URL") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        shape = MaterialTheme.shapes.small,
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = {
                            model = it; saved = false
                            viewModel.onTypedLlmConfig(apiKey = apiKey, baseUrl = baseUrl, model = it)
                        },
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
                            val ok = viewModel.onSaveLlmConfig(apiKey, baseUrl, model)
                            saved = ok; saveFailed = !ok
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(LiteracyDimens.ActionButtonHeight),
                        shape = MaterialTheme.shapes.large,
                    ) { Text("保存并继续", fontSize = 20.sp) }
                    if (saved) {
                        Spacer(Modifier.height(8.dp))
                        Text("已保存，仅存本机", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (saveFailed) {
                        Spacer(Modifier.height(8.dp))
                        Text("保存失败：加密存储不可用，请检查设备安全存储",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        // ── 选项按钮 ──
        if (ui.showOptions.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            ui.showOptions.forEach { label ->
                Button(
                    onClick = { viewModel.onOptionClick(label) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .height(LiteracyDimens.ActionButtonHeight),
                    shape = MaterialTheme.shapes.large,
                ) { Text(label, fontSize = 20.sp) }
            }
        }

        // ── 姓名输入（ASK_NAME / FALLBACK_INPUT）──
        if (ui.showInput) {
            Spacer(Modifier.height(20.dp))
            var name by remember { mutableStateOf("") }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(ui.inputLabel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { viewModel.onTypedName(name); name = "" },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.large,
                enabled = name.isNotBlank(),
            ) { Text("确定", fontSize = 18.sp) }
            Spacer(Modifier.height(8.dp))
            TtsHelpText(
                "也可以直接说：我叫张建国",
                MaterialTheme.typography.bodySmall,
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 姓名可跳过（ASK_NAME 步）："先不录名字" → 直接完成引导（首页建档卡引导）
            if (ui.step == OnboardingViewModel.Step.ASK_NAME) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { viewModel.onOptionClick("先不录名字") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = MaterialTheme.shapes.large,
                ) { Text("先不录名字", fontSize = 16.sp) }
            }
        }

        // ── 语音状态：引导阶段自动持续听，无需任何点击 ──
        // 语音包未就绪（VOICE_PREP 第一屏）不显示——先专注下载，避免误以为语音已就绪；
        // 进入后续步骤的前提是语音包就绪或已选系统语音，故此时显示状态条是安全的。
        if (ui.step != OnboardingViewModel.Step.VOICE_PREP) {
            Spacer(Modifier.height(16.dp))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "🎤 我在听，你直接说就行",
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(vertical = 14.dp, horizontal = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // ── 语音包准备（VOICE_PREP）：不可跳过（语音老师是使用前提）──
        if (ui.step == OnboardingViewModel.Step.VOICE_PREP && !ui.voiceModelsReady) {
            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(LiteracyDimens.CardPadding)) {
                    Text("语音老师准备", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.height(8.dp))
                    TtsHelpText(
                        "下载语音包：女声朗读 + 离线识别，约 210MB，建议连 Wi-Fi",
                        MaterialTheme.typography.bodyLarge,
                        MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(14.dp))
                    if (ui.voiceDownloading) {
                        LinearProgressIndicator(
                            progress = { ui.voiceDownloadProgress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("下载中… ${ui.voiceDownloadProgress}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    } else if (ui.voiceFailCount > 0) {
                        // 下载失败后说明原因并提供重试；离线语音包仍是进入下一步的前提。
                        TtsHelpText(
                            "下载失败，请检查网络和 Wi-Fi 后再试。",
                            MaterialTheme.typography.bodyLarge + androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.Bold),
                            MaterialTheme.colorScheme.error,
                        )
                        if (ui.voiceError.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "原因：${ui.voiceError}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.startVoiceDownload() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = MaterialTheme.shapes.large,
                        ) { Text("重试下载", fontSize = 18.sp) }
                    } else {
                        Button(
                            onClick = { viewModel.startVoiceDownload() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = MaterialTheme.shapes.large,
                        ) { Text("下载语音包", fontSize = 18.sp) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(20.dp))
        // 引导不可整体跳过：选宠物、语音包和 AI 配置必须完成；姓名可推迟。
        Spacer(Modifier.height(16.dp))
    }
}
