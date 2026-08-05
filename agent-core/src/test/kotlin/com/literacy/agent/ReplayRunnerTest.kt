package com.literacy.agent

import com.literacy.agent.engine.MasteryAdjudicator
import com.literacy.agent.engine.PhaseMachine
import com.literacy.agent.model.LearningPath
import com.literacy.agent.model.Phase
import com.literacy.agent.model.VoiceIntent
import com.literacy.agent.replay.ReplayRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 回放驱动测试：用 ReplayRunner 跑完整事件序列。
 * 对应 golden turn：
 * - GT-010 幂等落库
 * - GT-020 完整 9 阶段闭环（驱动版）
 * - GT-031/034 学习路径分支
 */
class ReplayRunnerTest {

    @Test
    fun `GT-010 同一 idempotency_key 重复 record_result 只插一条`() {
        val runner = ReplayRunner().startSession("家")
        val key = "550e8400-e29b-41d4-a716-446655440000"
        assertTrue(runner.recordResult("家", "independent_write", 0.85, "none", key))
        assertFalse(runner.recordResult("家", "independent_write", 0.85, "none", key))
        assertEquals(1, runner.store.results.size)
    }

    @Test
    fun `GT-020 完整 9 阶段闭环（驱动版）`() {
        val runner = ReplayRunner().startSession("家")
        assertEquals(Phase.INTRODUCE, runner.state.phase)

        assertTrue(runner.advance())                     // introduce → recognize（自动通过）
        assertTrue(runner.voice(VoiceIntent.RECOGNIZED)) // recognize → demonstrate
        runner.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("record_result", mapOf(
            "char" to "家",
            "result" to mapOf("phase" to "recognize", "score" to 1.0, "prompt_level" to "none", "idempotency_key" to "gt020-drive-recognize"),
        )))))   // P1-7：认对落库
        assertTrue(runner.advance())                     // demonstrate → guided_write（自动通过）
        assertTrue(runner.writing("guided_write", ok = true, promptLevel = 3))
        assertEquals(Phase.INDEPENDENT_WRITE, runner.state.phase)

        // review-11 P1-1.2：裁决用本地提示等级（attempt.promptLevel 兑底 state.promptLevel），
        // 模型 record_result 的 prompt_level 字段仅落库展示——独立写 L0 需本地状态为 L0（GT-023 语义）
        runner.configureState(runner.state.copy(promptLevel = 0))
        assertTrue(runner.writing("independent_write", ok = true, promptLevel = 0))
        // P1-7：裁决统一到 record_result——经 llmTurn 落库触发掌握升级
        runner.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("record_result", mapOf(
            "char" to "家",
            "result" to mapOf("phase" to "independent_write", "score" to 0.9, "prompt_level" to "none", "idempotency_key" to "gt020-drive-write"),
        )))))
        assertEquals(Phase.EXPLAIN, runner.state.phase)
        // GT-023 断言：L0 成功 → 书写等级 2
        assertEquals(2, runner.store.getCharacter("家").masteryWrite)

        assertTrue(runner.voice(VoiceIntent.OTHER))      // explain → sentence（尝试即可）
        assertTrue(runner.voice(VoiceIntent.OTHER))      // sentence → record
        assertTrue(runner.advance())                     // record → decide（自动通过）
        assertEquals(Phase.DECIDE, runner.state.phase)
    }

    @Test
    fun `GT-031 识主写辅路径 independent_write 改听音选字`() {
        val runner = ReplayRunner().startSession("家", LearningPath.READ_PRIMARY)
        runner.advance()
        runner.voice(VoiceIntent.RECOGNIZED)
        runner.advance()
        runner.writing("guided_write", ok = true, promptLevel = 3)
        assertEquals(Phase.INDEPENDENT_WRITE, runner.state.phase)

        // 书写通道不满足（识主写辅）
        assertFalse(runner.writing("independent_write", ok = true, promptLevel = 0))
        assertEquals(Phase.INDEPENDENT_WRITE, runner.state.phase)
        // 听音选字判题正确满足
        assertTrue(runner.tapped("select", correct = true, exerciseId = "e1"))
        assertEquals(Phase.EXPLAIN, runner.state.phase)
    }

    @Test
    fun `GT-034 识读优先路径 independent_write 改选字填空`() {
        val runner = ReplayRunner().startSession("家", LearningPath.READ_ONLY)
        runner.advance()
        runner.voice(VoiceIntent.RECOGNIZED)
        runner.advance()
        runner.writing("guided_write", ok = true, promptLevel = 3)
        assertFalse(runner.writing("independent_write", ok = true, promptLevel = 0))
        assertTrue(runner.tapped("fill_blank", correct = true, exerciseId = "e2"))
        assertEquals(Phase.EXPLAIN, runner.state.phase)
    }

    @Test
    fun `GT-034 识读路径 record_result 不虚增 WRITE（fill_blank与audio_choice映射 RECOGNIZE）`() {
        // 识读优先：选字填空判对 → phase=independent_write + exercise_type=fill_blank → RECOGNIZE，WRITE 不增长
        val ro = ReplayRunner().startSession("家", LearningPath.READ_ONLY)
        ro.advance(); ro.voice(VoiceIntent.RECOGNIZED); ro.advance()
        ro.writing("guided_write", ok = true, promptLevel = 3)
        ro.tapped("fill_blank", correct = true, exerciseId = "e2")
        ro.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("record_result", mapOf(
            "char" to "家",
            "result" to mapOf("phase" to "independent_write", "score" to 1.0, "prompt_level" to "none",
                "exercise_type" to "fill_blank", "idempotency_key" to "gt034-ro-fill-blank"),
        )))))
        assertEquals(0, ro.store.getCharacter("家").masteryWrite, "read_only 判对不练书写：WRITE 不得虚增")
        assertEquals(1, ro.store.getCharacter("家").masteryRecognize, "选字填空练识读：RECOGNIZE 增长")

        // 识主写辅：听音选字判对 → audio_choice 同样映射 RECOGNIZE
        val rp = ReplayRunner().startSession("家", LearningPath.READ_PRIMARY)
        rp.advance(); rp.voice(VoiceIntent.RECOGNIZED); rp.advance()
        rp.writing("guided_write", ok = true, promptLevel = 3)
        rp.tapped("select", correct = true, exerciseId = "e1")
        rp.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("record_result", mapOf(
            "char" to "家",
            "result" to mapOf("phase" to "independent_write", "score" to 1.0, "prompt_level" to "none",
                "exercise_type" to "audio_choice", "idempotency_key" to "gt034-rp-audio-choice"),
        )))))
        assertEquals(0, rp.store.getCharacter("家").masteryWrite)
        assertEquals(1, rp.store.getCharacter("家").masteryRecognize)

        // 对照组：书写通道独立写判对 → 仍映射 WRITE（P1-7 §4：L0 无提示成功 → 书写等级 2）
        // review-11 P1-1.2：裁决用本地提示等级——L0 语义需本地 state.promptLevel=0（模型字段仅展示）
        val wp = ReplayRunner().startSession("家", LearningPath.WRITE_PARALLEL)
        wp.advance(); wp.voice(VoiceIntent.RECOGNIZED); wp.advance()
        wp.writing("guided_write", ok = true, promptLevel = 3)
        wp.configureState(wp.state.copy(promptLevel = 0))
        wp.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("record_result", mapOf(
            "char" to "家",
            "result" to mapOf("phase" to "independent_write", "score" to 0.9, "prompt_level" to "none",
                "idempotency_key" to "gt020-drive-write"),
        )))))
        assertEquals(2, wp.store.getCharacter("家").masteryWrite)
        assertEquals(0, wp.store.getCharacter("家").masteryRecognize)
    }

    @Test
    fun `GT-009 非法控制动作被拒绝`() {
        val runner = ReplayRunner().startSession("家")
        // introduce 阶段（非 decide）：complete_character 不在 allowed_actions → 拒绝（§6.2）
        assertFalse(runner.control("complete_character"))
        // 非法动作名（协议外）始终拒绝
        assertFalse(runner.control("drop_database"))
    }

    // ---- review-11 P1-1.3：复习轮 record_result 必须带本地作答证据 ----

    @Test
    fun `复习轮出题回合直接 record_result 被拒绝（无作答证据）`() {
        val runner = ReplayRunner().startSession("家")
        runner.reviewQueue.add("家")
        runner.startReview()
        runner.advanceReview()   // recall → assess（beginAttempt 语义：无本地作答证据）
        // 模型在出题回合（assess）直接 record_result——无本地判题/书写证据 → 拒绝落库
        runner.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("record_result", mapOf(
            "char" to "家",
            "result" to mapOf("phase" to "assess", "score" to 1.0, "prompt_level" to "none", "idempotency_key" to "rev-no-evidence"),
        )))))
        assertTrue(runner.rejectedCalls.contains("record_result"), "无作答证据不得落分")
        assertEquals(0, runner.store.results.size)
        assertFalse(runner.reviewAnswered, "门禁不得被无证据 record_result 打开")
    }

    @Test
    fun `复习轮判题后 record_result 落库（本地作答证据通过）`() {
        val runner = ReplayRunner().startSession("家")
        runner.reviewQueue.add("家")
        runner.startReview()
        runner.advanceReview()   // recall → assess
        runner.tapped("家", correct = true, exerciseId = "e1")   // 本地判题（作答证据 + 门禁）
        runner.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("record_result", mapOf(
            "char" to "家",
            "result" to mapOf("phase" to "assess", "score" to 1.0, "prompt_level" to "none", "idempotency_key" to "rev-evidence"),
        )))))
        assertFalse(runner.rejectedCalls.contains("record_result"))
        assertEquals(1, runner.store.results.size)
        assertTrue(runner.reviewAnswered)
    }

    @Test
    fun `复习轮书写事件即本地作答证据（GT-053 链路）`() {
        val runner = ReplayRunner().startSession("家")
        runner.store.upsertCharacter(com.literacy.agent.model.CharacterRecord("家", masteryRecognize = 2))
        runner.reviewQueue.add("家")
        runner.startReview()
        // GT-053 语义：强化阶段由 lesson_state 直置（assess 判题门禁在真实流里由判题点开，
        // 此处聚焦「书写事件即证据」——record_result 落 reinforce 校验 attempt.score）
        runner.configureState(runner.state.copy(reviewStage = com.literacy.agent.model.ReviewStage.REINFORCE))
        // 独立写失败（本地评估）：绑定作答证据
        runner.writing("independent_write", ok = false, promptLevel = 4, score = 0.4)
        runner.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("record_result", mapOf(
            "char" to "家",
            "result" to mapOf("phase" to "reinforce", "score" to 0.4, "prompt_level" to "3", "idempotency_key" to "rev-write-evidence"),
        )))))
        assertFalse(runner.rejectedCalls.contains("record_result"), "书写评估是本地作答证据")
        assertEquals(1, runner.store.results.size)
        // 复习出错 + ≥L3 提示 → 降一级（GT-053）
        assertEquals(1, runner.store.getCharacter("家").masteryRecognize)
    }

    // ---- review-11 批A：ASSESS 判题后补记容错（review-10 P1-3 承诺）----

    @Test
    fun `ASSESS 判题后推进到 REINFORCE 补记 assess 成功（advanceReview 清空 attempt 后的容错）`() {
        val runner = ReplayRunner().startSession("家")
        runner.reviewQueue.add("家")
        runner.startReview()
        runner.advanceReview()   // recall → assess
        runner.tapped("家", correct = true, exerciseId = "e1")   // 本地判题（reviewAnswered 置位）
        // App 层 advanceReview 的 beginAttempt()（无参）把 attempt 覆盖为 null（新幂等键）——
        // 模型未当场落库时，补记 assess 的证据只能靠 reviewAnswered
        runner.configureState(runner.state.copy(attempt = null))
        runner.advanceReview()   // assess → reinforce（reviewAnswered 放行门禁）
        // 模型补记判题（GT-053：判题延迟落库）——不得被“attempt.score==null”门禁误拒
        runner.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("record_result", mapOf(
            "char" to "家",
            "result" to mapOf("phase" to "assess", "score" to 1.0, "prompt_level" to "none", "idempotency_key" to "rev-backfill"),
        )))))
        assertFalse(runner.rejectedCalls.contains("record_result"), "补记 assess 不得被门禁误拒（review-10 P1-3 容错路径）")
        assertEquals(1, runner.store.results.size)
        assertEquals("assess", runner.store.results.single().phase)
        assertTrue(runner.reviewAnswered)
    }

    // ---- review-11 P1-7：skip 落库 score=null + 跳过原因保存 ----

    @Test
    fun `skip 落库 score 为 null 且保存跳过原因`() {
        val runner = ReplayRunner().startSession("家")
        runner.configureState(runner.state.copy(attempt = com.literacy.agent.model.AttemptContext(
            phase = "skip",
        )))
        // 模型调 skip_character 带原因 → reason 进入 attempt.issues
        runner.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("skip_character", mapOf("reason" to "学生主动跳过")))))
        runner.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("record_result", mapOf(
            "char" to "家",
            "result" to mapOf("phase" to "skip", "idempotency_key" to "skip-1"),
        )))))
        assertFalse(runner.rejectedCalls.contains("record_result"))
        assertEquals(1, runner.store.results.size)
        val rec = runner.store.results.single()
        assertEquals("skip", rec.phase)
        assertEquals(null, rec.score, "skip 协议要求 score=null（不得合成 0.0）")
        assertEquals(listOf("学生主动跳过"), rec.issues, "跳过原因随记录落库")
    }

    @Test
    fun `语音跳过路径前置 recognize attempt 仍成功落库且不污染 mastery streak`() {
        val runner = ReplayRunner().startSession("家")
        // 语音认读失败已绑本地 attempt(phase=recognize, score=0.0)——模型随后决定跳过：
        // skip_character 必须强制 phase=skip，否则 record_result(skip) 被 phase 与本地事件不符拒绝
        runner.configureState(runner.state.copy(attempt = com.literacy.agent.model.AttemptContext(
            phase = "recognize",
            score = 0.0,
            dimension = com.literacy.agent.model.Dimension.RECOGNIZE,
        )))
        runner.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("skip_character", mapOf("reason" to "学生主动跳过")))))
        runner.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("record_result", mapOf(
            "char" to "家",
            "result" to mapOf("phase" to "skip", "idempotency_key" to "skip-recog"),
        )))))
        assertFalse(runner.rejectedCalls.contains("record_result"), "skip 不得被 phase 与本地事件不符拒绝")
        val rec = runner.store.results.single()
        assertEquals("skip", rec.phase)
        assertEquals(null, rec.score, "skip 协议要求 score=null")
        assertEquals(listOf("学生主动跳过"), rec.issues)
        // 不污染 mastery/streak（skip 不裁决不排期）
        val char = runner.store.getCharacter("家")
        assertEquals(0, char.masteryRecognize)
        assertEquals(0, char.streakSuccess(com.literacy.agent.model.Dimension.RECOGNIZE))
        assertEquals(0, char.streakErrors(com.literacy.agent.model.Dimension.RECOGNIZE))
    }

    // ---- review-11 P2-2：主动意图目标语义 ----

    @Test
    fun `REQUEST_NEW_CHAR 解析文本目标字优先于选择器`() {
        val runner = ReplayRunner().startSession("家")
        var selectorCalled = false
        runner.nextCharSelector = { selectorCalled = true; "药" }
        runner.voice(VoiceIntent.REQUEST_NEW_CHAR, "我想学'药'字")
        assertEquals("药", runner.state.char, "文本中明确的目标字应优先")
        assertFalse(selectorCalled, "有目标字时不调选择器")
        assertEquals(Phase.INTRODUCE, runner.state.phase)
    }

    @Test
    fun `REQUEST_NEW_CHAR 无目标字时回退选择器`() {
        val runner = ReplayRunner().startSession("家")
        runner.nextCharSelector = { "药" }
        runner.voice(VoiceIntent.REQUEST_NEW_CHAR, "我们换一个字吧")
        assertEquals("药", runner.state.char)
        assertEquals(Phase.INTRODUCE, runner.state.phase)
    }

    @Test
    fun `SWITCH_PATH 重复不写字确定映射 READ_ONLY 不回书写路径`() {
        val runner = ReplayRunner().startSession("家")
        runner.voice(VoiceIntent.SWITCH_PATH, "我今天不写字了")
        assertEquals(LearningPath.READ_ONLY, runner.state.learningPath)
        // 重复"不写字"不得回到书写路径（三态循环已废弃）
        runner.voice(VoiceIntent.SWITCH_PATH, "不写字")
        assertEquals(LearningPath.READ_ONLY, runner.state.learningPath)
    }

    // ---- show_sentence 校验 sentence_text ----

    @Test
    fun `show_sentence 合法调用（sentence_text 参数）不被拒绝`() {
        val runner = ReplayRunner().startSession("家")
        runner.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("show_sentence", mapOf(
            "sentence_text" to "我家有三口人。",
        )))))
        assertFalse(runner.rejectedCalls.contains("show_sentence"), "sentence_text 是 canonical 参数，不应拒绝")
        assertTrue(runner.executedToolCalls.contains("show_sentence"))
    }

    // ---- review-09 W7：strict 生产路径（strictResultValidation=true，P1-7）----

    private fun llmRecordResult(runner: ReplayRunner, key: String, phase: String = "recognize") =
        runner.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("record_result", mapOf(
            "char" to "家",
            "result" to mapOf("phase" to phase, "score" to 1.0, "prompt_level" to "none", "idempotency_key" to key),
        )))))

    @Test
    fun `strict 模式无 App 签发幂等键拒绝模型自造 key（P1-7 生产路径）`() {
        val runner = ReplayRunner().startSession("家")
        runner.strictResultValidation = true
        llmRecordResult(runner, "self-made-key")   // 无真实本地尝试，模型自造 key
        assertTrue(runner.rejectedCalls.contains("record_result"), "strict 下无 App 签发 key 必须拒绝（模型不得自造 key 改掌握度）")
        assertEquals(0, runner.store.results.size)
        assertTrue(runner.rejectReasons.any { it.contains("幂等键") })
    }

    @Test
    fun `strict 模式 key 不匹配 App 签发幂等键被拒`() {
        val runner = ReplayRunner().startSession("家")
        runner.strictResultValidation = true
        runner.configureState(runner.state.copy(idempotencyKey = "app-key-1"))
        llmRecordResult(runner, "different-key")
        assertTrue(runner.rejectedCalls.contains("record_result"), "key 必须逐字回传 App 签发值")
        assertEquals(0, runner.store.results.size)
    }

    @Test
    fun `strict 模式学习轮缺少本地尝试证据（attempt）被拒`() {
        val runner = ReplayRunner().startSession("家")
        runner.strictResultValidation = true
        // 有 App 签发 key，但无本地事件绑定 attempt（模型在无作答回合直接落库）
        runner.configureState(runner.state.copy(idempotencyKey = "app-key-1", attempt = null))
        llmRecordResult(runner, "app-key-1")
        assertTrue(runner.rejectedCalls.contains("record_result"), "strict 下学习轮必须有本地尝试证据")
        assertEquals(0, runner.store.results.size)
    }

    @Test
    fun `strict 模式有 key 有本地 attempt 落库成功（生产正路径）`() {
        val runner = ReplayRunner().startSession("家")
        runner.strictResultValidation = true
        runner.configureState(runner.state.copy(
            idempotencyKey = "app-key-1",
            attempt = com.literacy.agent.model.AttemptContext(phase = "recognize", score = 1.0, dimension = com.literacy.agent.model.Dimension.RECOGNIZE, promptLevel = 0),
        ))
        llmRecordResult(runner, "app-key-1")
        assertFalse(runner.rejectedCalls.contains("record_result"))
        assertEquals(1, runner.store.results.size)
    }

    @Test
    fun `strict 模式复习 REINFORCE 无本阶段作答证据被拒（P1-9）`() {
        val runner = ReplayRunner().startSession("家")
        runner.strictResultValidation = true
        runner.reviewQueue.add("家")
        runner.startReview()
        runner.advanceReview()   // recall → assess
        runner.tapped("fill_blank", correct = true, exerciseId = "e1")   // 判题证据（reviewAnswered）
        runner.advanceReview()   // assess → reinforce（advanceReview 不开新 attempt；App 层 beginAttempt 置 null）
        runner.configureState(runner.state.copy(attempt = null, idempotencyKey = "rev-key-1"))
        // 模型在 REINFORCE 落 reinforce——旧 ASSESS 判题证据不得被下一阶段借用（P1-9）
        llmRecordResult(runner, "rev-key-1", phase = "reinforce")
        assertTrue(runner.rejectedCalls.contains("record_result"), "strict 下 reinforce 必须绑定本阶段作答证据")
        assertEquals(0, runner.store.results.size)
    }

    @Test
    fun `宽松模式复习 REINFORCE 无本阶段证据可落库（W4：纯讲解 reinforce 不被误拒）`() {
        val runner = ReplayRunner().startSession("家")
        runner.strictResultValidation = false   // mock/用例宽松模式
        runner.reviewQueue.add("家")
        runner.startReview()
        runner.advanceReview()   // recall → assess
        runner.tapped("fill_blank", correct = true, exerciseId = "e1")   // 判题证据（reviewAnswered 放行首道门禁）
        runner.advanceReview()   // assess → reinforce
        runner.configureState(runner.state.copy(attempt = null, idempotencyKey = "rev-key-1"))
        // 纯讲解（无本阶段练习）的 reinforce 落库：宽松模式不得被 P1-9 门禁误拒
        llmRecordResult(runner, "rev-key-1", phase = "reinforce")
        assertFalse(runner.rejectedCalls.contains("record_result"), "W4：宽松模式 reinforce 无本阶段证据应放行")
        assertEquals(1, runner.store.results.size)
        assertEquals("reinforce", runner.store.results.single().phase)
    }

    // ---- 同字同轮 ASSESS 重复记账拒绝（key A 落库后新 key 补记不得再裁决）----

    @Test
    fun `ASSESS 已落库后 REINFORCE 用新 key 补记 assess 被拒（防重复记账）`() {
        val runner = ReplayRunner().startSession("家")
        runner.reviewQueue.add("家")
        runner.startReview()
        runner.advanceReview()   // recall → assess
        runner.tapped("家", correct = true, exerciseId = "e1")   // 本地判题（reviewAnswered 置位）
        // 模型在 ASSESS 当场落库（key A）
        llmRecordResult(runner, "rev-key-A", phase = "assess")
        assertFalse(runner.rejectedCalls.contains("record_result"))
        assertEquals(1, runner.store.results.size)
        // App 层 advanceReview：新幂等键 + attempt 清空 → 推进到 REINFORCE
        runner.configureState(runner.state.copy(
            attempt = null,
            idempotencyKey = "rev-key-B",
        ))
        runner.advanceReview()   // assess → reinforce
        // 模型用新 key 补记同一次判题 → 同一作答不得重复记账
        llmRecordResult(runner, "rev-key-B", phase = "assess")
        assertTrue(runner.rejectedCalls.contains("record_result"), "同字同轮 ASSESS 已落库，新 key 补记必须拒绝")
        assertEquals(1, runner.store.results.size, "重复记账不得再落一行")
        assertTrue(runner.rejectReasons.any { it.contains("已落库") })
    }

    @Test
    fun `ASSESS 阶段同字重复落库也拒绝（同轮只允许一次判题记账）`() {
        val runner = ReplayRunner().startSession("家")
        runner.reviewQueue.add("家")
        runner.startReview()
        runner.advanceReview()   // recall → assess
        runner.tapped("家", correct = true, exerciseId = "e1")
        llmRecordResult(runner, "rev-key-A", phase = "assess")
        assertFalse(runner.rejectedCalls.contains("record_result"))
        // 同一 ASSESS 阶段再落一次（不同 key）——同轮同字只允许一次判题
        llmRecordResult(runner, "rev-key-A2", phase = "assess")
        assertTrue(runner.rejectedCalls.contains("record_result"), "ASSESS 阶段同字重复记账必须拒绝")
        assertEquals(1, runner.store.results.size)
    }

    @Test
    fun `补记 assess 仍允许（该字该轮未落库过——review-10 P1-3 容错不破坏）`() {
        val runner = ReplayRunner().startSession("家")
        runner.reviewQueue.add("家")
        runner.startReview()
        runner.advanceReview()   // recall → assess
        runner.tapped("家", correct = true, exerciseId = "e1")   // 本地判题（reviewAnswered 置位）
        runner.configureState(runner.state.copy(attempt = null))
        runner.advanceReview()   // assess → reinforce
        // 未落库过的补记（GT-053 延迟落库）：仍放行
        llmRecordResult(runner, "rev-backfill", phase = "assess")
        assertFalse(runner.rejectedCalls.contains("record_result"), "未落库过的补记 assess 仍应放行")
        assertEquals(1, runner.store.results.size)
        assertEquals("assess", runner.store.results.single().phase)
    }

    // ---- score NaN 拒绝（NaN 与 0/1 比较恒 false 绕过范围校验）----

    @Test
    fun `score NaN 被 record_result 校验拒绝`() {
        val runner = ReplayRunner().startSession("家")
        runner.configureState(runner.state.copy(
            idempotencyKey = "app-key-nan",
            attempt = com.literacy.agent.model.AttemptContext(
                phase = "recognize", score = 1.0, dimension = com.literacy.agent.model.Dimension.RECOGNIZE, promptLevel = 0,
            ),
        ))
        runner.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("record_result", mapOf(
            "char" to "家",
            "result" to mapOf("phase" to "recognize", "score" to Double.NaN, "prompt_level" to "none", "idempotency_key" to "app-key-nan"),
        )))))
        assertTrue(runner.rejectedCalls.contains("record_result"), "NaN 分数必须拒绝（不进裁决/落库）")
        assertEquals(0, runner.store.results.size)
    }
}
