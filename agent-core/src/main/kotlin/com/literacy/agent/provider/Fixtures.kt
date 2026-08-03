package com.literacy.agent.provider

import com.literacy.agent.model.LlmOutput
import com.literacy.agent.model.ToolCall
import java.io.File

/**
 * 真实 LLM 输出的录制与回放（golden file testing）。
 *
 * 为什么：LLM 输出非确定性（同提示词不同模型/不同次结果不同），text 断言无法
 * 用真实调用做精确断言。方案：真实 provider 跑一次 → 输出录制为 fixture →
 * 测试回放 fixture，text 断言验证真实模型输出质量，又保持确定性。
 *
 * fixture 格式（每行/整文件一个 JSON 数组）：
 * [{"text": "...", "toolCalls": [{"name": "...", "arguments": {...}}]}]
 */
object Fixtures {

    fun save(file: File, outputs: List<LlmOutput>) {
        file.parentFile?.mkdirs()
        file.writeText("[\n" + outputs.joinToString(",\n") { toJson(it) } + "\n]\n")
    }

    fun load(file: File): List<LlmOutput> {
        if (!file.exists()) return emptyList()
        val root = org.yaml.snakeyaml.Yaml().load<Any>(file.readText()) as? List<*> ?: return emptyList()
        return root.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val text = m["text"]?.toString() ?: ""
            val calls = (m["toolCalls"] as? List<*>)?.mapNotNull { c ->
                val cm = c as? Map<*, *> ?: return@mapNotNull null
                val name = cm["name"]?.toString() ?: return@mapNotNull null
                @Suppress("UNCHECKED_CAST")
                val args = (cm["arguments"] as? Map<*, *>) as? Map<String, Any?> ?: emptyMap()
                ToolCall(name, args)
            } ?: emptyList()
            LlmOutput(text, calls)
        }
    }

    private fun toJson(out: LlmOutput): String {
        val calls = out.toolCalls.joinToString(",", "[", "]") { tc ->
            """{"name":${jstr(tc.name)},"arguments":${toJson(tc.arguments)}}"""
        }
        return """{"text":${jstr(out.text)},"toolCalls":$calls}"""
    }

    private fun toJson(obj: Any?): String = when (obj) {
        null -> "null"
        is String -> jstr(obj)
        is Number, is Boolean -> obj.toString()
        is Map<*, *> -> obj.entries.joinToString(",", "{", "}") { "${jstr(it.key.toString())}: ${toJson(it.value)}" }
        is List<*> -> obj.joinToString(",", "[", "]") { toJson(it) }
        else -> jstr(obj.toString())
    }

    private fun jstr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}

/** 录制包装：委托真实 provider 并记录每次输出（供保存为 fixture）。 */
class RecorderProvider(private val delegate: LlmProvider) : LlmProvider {
    val recorded: MutableList<LlmOutput> = mutableListOf()
    override fun respond(context: Any?): LlmOutput {
        val out = delegate.respond(context)
        recorded += out
        return out
    }
}

/** 回放包装：按序返回 fixture 中的输出；超出时返回空回复（§3.2 text 必填）。 */
class FixtureProvider(private val outputs: List<LlmOutput>) : LlmProvider {
    private var index = 0
    override fun respond(context: Any?): LlmOutput =
        outputs.getOrElse(index++) { LlmOutput("好的", emptyList()) }
}
