package com.literacy.agent.replay

/**
 * 安全护栏（AGENT-PROTOCOL §10 越界内容过滤 + SYSTEM-PROMPT 安全边界）。
 *
 * Agent 返回的文本可能混入越界内容（如尝试操作系统设置）。本地按句过滤后再交给 TTS：
 * 含越界模式的句子丢弃，教学部分保留（GT-014），并向下一 turn 注入警告。
 */
object SafetyGuard {

    /** 越界模式：系统操作 / 隐私索取 / 诱导等。命中该句即过滤。 */
    private val outOfBoundPatterns = listOf(
        "改系统设置", "系统设置", "删除", "支付", "转账", "密码", "验证码",
        "你是我", "帮我改", "无视", "绕过",
    )

    private val sentenceSplit = Regex("(?<=[。！？；!?;])")

    /** 过滤文本。返回 Pair(过滤后可 TTS 文本, 是否发生过滤)。 */
    fun filter(text: String): Pair<String, Boolean> {
        val sentences = text.split(sentenceSplit).map { it.trim() }.filter { it.isNotEmpty() }
        val kept = sentences.filter { s -> outOfBoundPatterns.none { it in s } }
        val filtered = kept.joinToString("")
        return filtered to (filtered != text.trim())
    }
}
