
import json, os, sys, time
from pathlib import Path

# 这里用 MCP 调 browser_tabs/browser_navigate/browser_evaluate/browser_take_screenshot
# 但我们用命令行的方式：直接输出切屏 JS 脚本调用 URL 即可，不用 MCP 从 python 调用。

# 直接在浏览器页用 browser_evaluate 执行 setActive(i) 然后截图即可
print("ready")
