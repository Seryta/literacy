package com.literacy.agent.learning

import com.literacy.agent.model.VoiceIntent

/**
 * 语音意图本地理解（STT 转写文本 → 教学意图）。
 *
 * 真实链路：本地 STT 转写 → 本规则解析 → 教学意图；不是 STT 也不是 LLM。
 * 主动意图（用户发起）：看拼音（REQUEST_PINYIN，recognize 成功条件之一 §6.3）、
 * 插单（REQUEST_NEW_CHAR，TEACHING-STRATEGY §1.2）、切路径（SWITCH_PATH，§3.2）。
 * 认读判定（RECOGNIZED/WRONG）需要目标字上下文，由调用方在 recognize 阶段比较文本完成。
 */
class IntentResolver {

    /** 主动意图解析；无匹配返回 null（交由上下文判定认读/其他）。 */
    fun activeIntent(text: String): VoiceIntent? {
        val t = text.trim()
        return when {
            t.contains("拼音") || t.contains("怎么读") || t.contains("读什么") -> VoiceIntent.REQUEST_PINYIN
            Regex("学['\"]?([\u4e00-\u9fa5])['\"]?字").containsMatchIn(t) -> VoiceIntent.REQUEST_NEW_CHAR
            t.contains("不写字") || t.contains("手不方便") || t.contains("不想写") -> VoiceIntent.SWITCH_PATH
            else -> null
        }
    }

    /** recognize 阶段认读判定：精确匹配 + 去常见标点/空格（review-05 P2-6：STT 噪声容错）。 */
    fun isRecognitionCorrect(text: String, targetChar: String): Boolean {
        val cleaned = text.trim()
            .removeSuffix("。").removeSuffix(".").removeSuffix("！").removeSuffix("!")
            .removeSuffix("？").removeSuffix("?")
            .trim()
        return cleaned == targetChar
    }
}
