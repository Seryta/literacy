package com.literacy.agent.provider

import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Provider 配置加载（对齐 pi 的 provider 配置形态：openai-completions 兼容 + 环境变量取 key）。
 *
 * 配置文件格式（provider-config.json，不入 git，API key 走环境变量）：
 * ```json
 * {
 *   "deepseek": {
 *     "baseUrl": "https://api.deepseek.com",
 *     "model": "deepseek-v4-flash",
 *     "apiKeyEnv": "DEEPSEEK_API_KEY"
 *   }
 * }
 * ```
 */
class ProviderConfigLoader {

    /** 从配置文件 + 环境变量解析。key 优先取 apiKeyEnv 环境变量，其次 apiKey 字面值。 */
    fun load(file: File, providerId: String): HttpLlmProvider.ProviderConfig? {
        if (!file.exists()) return null
        val root = try {
            Yaml().load<Any>(file.readText()) as? Map<*, *>
        } catch (e: Exception) {
            return null
        } ?: return null
        val p = root[providerId] as? Map<*, *> ?: return null
        val baseUrl = p["baseUrl"]?.toString() ?: return null
        val model = p["model"]?.toString() ?: return null
        val key = p["apiKeyEnv"]?.toString()?.let { System.getenv(it) }
            ?: p["apiKey"]?.toString()
            ?: return null
        return HttpLlmProvider.ProviderConfig(baseUrl, key, model)
    }
}
