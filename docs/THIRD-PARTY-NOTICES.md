# 第三方数据与许可声明

本项目字库数据来自开源项目，随 App 分发时保留以下声明（App「设置 → 关于」页展示）：

## 汉字数据 — Make Me a Hanzi

- 来源：https://github.com/skishore/makemeahanzi （9000+ 常用简体/繁体汉字数据）
- `dictionary.txt`（拼音 / 结构拆解 / 部首 / 释义）：派生自 Unihan 与 CJKlib，
  许可 **GNU LGPL-3.0**（详见 makemeahanzi 的 COPYING / LGPL 文件）
- `graphics.txt`（笔画 SVG 路径 / 中位线）：派生自 Arphic PL KaitiM GB 与 Arphic PL UKai 字体，
  许可 **Arphic Public License**（详见 APL 文件）
- 本项目对数据的使用方式：构建期由 `data/build_hanzi_db.py` 转换为 SQLite 字库，
  未修改原始数据内容
