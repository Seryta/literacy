package com.literacy.app.ui

import org.junit.Assert.*
import org.junit.Test

/** 语音解析规则单测（JVM，无 Android 依赖）。 */
class VoiceRulesTest {

    // ── OnboardingVoiceRules.extractName ──
    @Test
    fun `extractName 剥前缀与标点`() {
        assertEquals("张建国", OnboardingVoiceRules.extractName("我叫张建国"))
        assertEquals("张建国", OnboardingVoiceRules.extractName("我的名字叫张建国"))
        assertEquals("张建国", OnboardingVoiceRules.extractName("我叫张建国。"))
        assertEquals("张建国", OnboardingVoiceRules.extractName("我是张建国"))
    }

    @Test
    fun `extractName 裸名与超长截断`() {
        assertEquals("张建国", OnboardingVoiceRules.extractName("张建国"))
        assertEquals("张建国先", OnboardingVoiceRules.extractName("张建国先生你好"))   // 汉字全过滤后取前 4 字
        assertEquals("张建国建", OnboardingVoiceRules.extractName("张建国建"))   // 4 字不截断
    }

    @Test
    fun `extractName 空与纯标点`() {
        assertEquals("", OnboardingVoiceRules.extractName(""))
        assertEquals("", OnboardingVoiceRules.extractName("。。。"))
    }

    // ── OnboardingVoiceRules.pickMascotIndex ──
    @Test
    fun `pickMascotIndex 中文与阿拉伯数字`() {
        assertEquals(0, OnboardingVoiceRules.pickMascotIndex("第一个"))
        assertEquals(1, OnboardingVoiceRules.pickMascotIndex("第二个"))
        assertEquals(3, OnboardingVoiceRules.pickMascotIndex("第四个"))
        assertEquals(2, OnboardingVoiceRules.pickMascotIndex("第3个"))
    }

    @Test
    fun `pickMascotIndex 越界与无匹配`() {
        assertNull(OnboardingVoiceRules.pickMascotIndex("第九个"))
        assertNull(OnboardingVoiceRules.pickMascotIndex("随便"))
        assertNull(OnboardingVoiceRules.pickMascotIndex(""))
    }

    // ── OnboardingVoiceRules.isYes / isNo ──
    @Test
    fun `isNo 优先于 isYes`() {
        assertTrue(OnboardingVoiceRules.isNo("不对"))
        assertTrue(OnboardingVoiceRules.isNo("不是"))
        assertFalse(OnboardingVoiceRules.isYes("不对"))
    }

    @Test
    fun `isYes 确认词`() {
        assertTrue(OnboardingVoiceRules.isYes("对"))
        assertTrue(OnboardingVoiceRules.isYes("是的"))
        assertTrue(OnboardingVoiceRules.isYes("没错"))
        assertTrue(OnboardingVoiceRules.isYes("嗯好"))
    }

    // ── VoiceCommandParser（首页导航）──
    @Test
    fun `parse 设置建档与学字`() {
        assertEquals(VoiceCommandParser.Action.OpenSettings, VoiceCommandParser.parse("打开设置"))
        assertEquals(VoiceCommandParser.Action.OpenProfile, VoiceCommandParser.parse("我要建档"))
        assertEquals(VoiceCommandParser.Action.LearnChar("家"), VoiceCommandParser.parse("我想学家"))
        assertEquals(VoiceCommandParser.Action.OpenSearchChar, VoiceCommandParser.parse("学个字"))   // 不指定具体字 → 搜索卡
        assertEquals(VoiceCommandParser.Action.OpenNameLearning, VoiceCommandParser.parse("学我的名字"))
        assertEquals(VoiceCommandParser.Action.OpenReview, VoiceCommandParser.parse("我要复习"))
    }

    @Test
    fun `parse 否定句不触发学字`() {
        assertEquals(VoiceCommandParser.Action.Unknown, VoiceCommandParser.parse("我不学了"))
        assertEquals(VoiceCommandParser.Action.Unknown, VoiceCommandParser.parse("别学了"))
    }

    @Test
    fun `parse 无关文本`() {
        assertEquals(VoiceCommandParser.Action.Unknown, VoiceCommandParser.parse("今天天气不错"))
    }

    // ── VoiceCommandParser.learnCommand（学习页操作）──
    @Test
    fun `learnCommand 操作词映射`() {
        assertEquals("help", VoiceCommandParser.learnCommand("帮助我"))
        assertEquals("pause", VoiceCommandParser.learnCommand("暂停一下"))
        assertEquals("resume", VoiceCommandParser.learnCommand("继续"))
        assertEquals("end", VoiceCommandParser.learnCommand("结束"))
        assertEquals("next", VoiceCommandParser.learnCommand("下一个字"))
        assertEquals("review_stage", VoiceCommandParser.learnCommand("下一阶段"))
    }

    @Test
    fun `learnCommand 无关文本返回 null`() {
        assertNull(VoiceCommandParser.learnCommand("这个字怎么写"))
    }
}
