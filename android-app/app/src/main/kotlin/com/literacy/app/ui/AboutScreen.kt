package com.literacy.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.literacy.app.ui.theme.LiteracyDimens

/** 第三方数据与许可声明（对应 data/THIRD-PARTY-NOTICES.md，随 App 分发必须保留）。 */
private val NoticesText = """
本项目字库数据来自开源项目，随 App 分发保留以下声明。

【汉字数据 — Make Me a Hanzi】
- 来源：https://github.com/skishore/makemeahanzi
  （9000+ 常用简体/繁体汉字数据）
- dictionary.txt（拼音 / 结构拆解 / 部首 / 释义）：
  派生自 Unihan 与 CJKlib，许可 GNU LGPL-3.0
- graphics.txt（笔画 SVG 路径 / 中位线）：
  派生自 Arphic PL KaitiM GB 与 Arphic PL UKai 字体，
  许可 Arphic Public License
- 本项目使用方式：构建期转换为 SQLite 字库，未修改原始数据
""".trimIndent()

/**
 * 关于页：App 信息 + 第三方数据与许可声明。
 * 入口：设置页 → 关于（README 承诺的「设置 → 关于」页）。
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(LiteracyDimens.ScreenPadding)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("关于", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.padding(LiteracyDimens.CardPadding)) {
                Text("识字助手", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "面向成年人的识字与写字学习助手\n版本 0.1.0",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.padding(LiteracyDimens.CardPadding)) {
                Text("第三方数据与许可声明", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    NoticesText,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onBack) { Text("← 返回", fontSize = 17.sp) }
        Spacer(Modifier.height(16.dp))
    }
}
