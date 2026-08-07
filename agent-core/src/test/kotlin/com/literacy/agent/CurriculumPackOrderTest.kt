package com.literacy.agent

import com.literacy.agent.data.DefaultPacks
import com.literacy.agent.data.SqliteHanziRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CurriculumPackOrderTest {

    private val db = File("../data/hanzi.db")
    private val repo = SqliteHanziRepository(db)

    private val MODERN_256_SINGLE_CHAR = setOf(
        "一", "乙",
        "二", "十", "丁", "七", "人", "儿", "入", "八", "九", "匕", "几", "刁", "了", "乃", "刀", "力", "又",
        "三", "干", "于", "亏", "士", "工", "土", "才", "寸", "下", "丈", "大", "兀", "与", "万", "上", "小",
        "口", "山", "巾", "千", "川", "义", "丸", "广", "亡", "门", "丫", "之", "尸", "弓", "己", "已", "巳",
        "子", "卫", "也", "女", "飞", "刃", "习", "叉", "马", "乡", "幺",
        "丰", "王", "井", "开", "夫", "天", "无", "元", "专", "云", "木", "五", "支", "厅", "不", "太", "犬",
        "区", "历", "尤", "友", "匹", "车", "巨", "牙", "屯", "戈", "比", "瓦", "止", "少", "日", "曰", "月",
        "贝", "水", "见", "牛", "手", "气", "毛", "升", "长", "片", "斤", "爪", "父", "氏", "欠", "风", "丹",
        "匀", "乌", "勾", "凤", "六", "文", "亢", "方", "火", "为", "斗", "户", "心", "尹", "尺", "丑", "巴",
        "办", "予", "劝", "双", "书", "幻", "玉",
    )

    private fun stroke(c: String): Int {
        val s = repo.strokeCount(c)
        assertTrue(s > 0, "字库缺少「$c」的笔画数，无法排序验证")
        return s
    }

    private fun isSingle(c: String): Boolean = c in MODERN_256_SINGLE_CHAR

    @Test
    fun `P1 family chars 笔画非递减（铁律 §4 1 1）`() {
        val strokes = DefaultPacks.P1_FAMILY.chars.map { stroke(it) }
        for (i in 0 until strokes.size - 1) {
            assertTrue(
                strokes[i] <= strokes[i + 1],
                "P1 笔画反序：第${i}字「${DefaultPacks.P1_FAMILY.chars[i]}」${strokes[i]}画 > " +
                    "第${i + 1}字「${DefaultPacks.P1_FAMILY.chars[i + 1]}」${strokes[i + 1]}画",
            )
        }
    }

    @Test
    fun `P2 numbers chars 笔画非递减（铁律 §4 1 1）`() {
        val strokes = DefaultPacks.P2_NUMBERS.chars.map { stroke(it) }
        for (i in 0 until strokes.size - 1) {
            assertTrue(
                strokes[i] <= strokes[i + 1],
                "P2 笔画反序：第${i}字「${DefaultPacks.P2_NUMBERS.chars[i]}」${strokes[i]}画 > " +
                    "第${i + 1}字「${DefaultPacks.P2_NUMBERS.chars[i + 1]}」${strokes[i + 1]}画",
            )
        }
    }

    @Test
    fun `同笔画相邻字 独体字必须排在合体字前面（铁律 §4 1 2）`() {
        val allChars = DefaultPacks.ALL.flatMap { it.chars }
        val allStrokes = allChars.map { stroke(it) }

        for (i in 0 until allChars.size - 1) {
            val a = allChars[i]
            val b = allChars[i + 1]
            if (allStrokes[i] == allStrokes[i + 1]) {
                val aSingle = isSingle(a)
                val bSingle = isSingle(b)
                assertFalse(
                    !aSingle && bSingle,
                    "同${allStrokes[i]}画反序：「$a」(合体) 排在「$b」(独体) 前面，违反独体先于合体",
                )
            }
        }
    }

    @Test
    fun `P1 family 前 5 字全部是 现代常用独体字规范 独体字（独体优先硬约束 §4 0 3）`() {
        val first5 = DefaultPacks.P1_FAMILY.chars.take(5)
        for (c in first5) {
            assertTrue(isSingle(c), "P1 前5字「$c」应是独体字（国家语委《现代常用独体字规范》），但未在清单内")
        }
    }

    @Test
    fun `易错点反向断言 妈画数小于爸 五画数小于四（人容易凭感觉排反）`() {
        assertTrue(stroke("妈") < stroke("爸"), "妈应少于爸（实际 妈${stroke("妈")}画 vs 爸${stroke("爸")}画）")
        assertTrue(stroke("五") < stroke("四"), "五应少于四（实际 五${stroke("五")}画 vs 四${stroke("四")}画）")
    }

    @Test
    fun `P1 family 真实笔画号与设计文档注释一致（妈6爸8家10）`() {
        val chars = DefaultPacks.P1_FAMILY.chars
        assertEquals(listOf(2, 3, 3, 3, 3, 6, 8, 10), chars.map { stroke(it) }, "P1 笔画序列与 REDESIGN-v2 §4.2 注释不一致")
    }
}
