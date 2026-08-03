# 识字助手 —— 统一构建/测试入口（全部在容器中执行，宿主零构建）
#
# 常用：
#   make test           JVM 测试基线（97+ 测试）
#   make build          Android APK（app-debug.apk）
#   make replay         fixture 回放（真实 LLM 输出验证）
#   make record         录制真实 LLM 输出为 fixture（需 DEEPSEEK_API_KEY）
#   make db             重建字库 SQLite 并同步 Android assets
#   make pii            push 前 PII 四层检查
#   make check          pii + test（推送前完整检查）
#   make android-image  构建 Android SDK 镜像（首次）
#
# 容器 volume 持久化：gradle-cache（依赖缓存）；签名 keystore 在仓库内
# （android-app/keystore/debug.keystore，签名跨构建一致）。

GRADLE_CACHE := gradle-cache
JVM_IMAGE   := gradle:8.10-jdk17
ANDROID_IMG := literacy-android:latest
CORE_DIR    := $(CURDIR)/agent-core
CASES_DIR   := $(CURDIR)/test-cases
DATA_DIR    := $(CURDIR)/data
FIXT_DIR    := $(CURDIR)/fixtures

# 录制用的 LLM 配置（provider-config.json 已在 .gitignore；key 走环境变量）
PROVIDER_CONFIG := $(CURDIR)/provider-config.json

.PHONY: help test build replay record db assets pii check android-image clean-recordings

help:
	@echo "识字助手构建/测试入口（容器内执行）"
	@echo "  make test          JVM 测试基线（agent-core 全部测试）"
	@echo "  make build         Android APK（app-debug.apk）"
	@echo "  make replay        fixture 回放（真实 LLM 输出验证，无 fixture 时跳过）"
	@echo "  make record        录制真实 LLM 输出为 fixture（需 DEEPSEEK_API_KEY + provider-config.json）"
	@echo "                     CASES=GT-003,GT-010 可限定用例"
	@echo "  make db            重建字库（MZH_DIR=/path/to/makemeahanzi 指定源目录）"
	@echo "  make pii           push 前 PII 四层检查"
	@echo "  make check         pii + test（推送前完整检查）"
	@echo "  make android-image 构建 Android SDK 镜像（首次 / 重装 SDK 后）"

# ---- JVM 测试基线 ----
test:
	docker run --rm -v $(GRADLE_CACHE):/home/gradle/.gradle \
		-v $(CORE_DIR):/workspace -v $(CASES_DIR):/test-cases \
		-v $(DATA_DIR):/data -v $(FIXT_DIR):/fixtures \
		-w /workspace $(JVM_IMAGE) gradle test --no-daemon

# ---- Android APK（固定签名，升级安装不冲突）----
build:
	docker run --rm -v $(GRADLE_CACHE):/home/gradle/.gradle \
		-v $(CURDIR)/android-app:/workspace -v $(CORE_DIR):/agent-core \
		-w /workspace $(ANDROID_IMG) gradle :app:assembleDebug --no-daemon
	@echo "APK: android-app/app/build/outputs/apk/debug/app-debug.apk"

# ---- fixture 回放（真实模型输出验证；无 fixture 时测试自动跳过）----
replay:
	docker run --rm -v $(GRADLE_CACHE):/home/gradle/.gradle \
		-v $(CORE_DIR):/workspace -v $(CASES_DIR):/test-cases \
		-v $(DATA_DIR):/data -v $(FIXT_DIR):/fixtures \
		-w /workspace $(JVM_IMAGE) gradle test --tests "com.literacy.agent.FixtureReplayTest" --no-daemon

# ---- 录制真实 LLM 输出为 fixture（按需运行，需 key；CASES 可限定用例）----
record: $(PROVIDER_CONFIG)
	@test -n "$(DEEPSEEK_API_KEY)" || (echo "需要 DEEPSEEK_API_KEY 环境变量"; exit 1)
	@test -f $(PROVIDER_CONFIG) || (echo "缺少 $(PROVIDER_CONFIG)（复制 provider-config.example.json）"; exit 1)
	@# P1-15 + review-09 P1-17：key 经 --env-file 传入（不展开到命令行/日志），recipe 隐藏；
	@# mktemp 建临时文件（默认 0600，无权限窗口/符号链接竞态），trap EXIT 保证失败路径也清理
	@tmp=$$(mktemp) && trap 'rm -f "$$tmp"' EXIT && \
	printf 'DEEPSEEK_API_KEY=%s\n' "$$DEEPSEEK_API_KEY" > "$$tmp" && \
	docker run --rm --env-file "$$tmp" \
		-e LITERACY_RECORD_CASES=$(CASES) \
		-v $(GRADLE_CACHE):/home/gradle/.gradle \
		-v $(CORE_DIR):/workspace -v $(CASES_DIR):/test-cases \
		-v $(FIXT_DIR):/fixtures -v $(PROVIDER_CONFIG):/provider-config.json \
		-w /workspace $(JVM_IMAGE) gradle test --tests "com.literacy.agent.RecordFixturesTest" --rerun-tasks --no-daemon && \
	rm -f "$$tmp"

