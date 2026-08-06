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

    // ---- 残余修复（验收 P1）：REINFORCE 作答后补记 assess 用冻结快照（不串强化分数/题型）----

    @Test
    fun `REINFORCE 作答后补记 assess 仍用 ASSESS 冻结快照`() {
        val runner = ReplayRunner().startSession("家")
        runner.reviewQueue.add("家")
        runner.startReview()
        runner.advanceReview()   // recall → assess
        // ASSESS 判题（错误 0.0，冻结快照）
        runner.configureState(runner.state.copy(attempt = com.literacy.agent.model.AttemptContext(
            phase = "assess", exerciseType = "audio_choice",
            dimension = com.literacy.agent.model.Dimension.RECOGNIZE,
        )))
        runner.tapped("家", correct = false, exerciseId = "e1")   // 0.0
        runner.advanceReview()   // assess → reinforce（attempt 清空）
        // REINFORCE 作答（新 attempt，分数 1.0 正确——不得串进 assess 补记）
        runner.configureState(runner.state.copy(attempt = com.literacy.agent.model.AttemptContext(
            phase = "reinforce", exerciseType = "guided_write",
            dimension = com.literacy.agent.model.Dimension.WRITE,
        )))
        runner.tapped("家", correct = true, exerciseId = "e2")
        // 模型补记 assess（此时 attempt 是 reinforce 且 score=1.0 非 null）
        runner.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("record_result", mapOf(
            "char" to "家",
            "result" to mapOf("phase" to "assess", "score" to 1.0, "prompt_level" to "none", "idempotency_key" to "rev-frozen"),
        )))))
        assertFalse(runner.rejectedCalls.contains("record_result"), "补记 assess 不被拒：" + runner.rejectReasons.joinToString("; "))
        val rec = runner.store.getCharacter("家")
        // ASSESS 冻结快照 0.0（本地判题真值）——强化阶段的 1.0 不得串入 assess
        assertEquals(0.0, rec.masteryRecognize.toDouble(), 1e-9, "assess 落库必须用冻结的 0.0，不是强化 1.0")
        // RECOGNIZE（audio_choice）不串 WRITE 强化题型
        assertEquals(0, rec.masteryWrite, "强化 WRITE 不得串入 assess")
    }

    // ---- 验收 P1-1：补记 assess 用冻结原始幂等键（不占用强化 key，强化结果不被幂等误吞）----

    @Test
    fun `补记 assess 用冻结 key 不占用强化 key，reinforce 落库不被幂等误吞`() {
        val runner = ReplayRunner().startSession("家")
        runner.strictResultValidation = true   // 生产路径：key 必须回传 App 签发值
        runner.reviewQueue.add("家")
        runner.startReview()
        runner.advanceReview()   // recall → assess
        // ASSESS 判题：App 签发 key A，本地判题（冻结快照含 key A + 判题时提示等级）
        runner.configureState(runner.state.copy(
            idempotencyKey = "app-key-A",
            attempt = com.literacy.agent.model.AttemptContext(
                phase = null, exerciseType = "audio_choice",
                dimension = com.literacy.agent.model.Dimension.RECOGNIZE,
            ),
        ))
        runner.tapped("家", correct = false, exerciseId = "e1")   // 0.0，冻结快照
        // App 层 advanceReview：新 key B + 强化作答（本阶段证据）→ REINFORCE
        runner.configureState(runner.state.copy(
            idempotencyKey = "app-key-B",
            attempt = com.literacy.agent.model.AttemptContext(
                phase = "reinforce", score = 1.0, exerciseType = "guided_write",
                dimension = com.literacy.agent.model.Dimension.WRITE,
            ),
        ))
        runner.advanceReview()   // assess → reinforce
        // 模型补记 assess（回传当前注入 key B）——落库必须用冻结 key A，不占用 key B
        runner.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("record_result", mapOf(
            "char" to "家",
            "result" to mapOf("phase" to "assess", "score" to 1.0, "prompt_level" to "none", "idempotency_key" to "app-key-B"),
        )))))
        assertFalse(runner.rejectedCalls.contains("record_result"), "补记 assess 不被拒：" + runner.rejectReasons.joinToString("; "))
        assertEquals(1, runner.store.results.size)
        assertEquals(0, runner.store.getCharacter("家").masteryWrite, "assess 冻结 audio_choice 不串 WRITE")
        // 强化结果（同 key B）随后到达：不得被全局去重静默丢弃
        runner.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("record_result", mapOf(
            "char" to "家",
            "result" to mapOf("phase" to "reinforce", "score" to 1.0, "prompt_level" to "none", "idempotency_key" to "app-key-B"),
        )))))
        assertFalse(runner.rejectedCalls.contains("record_result"), "强化结果不被幂等误吞：" + runner.rejectReasons.joinToString("; "))
        assertEquals(2, runner.store.results.size, "assess + reinforce 都应落库")
        val assess = runner.store.results.first { it.phase == "assess" }
        assertEquals("app-key-A", assess.idempotencyKey, "补记 assess 用冻结原始 key（判题时签发）")
        assertEquals("3", assess.promptLevel, "补记 assess 用冻结快照的判题提示等级（不取当前强化尝试）")
        assertEquals(emptyList(), assess.issues)
        val reinforce = runner.store.results.first { it.phase == "reinforce" }
        assertEquals("app-key-B", reinforce.idempotencyKey, "强化结果用当前 key 正常落库")
    }

    // ---- 残余修复（验收 P1）：RECALL 阶段点击不开 ASSESS 推进门禁 ----

    @Test
    fun `RECALL 阶段点击不打开 ASSESS 门禁`() {
        val runner = ReplayRunner().startSession("家")
        runner.reviewQueue.add("家")
        runner.startReview()
        // 当前在 RECALL：点击（异常/历史题）不得产生 ASSESS 证据
        runner.configureState(runner.state.copy(attempt = com.literacy.agent.model.AttemptContext(
            phase = "recall", exerciseType = "audio_choice",
            dimension = com.literacy.agent.model.Dimension.RECOGNIZE,
        )))
        runner.tapped("家", correct = true, exerciseId = "e1")
        assertFalse(runner.reviewAnswered, "RECALL 点击不得置 ASSESS 证据")
        assertEquals(null, runner.reviewAnsweredAttempt)
        // 没有证据时 ASSESS→REINFORCE 被门禁挡住
        runner.advanceReview()   // recall → assess（RECALL 无门禁）
        assertEquals(com.literacy.agent.model.ReviewStage.ASSESS, runner.state.reviewStage)
        assertEquals(com.literacy.agent.model.ReviewStage.ASSESS, runner.advanceReview(), "无 ASSESS 证据不得推进 REINFORCE")
    }

    @Test
    fun `进入复习和切换复习字会清理学习阶段与 UI 工具`() {
        val runner = ReplayRunner().startSession("旧")
        runner.configureState(runner.state.copy(
            phase = Phase.DEMONSTRATE,
            attempt = com.literacy.agent.model.AttemptContext(phase = "demonstrate"),
            idempotencyKey = "old-key",
        ))
        runner.recentUiTools += com.literacy.agent.model.ToolCall("show_character")
        runner.reviewQueue.addAll(listOf("家", "国"))

        assertTrue(runner.startReview())
        assertEquals(null, runner.state.phase)
        assertEquals(null, runner.state.attempt)
        assertEquals(null, runner.state.idempotencyKey)
        assertTrue(runner.recentUiTools.isEmpty())

        runner.advanceReview()
        runner.configureState(runner.state.copy(reviewStage = com.literacy.agent.model.ReviewStage.NEXT))
        runner.recentUiTools += com.literacy.agent.model.ToolCall("show_options")
        assertTrue(runner.nextReviewChar())
        assertEquals("国", runner.state.char)
        assertEquals(null, runner.state.phase)
        assertTrue(runner.recentUiTools.isEmpty())
    }

    // ---- 残余修复（验收 P1）：复习中插单（REQUEST_NEW_CHAR）清空旧字复习证据 ----

    @Test
    fun `复习中插单清空旧字复习证据并退出复习`() {
        val runner = ReplayRunner().startSession("家")
        runner.reviewQueue.add("家")
        runner.startReview()
        runner.advanceReview()   // recall → assess
        // ASSESS 判题（旧字证据）
        runner.configureState(runner.state.copy(attempt = com.literacy.agent.model.AttemptContext(
            phase = "assess", exerciseType = "audio_choice",
            dimension = com.literacy.agent.model.Dimension.RECOGNIZE,
        )))
        runner.tapped("家", correct = true, exerciseId = "e1")
        assertTrue(runner.reviewAnswered)
        // 插单换字（"我想学'药'字"）：应清证据 + 退复习
        runner.voice(com.literacy.agent.model.VoiceIntent.REQUEST_NEW_CHAR, "我想学药字")
        assertFalse(runner.reviewAnswered, "换字后旧字证据必须清空")
        assertEquals(null, runner.reviewAnsweredAttempt)
        assertEquals(com.literacy.agent.model.Mode.LEARNING, runner.state.mode)
        assertEquals(null, runner.state.reviewStage)
        assertEquals("药", runner.state.char)
        // 新字补记 assess（无本地证据）必须被拒（旧字证据已清，不能放行）
        runner.configureState(runner.state.copy(
            mode = com.literacy.agent.model.Mode.REVIEW,
            reviewStage = com.literacy.agent.model.ReviewStage.REINFORCE,
        ))
        runner.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("record_result", mapOf(
            "char" to "药",
            "result" to mapOf("phase" to "assess", "score" to 1.0, "prompt_level" to "none", "idempotency_key" to "rev-xchar"),
        )))))
        assertTrue(runner.rejectedCalls.contains("record_result"), "换字后旧字证据不得放行新字补记")
    }

    // ---- 残余修复（验收 P1）：补记 assess 保留本地题型/维度（audio_choice 不误写 WRITE）----

    @Test
    fun `补记 assess 保留本地题型与维度（audio_choice 不误写 WRITE）`() {
        val runner = ReplayRunner().startSession("家")
        runner.reviewQueue.add("家")
        runner.startReview()
        runner.advanceReview()   // recall → assess
        // 本地判题：audio_choice 题型（识读），正确 → 1.0
        runner.configureState(runner.state.copy(attempt = com.literacy.agent.model.AttemptContext(
            phase = "assess",
            exerciseType = "audio_choice",
            dimension = com.literacy.agent.model.Dimension.RECOGNIZE,
        )))
        runner.tapped("家", correct = true, exerciseId = "e1")
        runner.configureState(runner.state.copy(attempt = null))   // advanceReview 清 attempt（新 key）
        runner.advanceReview()   // assess → reinforce
        // 模型补记 assess（模型不带 exercise_type——验证退本地而非模型/最弱维度）
        runner.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("record_result", mapOf(
            "char" to "家",
            "result" to mapOf("phase" to "assess", "score" to 1.0, "prompt_level" to "none", "idempotency_key" to "rev-backfill-dim"),
        )))))
        assertFalse(runner.rejectedCalls.contains("record_result"), "补记 assess 不被拒")
        val result = runner.store.results.single()
        assertEquals("assess", result.phase)
        // 题型必须是本地绑定的 audio_choice（补记不退回模型题型/最弱维度）
        assertEquals("audio_choice", result.exerciseType)
        // 掌握度更新到 RECOGNIZE（识读正确 +1），WRITE 不得被误提升
        val rec = runner.store.getCharacter("家")
        assertEquals(1, rec.masteryRecognize)
        assertEquals(0, rec.masteryWrite, "audio_choice 补记不得误更新 WRITE")
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

    // ---- 最终验收 批次A：复习书写链路（P1-1 门禁/P1-3 答案/P2-5 单 phase/P1-4 换字等级）----

    private fun reviewRunnerWith(char: String = "家") = ReplayRunner().startSession(char).also {
        it.reviewQueue.addAll(listOf("家", "的"))
        it.startReview()
    }

    @Test
    fun `复习 RECALL 拒绝字形拼音与多选工具（不提前变多选识别）`() {
        val runner = reviewRunnerWith()   // RECALL
        runner.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(
            com.literacy.agent.model.ToolCall("show_character", mapOf("char" to "家")),
            com.literacy.agent.model.ToolCall("show_pinyin", mapOf("char" to "家")),
            com.literacy.agent.model.ToolCall("show_options", mapOf("exercise_id" to "e1")),
        )))
        assertTrue(runner.rejectedCalls.contains("show_character"), "RECALL 提取练习不得展示字形")
        assertTrue(runner.rejectedCalls.contains("show_pinyin"), "RECALL 不得展示拼音")
        assertTrue(runner.rejectedCalls.contains("show_options"), "RECALL 自由回忆不得提前变多选识别（P2-6）")
        assertFalse(runner.recentUiTools.any { it.name == "show_character" }, "被拒工具不得进入 UI 渲染源")
        assertFalse(runner.recentUiTools.any { it.name == "show_options" })
    }

    @Test
    fun `复习 ASSESS 拒绝字形拼音但允许 show_options 出题`() {
        val runner = reviewRunnerWith()
        runner.advanceReview()   // recall → assess
        runner.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(
            com.literacy.agent.model.ToolCall("show_character", mapOf("char" to "家")),
            com.literacy.agent.model.ToolCall("show_pinyin", mapOf("char" to "家")),
            com.literacy.agent.model.ToolCall("show_options", mapOf("exercise_id" to "e1")),
        )))
        assertTrue(runner.rejectedCalls.contains("show_character"), "ASSESS 判题不泄露字形")
        assertTrue(runner.rejectedCalls.contains("show_pinyin"), "ASSESS 判题不泄露拼音")
        assertFalse(runner.rejectedCalls.contains("show_options"), "ASSESS 出题判题必须允许 show_options")
        assertTrue(runner.recentUiTools.any { it.name == "show_options" })
    }

    @Test
    fun `复习 REINFORCE 保留字形拼音教学提示`() {
        val runner = reviewRunnerWith()
        runner.configureState(runner.state.copy(reviewStage = com.literacy.agent.model.ReviewStage.REINFORCE))
        runner.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(
            com.literacy.agent.model.ToolCall("show_character", mapOf("char" to "家")),
            com.literacy.agent.model.ToolCall("show_pinyin", mapOf("char" to "家")),
        )))
        assertFalse(runner.rejectedCalls.contains("show_character"), "REINFORCE 再学保留字形提示")
        assertFalse(runner.rejectedCalls.contains("show_pinyin"))
        assertTrue(runner.recentUiTools.any { it.name == "show_character" })
    }

    @Test
    fun `复习 ASSESS 逐笔事件 phase 为 assess（与 App 层 attempt 一致）`() {
        val runner = reviewRunnerWith()
        runner.advanceReview()   // recall → assess
        runner.onStrokeFinished(1, listOf(
            com.literacy.agent.model.StrokePoint(0f, 100f),
            com.literacy.agent.model.StrokePoint(100f, 0f),
        ))
        assertEquals("assess", runner.lastWritingEval?.phase, "P2-5：复习逐笔事件必须用当前复习阶段 phase（不再写死 guided_write）")
        val ev = runner.producedEvents.last() as com.literacy.agent.model.WritingEvaluated
        assertEquals("assess", ev.phase)
    }

    @Test
    fun `复习 REINFORCE 逐笔事件 phase 为 reinforce`() {
        val runner = reviewRunnerWith()
        runner.configureState(runner.state.copy(reviewStage = com.literacy.agent.model.ReviewStage.REINFORCE))
        runner.onStrokeFinished(1, listOf(
            com.literacy.agent.model.StrokePoint(0f, 100f),
            com.literacy.agent.model.StrokePoint(100f, 0f),
        ))
        assertEquals("reinforce", runner.lastWritingEval?.phase)
        // 学习轮不受影响：跟写事件仍是 guided_write
        val learn = ReplayRunner().startSession("家")
        learn.onStrokeFinished(1, listOf(
            com.literacy.agent.model.StrokePoint(0f, 100f),
            com.literacy.agent.model.StrokePoint(100f, 0f),
        ))
        assertEquals("guided_write", learn.lastWritingEval?.phase)
    }

    @Test
    fun `复习换字读新字自己的提示等级（不沿用旧字）`() {
        val runner = ReplayRunner().startSession("旧")
        runner.store.upsertCharacter(com.literacy.agent.model.CharacterRecord("家", currentPromptLevel = 5))
        runner.store.upsertCharacter(com.literacy.agent.model.CharacterRecord("的", currentPromptLevel = 1))
        runner.reviewQueue.addAll(listOf("家", "的"))
        runner.startReview()   // 家 → L5（读自己的持久化等级，不沿用"旧"的默认 L3）
        assertEquals(5, runner.state.promptLevel, "复习字必须读自己的 currentPromptLevel")
        // 推进到 NEXT 换下一复习字
        runner.advanceReview()   // recall → assess
        runner.tapped("家", correct = true, exerciseId = "e1")   // 判题证据
        runner.advanceReview()   // assess → reinforce
        runner.advanceReview()   // reinforce → next
        assertTrue(runner.nextReviewChar())
        assertEquals("的", runner.state.char)
        assertEquals(1, runner.state.promptLevel, "换复习字必须读新字自己的等级（不沿用旧字 L5）")
    }

    @Test
    fun `插单换字读新字自己的提示等级`() {
        val runner = ReplayRunner().startSession("家")
        runner.store.upsertCharacter(com.literacy.agent.model.CharacterRecord("药", currentPromptLevel = 0))
        runner.voice(com.literacy.agent.model.VoiceIntent.REQUEST_NEW_CHAR, "我想学药字")
        assertEquals("药", runner.state.char)
        assertEquals(0, runner.state.promptLevel, "插单换字必须读新字自己的等级（不沿用旧字 L3）")
    }

    @Test
    fun `complete_character 换字读新字自己的提示等级`() {
        val runner = ReplayRunner().startSession("家")
        runner.store.upsertCharacter(com.literacy.agent.model.CharacterRecord("国", currentPromptLevel = 2))
        runner.nextCharSelector = { "国" }
        runner.configureState(runner.state.copy(
            phase = Phase.DECIDE, allowedActions = com.literacy.agent.replay.ReplayRunner.allowedFor(Phase.DECIDE),
        ))
        runner.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("complete_character"))))
        assertEquals("国", runner.state.char)
        assertEquals(Phase.INTRODUCE, runner.state.phase)
        assertEquals(2, runner.state.promptLevel, "complete_character 换字必须读新字自己的等级（不回落默认 L3）")
    }

    @Test
    fun `两字完整复习旅程 落库证据与换字目标字 promptLevel`() {
        val runner = ReplayRunner().startSession("旧")
        runner.store.upsertCharacter(com.literacy.agent.model.CharacterRecord("家", currentPromptLevel = 4))
        runner.store.upsertCharacter(com.literacy.agent.model.CharacterRecord("的", currentPromptLevel = 1))
        runner.reviewQueue.addAll(listOf("家", "的"))
        runner.startReview()
        assertEquals("家", runner.state.char)
        assertEquals(4, runner.state.promptLevel)
        // RECALL：show_options 被本地拒绝（自由回忆不变多选）
        runner.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("show_options", mapOf("exercise_id" to "q0")))))
        assertTrue(runner.rejectedCalls.contains("show_options"))
        // RECALL → ASSESS
        runner.advanceReview()
        assertEquals(com.literacy.agent.model.ReviewStage.ASSESS, runner.state.reviewStage)
        // ASSESS 听写（书写事件即本地作答证据，GT-053 链路）
        runner.writing("assess", ok = true, promptLevel = 4, score = 0.9)
        runner.configureState(runner.state.copy(idempotencyKey = "rev-k1"))
        runner.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("record_result", mapOf(
            "char" to "家",
            "result" to mapOf("phase" to "assess", "score" to 0.9, "prompt_level" to "none", "idempotency_key" to "rev-k1"),
        )))))
        assertFalse(runner.rejectedCalls.contains("record_result"), "ASSESS 听写落库不得被拒：" + runner.rejectReasons.joinToString("; "))
        // ASSESS → REINFORCE（已落库证据放行门禁）
        runner.advanceReview()
        assertEquals(com.literacy.agent.model.ReviewStage.REINFORCE, runner.state.reviewStage)
        // REINFORCE 再学书写 → 落库 reinforce
        runner.writing("reinforce", ok = true, promptLevel = 4, score = 0.9)
        runner.configureState(runner.state.copy(idempotencyKey = "rev-k2"))
        runner.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("record_result", mapOf(
            "char" to "家",
            "result" to mapOf("phase" to "reinforce", "score" to 0.9, "prompt_level" to "none", "idempotency_key" to "rev-k2"),
        )))))
        assertFalse(runner.rejectedCalls.contains("record_result"), "REINFORCE 落库不得被拒：" + runner.rejectReasons.joinToString("; "))
        // REINFORCE → NEXT → 下一字
        runner.advanceReview()
        assertEquals(com.literacy.agent.model.ReviewStage.NEXT, runner.state.reviewStage)
        assertTrue(runner.nextReviewChar())
        assertEquals("的", runner.state.char)
        assertEquals(1, runner.state.promptLevel, "第二字读自己的等级（不沿用家的 L4）")
        // 落库证据：家 assess + reinforce 两条
        assertEquals(2, runner.store.results.size)
        assertEquals(listOf("assess", "reinforce"), runner.store.results.map { it.phase })
        assertEquals("家", runner.store.results.first().char)
    }
}
