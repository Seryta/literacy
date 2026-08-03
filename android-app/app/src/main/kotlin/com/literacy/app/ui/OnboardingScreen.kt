package com.literacy.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

/**
 * 首次引导页：活泼机器人对话式建档。
 * 流程由 [OnboardingViewModel] 状态机驱动；机器人说话（TTS）+ 气泡文字同步。
 */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    listening: Boolean,
    onSpeak: () -> Unit,
    onSkip: () -> Unit,
) {
    val ui = viewModel.ui
    val stepOrder = listOf(
        OnboardingViewModel.Step.WELCOME,
        OnboardingViewModel.Step.PICK_MASCOT,
        OnboardingViewModel.Step.ASK_NAME,
        OnboardingViewModel.Step.CONFIRM_NAME,
        OnboardingViewModel.Step.GUIDE_START,
    )
    val currentStepIndex = stepOrder.indexOf(ui.step).coerceAtLeast(0)

    // TTS：机器人说话（生命周期随页面）
    val context = androidx.compose.ui.platform.LocalContext.current
    val tts = remember { LocalTts(context) }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }
    // robotText 变化 → 朗读（气泡文字始终可见，TTS 失败不阻断）
    LaunchedEffect(ui.robotText) {
        if (ui.robotText.isNotBlank()) tts.speak(ui.robotText)
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

        // ── 选宠物（PICK_MASCOT）：4 个卡片横排 ──
        if (ui.step == OnboardingViewModel.Step.PICK_MASCOT || ui.step == OnboardingViewModel.Step.WELCOME) {
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Mascots.candidates.forEachIndexed { index, mascot ->
                    val selected = index == ui.mascotIndex
                    Card(
                        onClick = { viewModel.onSelectMascot(index) },
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        ),
                        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            MascotAvatar(variant = mascot.variant, size = 52.dp)
                            Spacer(Modifier.height(6.dp))
                            Text("${index + 1}. ${mascot.variant.label}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "说“第几个”也可以选，比如“第一个”",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            Text(
                "也可以直接说：我叫张建国",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── 语音：引导阶段自动持续听，无需点击；此按钮为听不清时的兜底 ──
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onSpeak,
            modifier = Modifier
                .fillMaxWidth()
                .height(LiteracyDimens.ActionButtonHeight),
            shape = MaterialTheme.shapes.extraLarge,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (listening) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
            ),
        ) {
            Text(
                if (listening) "🎙️ 正在听，直接说话就行" else "听不清时，点这里说话",
                fontSize = 19.sp,
            )
        }
        Spacer(Modifier.height(8.dp))

        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onSkip) { Text("跳过引导（以后再说）", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(16.dp))
    }
}
