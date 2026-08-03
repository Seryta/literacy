#!/usr/bin/env python3
"""
汉字数据管线：makemeahanzi → SQLite 字库（agent-core 测试 + Android assets 共用）。

数据来源（LGPL-3.0 + Arphic Public License，见 THIRD-PARTY-NOTICES.md）：
- dictionary.txt：character / pinyin / decomposition（IDS 结构拆解）/ radical / definition
- graphics.txt：strokes（SVG 路径）/ medians（笔画中位线）

输出 schema（hanzi 表）：
- char          TEXT PRIMARY KEY  字
- pinyin        TEXT              拼音（逗号分隔多音）
- decomposition TEXT              IDS 结构拆解（⿱宀豕）
- radical       TEXT              部首
- definition    TEXT              英文释义
- strokes       BLOB              zlib 压缩的 JSON 数组（SVG 笔画路径）
- medians       BLOB              zlib 压缩的 JSON 数组（笔画中位线）
- stroke_count  INTEGER           笔画数

用法：python3 data/build_hanzi_db.py <makemeahanzi目录> <输出.db>
"""
import json
import sqlite3
import sys
import zlib
from pathlib import Path


def load_characters(dictionary_path: Path) -> dict:
    """dictionary.txt：每行一个 JSON。"""
    chars = {}
    with dictionary_path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            d = json.loads(line)
            chars[d["character"]] = d
    return chars


def load_graphics(graphics_path: Path) -> dict:
    """graphics.txt：每行一个 JSON（character/strokes/medians）。"""
    graphics = {}
    with graphics_path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            d = json.loads(line)
            graphics[d["character"]] = d
    return graphics


def build(dict_path: Path, graphics_path: Path, out_path: Path) -> int:
    chars = load_characters(dict_path)
    graphics = load_graphics(graphics_path)
    print(f"dictionary: {len(chars)} 字, graphics: {len(graphics)} 字")

    conn = sqlite3.connect(out_path)
    # P2 复现性：重建前清空（上游移除的字不再残留）
    conn.execute("DROP TABLE IF EXISTS hanzi")
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS hanzi (
            char TEXT PRIMARY KEY,
            pinyin TEXT NOT NULL DEFAULT '',
            decomposition TEXT NOT NULL DEFAULT '',
            radical TEXT NOT NULL DEFAULT '',
            definition TEXT NOT NULL DEFAULT '',
            strokes BLOB NOT NULL,
            medians BLOB NOT NULL,
            stroke_count INTEGER NOT NULL DEFAULT 0
        )
        """
    )
    conn.execute("CREATE INDEX IF NOT EXISTS idx_stroke_count ON hanzi(stroke_count)")

    def blob(obj: object) -> bytes:
        return zlib.compress(json.dumps(obj, ensure_ascii=False).encode("utf-8"))

    count = 0
    for char, d in chars.items():
        g = graphics.get(char)
        strokes = blob(g["strokes"]) if g else b""
        medians = blob(g["medians"]) if g else b""
        stroke_count = len(g["strokes"]) if g else 0
        conn.execute(
            "INSERT OR REPLACE INTO hanzi VALUES (?,?,?,?,?,?,?,?)",
            (
                char,
                ",".join(d.get("pinyin", [])),
                d.get("decomposition", ""),
                d.get("radical", ""),
                d.get("definition", ""),
                strokes,
                medians,
                stroke_count,
            ),
        )
        count += 1

    conn.commit()
    conn.execute("VACUUM")
    conn.close()

    # 统计：有多少字同时有拼音 + 笔画
    conn = sqlite3.connect(out_path)
    full = conn.execute(
        "SELECT COUNT(*) FROM hanzi WHERE pinyin != '' AND stroke_count > 0"
    ).fetchone()[0]
    conn.close()
    print(f"生成 {count} 字（含拼音+笔画完整: {full}），输出: {out_path}")
    return count


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    src = Path(sys.argv[1])
    out = Path(sys.argv[2])
    build(src / "dictionary.txt", src / "graphics.txt", out)
