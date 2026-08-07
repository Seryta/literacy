package com.literacy.app.ui.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 全局对话协调器：App 前台运行期间，持续跟踪对话状态，判断是否需要主动沟通。
 *
 * 机制：
 * - 页面进入/状态变化时登记上下文（[setContext]）——协调器知道"现在在哪个场景"
 * - 任何用户交互（语音输入/点击）重置沉默计时（[onUserInteraction]）
 * - 沉默超过上下文配置的阈值 → 主动说话（[Context.idlePrompt]），渐弱提示（最多 N 次，间隔递增）
 * - 主动说话本身也重置计时（避免自说自话触发循环）
 *
 * 提示策略（克制不烦人）：引导 10s 提示、学习页 15s、首页 60s；每次提示后间隔递增。
 */
class VoiceFlowCoordinator(
    private val scope: CoroutineScope,
    private val speak: (String) -> Unit,
) {
    /** 场景上下文：进入时登记，配置沉默提示。 */
    data class Context(
        val key: String,
        val idlePrompt: String,          // 沉默超时后的主动提示语
        val idleDelayMs: Long = 12_000,  // 沉默多久提示第一次
        val maxPrompts: Int = 2,         // 一个沉默段最多提示次数
    )

    @Volatile private var current: Context? = null
    @Volatile private var lastInteraction = System.currentTimeMillis()
    @Volatile private var promptCount = 0
    @Volatile private var running = false
    private var loopJob: Job? = null

    /** 进入新场景（页面切换/步骤变化）。调用方恒为主线程，ensureLoop 与 stop/resume
     *  因此天然串行——volatile 仅保障 worker 线程对上述字段的可见性。 */
    fun setContext(ctx: Context) {
        current = ctx
        promptCount = 0
        lastInteraction = System.currentTimeMillis()
        ensureLoop()
    }

    /** 用户交互（语音识别到文本、按钮点击、手写等）：重置沉默计时。 */
    fun onUserInteraction() {
        promptCount = 0
        lastInteraction = System.currentTimeMillis()
    }

    /** 主动说话（状态变化自动朗读等）：重置计时，避免被自己的话触发提示。 */
    fun onSpeak(text: String) {
        speak(text)
        onUserInteraction()
    }

    private fun ensureLoop() {
        if (running) return
        running = true
        loopJob = scope.launch {
            while (isActive) {
                delay(5_000)
                val ctx = current ?: continue
                val silentMs = System.currentTimeMillis() - lastInteraction
                if (silentMs > ctx.idleDelayMs && promptCount < ctx.maxPrompts) {
                    promptCount++
                    onSpeak(ctx.idlePrompt)
                }
            }
        }
    }

    fun stop() {
        running = false
        loopJob?.cancel()
        loopJob = null
    }

    /** review-09 P2-3：回前台恢复沉默检测循环（ON_PAUSE stop 之后调用）。
     *  review-09 W5：同时重置沉默计时/提示计数——退后台前已超时的话，回前台
     *  不能 5s 内立刻播提示（提示属于离开前的沉默段，回前台视为新的交互段）。 */
    fun resume() {
        promptCount = 0
        lastInteraction = System.currentTimeMillis()
        ensureLoop()
    }
}
