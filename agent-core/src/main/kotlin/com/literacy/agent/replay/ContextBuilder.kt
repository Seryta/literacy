package com.literacy.agent.replay

import com.literacy.agent.model.LearningPath
import com.literacy.agent.model.NamePlan

/**
 * Prompt 上下文构建（AGENT-PROTOCOL §2 注入结构 + §9 隐私边界）。
 *
 * 隐私规则（§9）：
 * - `<learner_profile>` 只传 display_name（称呼），不传 learner_name（真实姓名）
 * - `<name_plan>` 只传目标字列表和阶段，不传完整姓名原文
 * - 原始音频 / 手写轨迹永不上传
 */
class ContextBuilder {

    /** 学习者档案注入（不含 learner_name）。 */
    fun learnerProfile(displayName: String, learningPath: LearningPath): String =
        "称呼：$displayName；学习路径：${learningPath.name.lowercase()}"

    /** name_plan 注入（不含 full_name）。 */
    fun namePlan(plan: NamePlan): String = buildString {
        append("姓名目标字：${plan.targetChars.joinToString("、")}")
        if (plan.recognitionReady) append("；能认出")
        if (plan.guidedWritingReady) append("；有提示能写")
        if (plan.independentWritingReady) append("；无提示能写")
        if (plan.signingReady) append("；可签字")
    }
}