$(PROVIDER_CONFIG):
	@echo "请先复制 provider-config.example.json 为 provider-config.json 并配置"
	@exit 1

# ---- 字库重建（MZH_DIR 指向 makemeahanzi 克隆目录）+ 同步 Android assets ----
db:
	@test -n "$(MZH_DIR)" || (echo "需要 MZH_DIR=/path/to/makemeahanzi"; exit 1)
	python3 $(DATA_DIR)/build_hanzi_db.py $(MZH_DIR) $(DATA_DIR)/hanzi.db
	cp $(DATA_DIR)/hanzi.db $(CURDIR)/android-app/app/src/main/assets/hanzi.db
	@echo "字库已重建并同步 assets"

# ---- PII 四层检查（push 前必须；review-09 P2-11：命中即 exit 1 阻断，不静默通过）----
pii:
	@echo "=== 1) commit 元数据（review-10 P2-20：白名单校验，非白名单邮箱阻断）==="
	@hit=$$(git log --all --format="%an <%ae>%n%cn <%ce>" | sort -u | grep -vE "<(saryta@qq\.com|seryta@qq\.com|31036794\+Seryta@users\.noreply\.github\.com)>" || true); if [ -n "$$hit" ]; then echo "$$hit"; echo "!! 非白名单 commit 邮箱"; exit 1; fi
	@git log --all --format="%an <%ae>%n%cn <%ce>" | sort -u
	@echo "=== 2) tracked 文件（排除 debug.keystore 二进制；邮箱也命中——不忽略）==="
	@hit=$$(git grep -nE --text "(sk-[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{30,}|-----BEGIN [A-Z ]*PRIVATE KEY-----|1[3-9][0-9]{9}|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})" -- . ':(exclude)android-app/keystore/*' || true); if [ -n "$$hit" ]; then echo "$$hit"; echo "!! PII 命中（tracked 文件）"; exit 1; else echo "无匹配"; fi
	@echo "=== 3) commit message（排除 DEEPSEEK_API_KEY 环境变量名引用）==="
	@hit=$$(git log --all --format="%s%n%b" | grep -iE "(password|secret|token|sk-[A-Za-z0-9]|AKIA|1[3-9][0-9]{9}|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})" | grep -v "DEEPSEEK_API_KEY" || true); if [ -n "$$hit" ]; then echo "$$hit"; echo "!! PII 命中（commit message）"; exit 1; else echo "无匹配"; fi
	@echo "=== 4) 历史 blob（排除 Author 行与 keystore）==="
	@hit=$$(git log --all -p | grep -nE --text "(sk-[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{30,}|-----BEGIN [A-Z ]*PRIVATE KEY-----|1[3-9][0-9]{9}|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})" | grep -vE "Author:|debug\.keystore|^[0-9]+:commit [0-9a-f]{7,}|saryta@qq\.com|seryta@qq\.com" || true); if [ -n "$$hit" ]; then echo "$$hit"; echo "!! PII 命中（历史 blob）"; exit 1; else echo "无匹配"; fi

# ---- Android instrumented 测试（需模拟器 emulator-5554 在跑）----
androidtest:
	docker run --rm --network host -v $(GRADLE_CACHE):/home/gradle/.gradle \
		-v $(CURDIR)/android-app:/workspace -v $(CORE_DIR):/agent-core \
		-w /workspace $(ANDROID_IMG) gradle :app:connectedDebugAndroidTest --no-daemon

# ---- Android lint（无需模拟器）----
lint:
	docker run --rm --network host -v $(GRADLE_CACHE):/home/gradle/.gradle \
		-v $(CURDIR)/android-app:/workspace -v $(CORE_DIR):/agent-core \
		-w /workspace $(ANDROID_IMG) gradle :app:lintDebug --no-daemon

# ---- 推送前完整检查（review-09 P1-18 + review-10 P2-19：含 androidTest + lint 门禁）----
# 注：androidtest 需要模拟器 emulator-5554 在跑（emulator status 确认）
check: pii test build lint androidtest

# ---- Android SDK 组件下载（review-09 P1-19：干净 checkout 无 zip——先下载再构建）----
android-components:
	@mkdir -p docker
	@curl -fL --retry 3 -o docker/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
	@curl -fL --retry 3 -o docker/platform-tools.zip https://dl.google.com/android/repository/platform-tools-latest-linux.zip
	@curl -fL --retry 3 -o docker/platform-34.zip https://dl.google.com/android/repository/platform-34-ext12_r01.zip
	@curl -fL --retry 3 -o docker/build-tools.zip https://dl.google.com/android/repository/build-tools_r34-linux.zip
	@echo "SDK 组件已下载到 docker/"

# ---- Android SDK 镜像（首次；干净 checkout 先下载组件）----
android-image: android-components
	docker build -f docker/android-sdk.dockerfile -t $(ANDROID_IMG) .
