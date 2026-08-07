package com.literacy.agent.data

object DefaultPacks {

    val P1_FAMILY = CurriculumPack(
        id = "p1_family",
        name = "家庭常用字",
        iconResName = "i-home",
        chars = listOf("人", "大", "口", "女", "子", "妈", "爸", "家"),
        prerequisitePackIds = listOf("p0_name"),
        targetCapabilities = listOf("recognize", "write"),
        exampleSentences = mapOf(
            "人" to listOf("我是中国人", "这里有三个人", "工人叔叔很辛苦"),
            "大" to listOf("大的那个", "大人请坐", "一只大鸟"),
            "口" to listOf("开口说话", "门口有人", "一口水"),
            "女" to listOf("女人", "女儿", "女孩"),
            "子" to listOf("儿子", "子女", "一下子"),
            "妈" to listOf("妈妈在家", "妈妈做饭", "我妈妈"),
            "爸" to listOf("爸爸上班", "爸爸开车", "我爸爸"),
            "家" to listOf("这是我的家", "回家吃饭", "家里三个人"),
        ),
    )

    val P2_NUMBERS = CurriculumPack(
        id = "p2_numbers",
        name = "数字与金额基础",
        iconResName = "i-num",
        chars = listOf("一", "二", "十", "三", "千", "万", "个", "五", "四", "百"),
        prerequisitePackIds = listOf("p1_family"),
        targetCapabilities = listOf("recognize", "write"),
        exampleSentences = mapOf(
            "一" to listOf("一个苹果", "一块钱", "一号门"),
            "万" to listOf("一万元", "十万人", "千家万户"),
        ),
    )

    val ALL: List<CurriculumPack> = listOf(P1_FAMILY, P2_NUMBERS)
}
