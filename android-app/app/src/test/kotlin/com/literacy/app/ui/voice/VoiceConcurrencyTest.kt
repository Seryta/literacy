package com.literacy.app.ui.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 语音并发修复回归测试（P2-8）：可控 barrier 验证 STT 恢复/取消与 TTS 发布/取消的锁交错。
 *
 * 测试接缝只包住代次协调与轨道/恢复动作（TtsPlaybackCoordinator / SttState 纯 JVM 类，
 * 引擎以 lambda 注入 stop/发布/恢复动作），不抽象整个 sherpa 引擎。
 *
 * 覆盖交错：
 * - STT（1）cancel 先于恢复判断（代次变→不恢复）；（2）恢复判断先拿锁、cancel 阻塞
 *   后执行（恢复动作完成，取消停麦生效）
 * - TTS（1）cancel 先于最终 publish 拿锁（拒绝发布，旧音频不响）；
 *   （2）publish 先拿锁、cancel 后执行（已发布轨道被 stop）
 */
class VoiceConcurrencyTest {

    // ---- STT：捕获旧状态后、恢复判断前 cancel 的两种交错 ----

    @Test
    fun `STT cancel 先于恢复判断则不恢复监听`() {
        val stt = SttState()
        stt.start {}   // 模拟监听中（startListening 启动新一代监听）
        // initStt 重建捕获（监听中，captureGen = 新代次）
        val (wasListening, captureGen) = stt.captureForRebuild()
        assertTrue("重建前应在监听", wasListening)
        // 重建期间用户退后台（cancelListening）：作废代次
        stt.cancel()
        var restored = false
        stt.restoreIfCurrent(captureGen) { restored = true }
        assertFalse("cancel 作废代次后不得恢复监听（否则在后台重新开麦）", restored)
        assertFalse(stt.listening)
        assertTrue(stt.cancelled)
    }

    @Test
    fun `STT 恢复判断先拿锁则恢复执行且 cancel 后停麦生效`() {
        val stt = SttState()
        val (_, captureGen) = stt.captureForRebuild()
        val restoreEntered = CountDownLatch(1)   // 恢复动作已进入（持锁）
        val cancelReady = CountDownLatch(1)      // cancel 线程已就绪（等锁）
        val releaseRestore = CountDownLatch(1)   // 放行恢复动作完成
        var restored = false
        val restoreThread = Thread {
            stt.restoreIfCurrent(captureGen) {
                restored = true
                restoreEntered.countDown()
                // 模拟 startListening 耗时窗口（此时持锁，cancel 必须阻塞）
                releaseRestore.await(5, TimeUnit.SECONDS)
            }
        }
        val cancelThread = Thread {
            cancelReady.await(5, TimeUnit.SECONDS)
            stt.cancel()   // 阻塞在锁外，恢复动作完成后执行
        }
        restoreThread.start()
        cancelThread.start()
        assertTrue("恢复动作必须进入（代次匹配）", restoreEntered.await(5, TimeUnit.SECONDS))
        cancelReady.countDown()
        releaseRestore.countDown()
        restoreThread.join(5000)
        cancelThread.join(5000)
        assertTrue("恢复判断通过时动作必须执行", restored)
        // cancel 后于恢复：停麦生效（listening=false 且代次已作废）
        assertFalse("cancel 必须在恢复完成后停麦", stt.listening)
        assertTrue(stt.cancelled)
    }

    // ---- TTS：最终 publish/play 锁前 cancel 与 play 先拿锁的两种交错 ----

    @Test
    fun `TTS cancel 先于最终发布则不播放`() {
        val tts = TtsPlaybackCoordinator()
        val gen = tts.nextGeneration()   // 新朗读代次
        var stopped = false
        tts.cancelAndStop { stopped = true }   // cancel 先拿锁（含 stop 动作）
        var published = false
        val ok = tts.publishIfCurrent(gen) { published = true }
        assertFalse("cancel 后最终代次检查必须拒绝发布（旧音频不得响起）", ok)
        assertFalse(published)
        assertTrue("cancel 必须执行 stop 动作", stopped)
    }

    @Test
    fun `TTS 发布先拿锁则 cancel 后停止已发布轨道`() {
        val tts = TtsPlaybackCoordinator()
        val gen = tts.nextGeneration()
        val publishEntered = CountDownLatch(1)   // 发布动作已进入（持锁）
        val cancelReady = CountDownLatch(1)      // cancel 线程已就绪（等锁）
        val releasePublish = CountDownLatch(1)   // 放行发布动作完成
        var published = false
        var stopped = false
        val publishThread = Thread {
            val ok = tts.publishIfCurrent(gen) {
                published = true
                publishEntered.countDown()
                // 模拟 track.play() 调用窗口（此时持锁，cancel 必须阻塞）
                releasePublish.await(5, TimeUnit.SECONDS)
            }
            assertTrue("代次未变时必须发布成功", ok)
        }
        val cancelThread = Thread {
            cancelReady.await(5, TimeUnit.SECONDS)
            tts.cancelAndStop { stopped = true }   // 阻塞在锁外，发布完成后执行
        }
        publishThread.start()
        cancelThread.start()
        assertTrue("发布动作必须进入（代次匹配）", publishEntered.await(5, TimeUnit.SECONDS))
        cancelReady.countDown()
        releasePublish.countDown()
        publishThread.join(5000)
        cancelThread.join(5000)
        assertTrue("发布先拿锁时必须真正发布", published)
        assertTrue("publish 先拿锁后 cancel 必须停掉已发布轨道", stopped)
        // 取消后同代次不得再发布（代次已作废）
        assertFalse("cancel 后同代次再次发布必须拒绝", tts.publishIfCurrent(gen) {})
    }

    @Test
    fun `TTS 真实并发竞争后状态一致`() {
        // 多轮并发 cancel/publish 竞争：最终状态必须自洽——cancel 后同代次发布恒拒绝；
        // 每次成功发布后 cancel 必然停掉（无泄漏轨道）。非时序断言，验证锁本身无数据竞争。
        val tts = TtsPlaybackCoordinator()
        repeat(50) {
            val gen = tts.nextGeneration()
            val t1 = Thread { tts.publishIfCurrent(gen) {} }
            val t2 = Thread { tts.cancelAndStop {} }
            t1.start(); t2.start()
            t1.join(2000); t2.join(2000)
            // cancelAndStop 恒最后执行代次作废：无论 publish 是否先发布，同代次再发布必被拒
            assertFalse("cancel 后同代次不得再发布", tts.publishIfCurrent(gen) {})
        }
    }
}
