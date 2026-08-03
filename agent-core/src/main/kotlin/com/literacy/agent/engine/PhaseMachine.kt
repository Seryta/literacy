package com.literacy.agent.engine

import com.literacy.agent.model.ButtonTapped
import com.literacy.agent.model.Event
import com.literacy.agent.model.LessonState
import com.literacy.agent.model.Phase
import com.literacy.agent.model.VoiceInput
import com.literacy.agent.model.VoiceIntent
import com.literacy.agent.model.WritingEvaluated

/**
 * 阶段状态机裁决（AGENT-PROTOCOL §6）。
 *
 * 本地持有 canonical phase：Agent 只能请求，本地裁决是否允许迁移——
 * 不在 allowed_actions 内的动作静默拒绝（§6.2），成功条件未满足的 advance_phase 拒绝（§6.3）。
 */
class PhaseMachine {

    /** §6.2：动作是否在 allowed_actions 内。不在则静默拒绝，不通知 Agent。 */
    fun isActionAllowed(action: String, state: LessonState): Boolean =
        action in state.allowedActions

    /** §6.3：当前阶段的成功条件（本地判定）。null phase（无进行中教学）无成功条件。
     *  event 可空：自动通过阶段（introduce/demonstrate/record）不依赖事件。 */
    fun successCriteriaMet(state: LessonState, event: Event?): Boolean {
        val phase = state.phase ?: return false
        return when (phase) {
            // 自动通过（教学流程占位）
            Phase.INTRODUCE, Phase.DEMONSTRATE, Phase.RECORD -> true

            // 正确认出 或 主动请求看拼音
            Phase.RECOGNIZE -> when (event) {
                is VoiceInput -> event.intent == VoiceIntent.RECOGNIZED ||
                    event.intent == VoiceIntent.REQUEST_PINYIN
                else -> false
            }

            // 所有笔画跟写完成
            Phase.GUIDED_WRITE -> event is WritingEvaluated &&
                event.ok && event.phase == "guided_write"

            // 路径分支（§6.3）：识写并进=完成书写；识主写辅=听音选字；识读优先=选字填空
            Phase.INDEPENDENT_WRITE -> when (state.learningPath.check) {
                com.literacy.agent.model.IndependentCheck.WRITE ->
                    event is WritingEvaluated && event.ok && event.phase == "independent_write"
                com.literacy.agent.model.IndependentCheck.AUDIO_CHOICE,
                com.literacy.agent.model.IndependentCheck.FILL_BLANK ->
                    event is ButtonTapped && event.isCorrect == true && event.exerciseId != null
            }

            // 尝试即可（不判对错）
            Phase.EXPLAIN, Phase.SENTENCE -> event is VoiceInput

            // Agent 决策（自动通过）
            Phase.DECIDE -> true
        }
    }

    /** §6.2：advance_phase 请求裁决——只校验动作允许 + 阶段迁移，成功条件由调用方预判（review-05 P0-3）。 */
    fun advanceStep(state: LessonState): Phase? {
        if (!isActionAllowed(ControlActionRef.ADVANCE, state)) return null
        return Phase.next(state.phase ?: return null)
    }

    private object ControlActionRef {
        const val ADVANCE = "advance_phase"
    }
}
