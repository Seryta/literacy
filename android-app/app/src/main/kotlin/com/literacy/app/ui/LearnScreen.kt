package com.literacy.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.literacy.agent.model.Phase
import com.literacy.app.ui.theme.LiteracyDimens

/** 阶段中文映射（UI 层，不动 agent-core 的英文 display）——适老用户看不懂英文阶段名。 */
private fun phaseLabelZh(phase: String): String = when (phase) {
    Phase.INTRODUCE.display -> "认识这个字"
    Phase.RECOGNIZE.display -> "认一认"
    Phase.DEMONSTRATE.display -> "看老师写"
    Phase.GUIDED_WRITE.display -> "跟着写"
    Phase.INDEPENDENT_WRITE.display -> "自己写"
    Phase.EXPLAIN.display -> "说一说"
    Phase.SENTENCE.display -> "用一用"
    Phase.RECORD.display -> "记一记"
    Phase.DECIDE.display -> "下一步"
    else -> phase
}

/**
 * 学习主界面：当前字 + 米字格 + 教学语 + 输入与操作按钮。
 * 适老化：大字号、2×2 大操作按钮、教学语卡片化；交互逻辑不变。
 */
@Composable
fun LearnScreen(
    viewModel: LearnViewModel,
    partialText: String = "",   // 实时字幕（自动语音识别中用户说的话）
    onBack: () -> Unit,
) {
    val ui = viewModel.ui
    var input by remember { mutableStateOf("") }
    var strokeCount by remember { mutableStateOf(0) }
    var drawnLibrary by remember { mutableStateOf<List<List<Pair<Float, Float>>>>(emptyList()) }
    // review-11 P1-5：清屏（clear_grid）也重置提交数据——此前只随 phase 重置，清屏后点"完成书写"
    // 会把旧轨迹一并提交（与 Canvas 重建的 resetKey 保持一致）
    // P1-2：重置边界与 MizigeGrid resetKey 同源——复习所有阶段 phase=null，仅按 phase 重置会
    // 让 RECALL→ASSESS、ASSESS→REINFORCE、切换复习字保留旧轨迹；含 char/mode/reviewStage/phase/clearGridSignal
    LaunchedEffect(ui.char, ui.mode, ui.reviewStage, ui.phase, viewModel.clearGridSignal) {
        drawnLibrary = emptyList(); strokeCount = 0
    }
    val focusManager = LocalFocusManager.current
    // 用户开口（实时转写非空）→ 打断教学 TTS，让位给用户（实时对话）
    LaunchedEffect(partialText) {
        if (partialText.isNotBlank()) viewModel.stopTts()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = LiteracyDimens.ScreenPadding)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── 顶部：返回 + 阶段标签 ──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.height(56.dp),
                shape = MaterialTheme.shapes.medium,
            ) { Text("← 首页", fontSize = 17.sp) }
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))

        // 阶段胶囊标签（复习模式不显示答案字，标签不含答案）
        val phaseLabel = when {
            ui.mode == "review" -> "复习 · ${ui.reviewStage ?: "-"}"
            else -> phaseLabelZh(ui.phase)
        }
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.extraSmall,
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            Text(
                phaseLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }

        // P1-4 + review-09 P1-3：独立写（无提示）与复习检测阶段（RECALL 提取练习 / ASSESS 判题）
        // 都不显示答案（字形/拼音/结构）——RECALL 隐藏后标题不得含 char，ASSESS 同样隐藏
        var answerLocked by remember(ui.exercise?.exerciseId) { mutableStateOf(false) }   // 每道新题解锁
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
                fontSize = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = when {
                ui.mode == "review" -> "复习模式（${ui.reviewStage ?: "-"}）"   // review-09 P1-3：标题不含答案字
                else -> "笔画：${ui.strokeCount}"
            },
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        // 米字格（按阶段决定揭示模式）
        val reveal = when {
            ui.mode == "review" && ui.reviewStage in listOf("recall", "assess") -> 0
            ui.phase in listOf(Phase.RECOGNIZE.display, Phase.DEMONSTRATE.display) -> -1            // 显示全字
            // P2-2 权威成功笔数 + review-10 P1-4：首次进入也显示当前待写笔（reveal = 已完成+1）
            ui.phase == Phase.GUIDED_WRITE.display -> (viewModel.completedStrokes + 1).coerceAtMost(ui.strokeCount)
            ui.phase == Phase.INDEPENDENT_WRITE.display -> 0                                 // 空白
            else -> -1
        }
        MizigeGrid(
            char = ui.char,
            strokes = viewModel.orchestratorStrokes,
            revealStrokes = reveal,
            showOutline = !hideAnswer && ui.phase in listOf(Phase.RECOGNIZE.display, Phase.DEMONSTRATE.display, ""),
            // P1-8：回调为字库坐标（评估）；P1-7：阶段变化重置轨迹（跟写轨迹不混入独立写）
            // P1-2：重置边界含 char/mode/reviewStage/phase/clearGridSignal——复习阶段（phase=null）
            // 换阶段/换复习字必须清旧轨迹（此前仅 phase+clearGridSignal，复习内所有阶段同 key 不重置）
            resetKey = ui.char + ":" + ui.mode + ":" + ui.reviewStage + ":" + ui.phase + ":" + viewModel.clearGridSignal,
            enabled = !ui.loading && !ui.paused && !ui.sessionEnded,   // P1-14：门禁
            onStrokeCountChanged = { strokeCount = it },
            onStrokeComplete = { path ->
                drawnLibrary = drawnLibrary + listOf(path)
                viewModel.onStrokeDrawn(path)
            },
            modifier = Modifier
                .size(300.dp)
                .padding(8.dp)
                .testTag("mizige_grid"),   // 测试定位（androidTest 手势绘制）
        )

        // 独立写 / 复习 ASSESS·REINFORCE：画完多笔后点"完成书写"提交整体评估（MASTERY-CRITERIA §4）
        // P1-1：复习态 phase=null——不能只看 phase==INDEPENDENT_WRITE；按 mode+reviewStage 接通
        // 已有整字评估链路（ASSESS 听写走整字提交；REINFORCE 复用整字/逐笔链路）
        val showCompleteWriting = (ui.phase == Phase.INDEPENDENT_WRITE.display ||
            (ui.mode == "review" && ui.reviewStage in listOf("assess", "reinforce"))) && !ui.sessionEnded
        if (showCompleteWriting) {
            Text(
                "已画 ${strokeCount}/${ui.strokeCount} 笔，画完点完成",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { viewModel.onCompleteWriting(drawnLibrary) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LiteracyDimens.ActionButtonHeight),
                shape = MaterialTheme.shapes.large,
                enabled = strokeCount > 0,
            ) { Text("完成书写（$strokeCount 笔）", fontSize = 19.sp) }
        }

        // 教学语（LLM text，TTS 已自动朗读；固定高度区域，任何阶段稳定显示，内容多可滚动）
        Spacer(Modifier.height(10.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                text = ui.text.ifEmpty { "等待老师说话…" },
                fontSize = 18.sp,
                lineHeight = 26.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp, max = 120.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(LiteracyDimens.CardPadding),
            )
        }
        if (ui.listening) {
            Text("🎙 老师正在听…", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
        }
        if (ui.loading) {
            Text("⏳ 老师思考中…", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
        }
        if (ui.paused) {
            Text("⏸ 已暂停", fontSize = 15.sp, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 6.dp))
        }
        if (ui.providerFailed) {
            // 技术性报错不暴露给用户：温和提示，引导家人协助（不阻塞学习主流程）
            Text(
                "语音老师还没准备好，请先让家人帮忙设置",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // ── 实时字幕：正在听用户说话（边说边显示）──
        if (partialText.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Text(
                    "🎤 $partialText",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        } else if (ui.listening) {
            Text("🎤 我在听…", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // 输入区（打字辅助：家人可帮忙输入；语音为主交互，已在自动监听）
        // 右侧留 76dp：悬浮吉祥物默认位置，避免遮挡输入
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("打字输入，家人可帮忙") },
            singleLine = true,
            enabled = !ui.loading && !ui.paused && !ui.sessionEnded,   // review-09 P1-12：结束后禁输入
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 76.dp),
            shape = MaterialTheme.shapes.small,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(end = 76.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    viewModel.onUserInput(input.trim())
                    input = ""
                    focusManager.clearFocus()
                },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = MaterialTheme.shapes.medium,
                enabled = !ui.loading && !ui.paused && !ui.sessionEnded,   // review-09 P1-12
            ) { Text("发送", fontSize = 18.sp) }
            // 开发模式：模拟认读（绕过中文输入限制，仅 debug 构建）
            if (com.literacy.app.BuildConfig.DEBUG && ui.phase == Phase.RECOGNIZE.display) {
                OutlinedButton(
                    onClick = { viewModel.onSimulatedRecognition(true) },
                    modifier = Modifier.height(52.dp),
                ) { Text("✓认对") }
                OutlinedButton(
                    onClick = { viewModel.onSimulatedRecognition(false) },
                    modifier = Modifier.height(52.dp),
                ) { Text("✗认错") }
            }
        }

        // review-11 P1-1.4：选择题渲染本地真值（AgentOrchestrator 从 show_options 执行提取：
        // 选项 + 题目 id + 正确答案=当前字）——不直接信模型 show_options 参数渲染选项；
        // 作答后 currentExercise 清空（一次性消费），answerLocked 兜底禁用
        ui.exercise?.let { ex ->
            Text("请选择正确答案：", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ex.options.forEach { opt ->
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
        // review-09 P1-5：模型声明的 UI 工具渲染（show_sentence 句子 / highlight_stroke 当前笔）
        ui.uiTools.takeLast(3).forEach { tool ->
            when (tool.name) {
                "show_sentence" -> {
                    // review-10 P1-5：参数字段兼容（text/sentence/content）
                    // review-11 P1-4.1：canonical 参数 sentence_text 是校验/读取主键（此前 UI 只读旧键漏渲染）
                    (tool.arguments["sentence_text"]
                        ?: tool.arguments["text"] ?: tool.arguments["sentence"] ?: tool.arguments["content"])
                        ?.toString()?.takeIf { it.isNotBlank() }?.let { sentence ->
                        Spacer(Modifier.height(8.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(sentence, modifier = Modifier.padding(12.dp), fontSize = 18.sp, lineHeight = 26.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
                // review-11 P1-4.2：show_pinyin/show_character 此前只记录不消费（认读帮助/独立写提示看不到）——
                // 这里渲染；RECOGNIZE 检测阶段不显示拼音（拼音是答案，与 hidePinyin 同约束）；
                // 复习 recall 的展示工具已被 ReplayRunner 本地拒绝（GT-051），不会到达 UI
                "show_pinyin" -> {
                    if (!hidePinyin && !ui.sessionEnded) {
                        ui.pinyin.takeIf { it.isNotBlank() }?.let { py ->
                            Text("拼音：$py", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }
                "show_character" -> {
                    // 独立写提示：模型 reveal 时覆盖隐藏（主标题 "？" 场景也能看到字形）
                    // P1-3：show_character(revealStrokes=0) 是"不揭示任何笔画"的独立写/检测提示——
                    // 参数即答案，不得渲染完整汉字；hideAnswer（独立写/复习 RECALL·ASSESS）保留为第二层保护
                    val reveal = tool.arguments["revealStrokes"]?.toString()?.toIntOrNull()
                    if (!ui.sessionEnded && !hideAnswer && reveal != 0) {
                        ui.char.takeIf { it.isNotBlank() }?.let { c ->
                            Text("字形提示：$c", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }
                "highlight_stroke" -> {
                    tool.arguments["stroke"]?.toString()?.takeIf { it.isNotBlank() }?.let { n ->
                        Spacer(Modifier.height(8.dp))
                        Text("请书写第 $n 笔", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        }

        // 操作按钮：学习模式 2×2 大网格；复习模式 2+1（大触控目标）
        Spacer(Modifier.height(14.dp))
        if (ui.mode == "review") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { viewModel.onButton("review_stage") },
                    modifier = Modifier
                        .weight(1f)
                        .height(LiteracyDimens.ActionButtonHeight),
                    shape = MaterialTheme.shapes.large,
                    enabled = !ui.sessionEnded && !ui.paused && ui.reviewStage != "next",
                ) { Text("下一阶段", fontSize = 18.sp) }
                OutlinedButton(
                    onClick = { viewModel.onButton("end") },
                    modifier = Modifier
                        .weight(1f)
                        .height(LiteracyDimens.ActionButtonHeight),
                    shape = MaterialTheme.shapes.large,
                    enabled = !ui.sessionEnded,
                ) { Text("结束", fontSize = 18.sp) }
            }
            OutlinedButton(
                onClick = { viewModel.onButton("next") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LiteracyDimens.ActionButtonHeight),
                shape = MaterialTheme.shapes.large,
                enabled = !ui.sessionEnded && !ui.paused,
            ) { Text("下一复习字", fontSize = 18.sp) }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val ended = ui.sessionEnded
                OutlinedButton(
                    onClick = { viewModel.onButton("help") },
                    modifier = Modifier
                        .weight(1f)
                        .height(LiteracyDimens.ActionButtonHeight),
                    shape = MaterialTheme.shapes.large,
                    enabled = !ended && !ui.paused,
                ) { Text("帮助", fontSize = 18.sp) }
                OutlinedButton(
                    onClick = { viewModel.onButton("skip") },
                    modifier = Modifier
                        .weight(1f)
                        .height(LiteracyDimens.ActionButtonHeight),
                    shape = MaterialTheme.shapes.large,
                    enabled = !ended && !ui.paused,
                ) { Text("跳过", fontSize = 18.sp) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val ended = ui.sessionEnded
                OutlinedButton(
                    onClick = { viewModel.onButton(if (ui.paused) "resume" else "pause") },
                    modifier = Modifier
                        .weight(1f)
                        .height(LiteracyDimens.ActionButtonHeight),
                    shape = MaterialTheme.shapes.large,
                    enabled = !ended,
                ) { Text(if (ui.paused) "继续" else "暂停", fontSize = 18.sp) }
                OutlinedButton(
                    onClick = { viewModel.onButton("end") },
                    modifier = Modifier
                        .weight(1f)
                        .height(LiteracyDimens.ActionButtonHeight),
                    shape = MaterialTheme.shapes.large,
                    enabled = !ended,
                ) { Text("结束", fontSize = 18.sp) }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
