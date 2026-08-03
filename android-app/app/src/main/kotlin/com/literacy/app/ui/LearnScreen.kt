package com.literacy.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.literacy.agent.model.Phase

/**
 * 学习主界面：当前字 + 米字格 + 教学语 + 输入与操作按钮。
 * 第一版以文本框模拟语音输入（STT 接入后替换）。
 */
@Composable
fun LearnScreen(
    viewModel: LearnViewModel,
    onBack: () -> Unit,
) {
    val ui = viewModel.ui
    var input by remember { mutableStateOf("") }
    var strokeCount by remember { mutableStateOf(0) }
    var drawnLibrary by remember { mutableStateOf<List<List<Pair<Float, Float>>>>(emptyList()) }
    LaunchedEffect(ui.phase) { drawnLibrary = emptyList(); strokeCount = 0 }
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 顶部：返回 + 当前字 + 拼音 + 阶段
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 首页") }
            Spacer(Modifier.weight(1f))
            if (ui.providerFailed) {
                Text(
                    "⚠ AI 未连接（检查设置中的 API Key）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        // P1-4 + review-09 P1-3：独立写（无提示）与复习检测阶段（RECALL 提取练习 / ASSESS 判题）
        // 都不显示答案（字形/拼音/结构）——RECALL 隐藏后标题不得含 char，ASSESS 同样隐藏
        var answerLocked by remember(ui.uiTools.size) { mutableStateOf(false) }   // review-10 P1-5：旧题一次性消费
        val hideAnswer = (ui.phase == Phase.INDEPENDENT_WRITE.display && !ui.sessionEnded) ||
            (ui.mode == "review" && (ui.reviewStage == "recall" || ui.reviewStage == "assess"))
        Text(
            text = if (hideAnswer) "？" else ui.char.ifEmpty { "—" },
            fontSize = 88.sp,
            fontWeight = FontWeight.Bold,
            color = if (hideAnswer) MaterialTheme.colorScheme.outline else Color.Unspecified,
        )
        // review-10 P1-5：认读检测（RECOGNIZE）不显示拼音——拼音是答案（看着字形读）
        val hidePinyin = hideAnswer || ui.phase == Phase.RECOGNIZE.display
        if (!hidePinyin) {
            Text(
                text = listOfNotNull(
                    ui.pinyin.ifEmpty { null },
                    ui.decomposition.ifEmpty { null },
                ).joinToString("  "),
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = when {
                ui.mode == "review" -> "复习模式（${ui.reviewStage ?: "-"}）"   // review-09 P1-3：标题不含答案字
                else -> "阶段：${ui.phase}　提示等级：L${ui.promptLevel}　笔画：${ui.strokeCount}"
            },
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        // 米字格（按阶段决定揭示模式）
        val reveal = when (ui.phase) {
            Phase.RECOGNIZE.display, Phase.DEMONSTRATE.display -> -1            // 显示全字
            // P2-2 权威成功笔数 + review-10 P1-4：首次进入也显示当前待写笔（reveal = 已完成+1）
            Phase.GUIDED_WRITE.display -> (viewModel.completedStrokes + 1).coerceAtMost(ui.strokeCount)
            Phase.INDEPENDENT_WRITE.display -> 0                                 // 空白
            else -> -1
        }
        MizigeGrid(
            char = ui.char,
            strokes = viewModel.orchestratorStrokes,
            revealStrokes = reveal,
            showOutline = ui.phase in listOf(Phase.RECOGNIZE.display, Phase.DEMONSTRATE.display, ""),
            // P1-8：回调为字库坐标（评估）；P1-7：阶段变化重置轨迹（跟写轨迹不混入独立写）
            resetKey = ui.phase.toString() + ":" + viewModel.clearGridSignal,   // review-09 P1-5：clear_grid 触发重置
            enabled = !ui.loading && !ui.paused && !ui.sessionEnded,   // P1-14：门禁
            onStrokeCountChanged = { strokeCount = it },
            onStrokeComplete = { path ->
                drawnLibrary = drawnLibrary + listOf(path)
                viewModel.onStrokeDrawn(path)
            },
            modifier = Modifier
                .size(300.dp)
                .padding(8.dp),
        )

        // 独立写阶段：画完多笔后点"完成书写"提交整体评估（MASTERY-CRITERIA §4）
        if (ui.phase == Phase.INDEPENDENT_WRITE.display && !ui.sessionEnded) {
            Text(
                "已画 ${strokeCount}/${ui.strokeCount} 笔，画完点完成",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { viewModel.onCompleteWriting(drawnLibrary) },
                modifier = Modifier.fillMaxWidth(),
                enabled = strokeCount > 0,
            ) { Text("完成书写（$strokeCount 笔）") }
        }

        // 教学语（LLM text，TTS 已自动朗读；固定高度区域，任何阶段稳定显示，内容多可滚动）
        Spacer(Modifier.height(8.dp))
        Text(
            text = ui.text.ifEmpty { "（等待老师说话…）" },
            fontSize = 17.sp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp, max = 120.dp)
                .verticalScroll(rememberScrollState()),
        )
        if (ui.listening) {
            Text("🎙 老师正在听…", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        }
        if (ui.loading) {
            Text("⏳ 老师思考中…", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        }
        if (ui.paused) {
            Text("⏸ 已暂停（其他按钮不可用）", fontSize = 14.sp, color = MaterialTheme.colorScheme.tertiary)
            OutlinedButton(
                onClick = { viewModel.onButton("resume") },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("继续") }
        }

        // 输入区（第一版以文本框模拟语音；P1-14：loading/paused 时禁用防并发乱序）
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("说点什么（模拟语音）") },
            singleLine = true,
            enabled = !ui.loading && !ui.paused && !ui.sessionEnded,   // review-09 P1-12：结束后禁输入
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    viewModel.onUserInput(input.trim())
                    input = ""
                    focusManager.clearFocus()
                },
                modifier = Modifier.weight(1f),
                enabled = !ui.loading && !ui.paused && !ui.sessionEnded,   // review-09 P1-12
            ) { Text("发送") }
            // 开发模式：模拟认读（绕过中文输入限制，仅 debug 构建）
            if (com.literacy.app.BuildConfig.DEBUG && ui.phase == Phase.RECOGNIZE.display) {
                OutlinedButton(onClick = { viewModel.onSimulatedRecognition(true) }) { Text("✓认对") }
                OutlinedButton(onClick = { viewModel.onSimulatedRecognition(false) }) { Text("✗认错") }
            }
        }

        // review-09 P1-5：模型声明的 UI 工具渲染（show_options 选项按钮 / show_sentence 句子 / highlight_stroke 当前笔）
        ui.uiTools.takeLast(3).forEach { tool ->
            when (tool.name) {
                "show_options" -> {
                    val opts = (tool.arguments["options"] as? List<*>)?.mapNotNull { it?.toString() }
                        ?: (tool.arguments["options"] as? String)?.split(",")?.map { it.trim() }
                    if (!opts.isNullOrEmpty()) {
                        Text("请选择正确答案：", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        opts.forEach { opt ->
                            // review-10 P1-5：选项一次性消费（answered 后旧题不可重复点）；本地判题
                            OutlinedButton(
                                onClick = {
                                    answerLocked = true   // 本地判题在 AgentOrchestrator（correct=目标字比较）
                                    viewModel.onButton(opt)
                                },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                enabled = !answerLocked && !ui.sessionEnded && !ui.paused,
                            ) { Text(opt) }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
                "show_sentence" -> {
                    // review-10 P1-5：参数字段兼容（text/sentence/content）
                    (tool.arguments["text"] ?: tool.arguments["sentence"] ?: tool.arguments["content"])
                        ?.toString()?.takeIf { it.isNotBlank() }?.let { sentence ->
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Text(sentence, modifier = Modifier.padding(10.dp), fontSize = 16.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
                "highlight_stroke" -> {
                    tool.arguments["stroke"]?.toString()?.takeIf { it.isNotBlank() }?.let { n ->
                        Text("请书写第 $n 笔", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        }

        // 操作按钮（复习模式：next / end；学习模式：帮助/跳过/暂停/结束）
        Spacer(Modifier.height(8.dp))
        if (ui.mode == "review") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.onButton("review_stage") }, modifier = Modifier.weight(1f), enabled = !ui.sessionEnded && !ui.paused && ui.reviewStage != "next") { Text("下一阶段") }
                OutlinedButton(onClick = { viewModel.onButton("next") }, modifier = Modifier.weight(1f), enabled = !ui.sessionEnded && !ui.paused) { Text("下一复习字") }
                OutlinedButton(onClick = { viewModel.onButton("end") }, modifier = Modifier.weight(1f), enabled = !ui.sessionEnded) { Text("结束") }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val ended = ui.sessionEnded
                OutlinedButton(onClick = { viewModel.onButton("help") }, modifier = Modifier.weight(1f), enabled = !ended && !ui.paused) { Text("帮助") }
                OutlinedButton(onClick = { viewModel.onButton("skip") }, modifier = Modifier.weight(1f), enabled = !ended && !ui.paused) { Text("跳过") }
                OutlinedButton(onClick = { viewModel.onButton("pause") }, modifier = Modifier.weight(1f), enabled = !ended && !ui.paused) { Text("暂停") }
                OutlinedButton(onClick = { viewModel.onButton("end") }, modifier = Modifier.weight(1f), enabled = !ended) { Text("结束") }
            }
        }
    }
}
