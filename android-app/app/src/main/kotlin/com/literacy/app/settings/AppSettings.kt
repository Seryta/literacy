package com.literacy.app.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * App 设置。
 *
 * - api_key：EncryptedSharedPreferences 加密存储（独立文件 literacy_settings_secure，
 *   仅 key 加密；baseUrl/model/displayName 非敏感，保持明文 SharedPreferences 不动）
 * - 旧版明文 key（literacy_settings.api_key）首次读取时一次性迁移：读明文 → 写加密 → 清明文
 *
 * 安全说明（surface 决策）：androidx.security:security-crypto 1.1.0 后无稳定版
 * （1.1.0-alpha07 为最后一个版本，Google 已归档停止维护），但 minSdk 26 ≥ 21 兼容、
 * 功能稳定且广泛使用。若将来需脱离该库，可迁移到 Keystore + 手写 AES-GCM——
 * 本类接口不变，只换存储实现。
 */
class AppSettings(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("literacy_settings", Context.MODE_PRIVATE)

    /** 加密存储（懒创建：MasterKey 首次生成涉及 Keystore，一次 ~100ms 量级）。
     *  MasterKey/加密库可能抛 GeneralSecurityException/IOException（Keystore 损坏、定制 ROM 兼容）——
     *  调用方一律经 [secureOrNull] 访问：api_key 是可选配置，加密不可用只降级该项，不阻断 App。 */
    private val securePrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** 加密访问安全包装：Keystore 初始化失败返回 null——api_key 降级为不可用（读空/写丢弃），
     *  明文设置（baseUrl/model/displayName）不受影响。异常不外抛：不允许 Keystore 问题 kill 进程。 */
    private fun secureOrNull(): SharedPreferences? = try {
        securePrefs
    } catch (e: Exception) {
        Log.w(TAG, "加密存储不可用（Keystore 异常），api_key 降级为空", e)
        null
    }

    /** 预热加密存储（MasterKey/Keystore 首次生成），MainActivity 后台线程调用防首帧卡顿。
     *  异常不外抛：Keystore 失败只影响 api_key（可选配置），不 kill 进程。 */
    fun prewarm() {
        secureOrNull()
    }

    /** review-09 P2-8：加密保存 API key，返回写入是否成功（同步 commit——写入失败 UI 可见）。 */
    fun saveApiKey(key: String): Boolean {
        val secure = secureOrNull() ?: return false
        return try {
            secure.edit().putString(KEY_API, key.trim()).commit()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "api_key 加密写入失败", e)
            false
        }
    }

    var apiKey: String
        get() = readApiKey()
        set(v) {
            val key = v.trim()
            val secure = secureOrNull()
            if (secure == null) {
                // 加密不可用：写入丢弃（不降级明文存储），明文残留保留待 Keystore 恢复后迁移
                Log.w(TAG, "api_key 写入被丢弃：加密存储不可用")
                return
            }
            val secureWrite = if (key.isEmpty()) {
                secure.edit().remove(KEY_API).commit()
            } else {
                secure.edit().putString(KEY_API, key).commit()
            }
            // 加密写 commit() 同步落盘成功才清理明文残留：防密文未持久化进程被杀 → 明文已删密文丢失
            if (secureWrite && prefs.contains(KEY_API)) prefs.edit().remove(KEY_API).commit()
        }

    /** 旧版明文 key 一次性迁移：明文存在 → 读入加密（commit 同步落盘）→ 清明文（无感，不要求重新输入）。
     *  迁移失败（Keystore 异常/落盘失败）保留明文，下次启动重试——明文/密文不同时处于丢失窗口。 */
    private fun readApiKey(): String {
        val legacy = prefs.getString(KEY_API, null)
        if (!legacy.isNullOrBlank()) {
            val secure = secureOrNull()
            if (secure != null && secure.edit().putString(KEY_API, legacy).commit()) {
                prefs.edit().remove(KEY_API).commit()
            }
            return legacy
        }
        // review-10 P2-17：读取也包 try——损坏密文解密异常在首页 hasApiKey 时崩溃（不 kill 进程）
        return try {
            secureOrNull()?.getString(KEY_API, "") ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "api_key 读取失败（密文损坏？），降级为空", e)
            ""
        }
    }

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(v) = prefs.edit().putString(KEY_BASE_URL, v).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = prefs.edit().putString(KEY_MODEL, v).apply()

    /** 称呼方式（建档采集，P2：不再丢弃）。 */
    var displayName: String
        get() = prefs.getString(KEY_DISPLAY_NAME, "") ?: ""
        set(v) = prefs.edit().putString(KEY_DISPLAY_NAME, v).apply()

    /** 选中的陪伴宠物 id（onboarding 引导选择；默认小绿小怪兽）。 */
    var mascotId: String
        get() = prefs.getString(KEY_MASCOT_ID, "monster") ?: "monster"
        set(v) = prefs.edit().putString(KEY_MASCOT_ID, v).apply()

    /** onboarding 引导流程是否已完成（首次进入走引导，完成后置 true）。 */
    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(v) = prefs.edit().putBoolean(KEY_ONBOARDING_DONE, v).apply()

    /** 初始问候是否已说过（引导后的第一次进首页问"今天想学什么"，之后靠规划不反复问）。 */
    var initialGreetDone: Boolean
        get() = prefs.getBoolean(KEY_INITIAL_GREET_DONE, false)
        set(v) = prefs.edit().putBoolean(KEY_INITIAL_GREET_DONE, v).apply()

    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    companion object {
        private const val TAG = "AppSettings"
        private const val SECURE_PREFS_NAME = "literacy_settings_secure"
        private const val KEY_API = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_MASCOT_ID = "mascot_id"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_INITIAL_GREET_DONE = "initial_greet_done"

        /** 默认 provider：deepseek（pi 同款 openai-completions 格式）。 */
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-v4-flash"
    }
}
