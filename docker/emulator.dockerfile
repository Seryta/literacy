# Android 模拟器镜像：基于 literacy-android（SDK34 + JDK17）
# 宿主预下载 emulator + system image 到构建上下文，COPY 进镜像，构建不联网
# 构建: docker build -f docker/emulator.dockerfile -t literacy-emulator /tmp/ae-sdk
FROM literacy-android

# 模拟器运行依赖（无头模式所需最小库集）
RUN apt-get update && apt-get install -y --no-install-recommends \
        libpulse0 libasound2 libx11-6 libxcb1 libxcomposite1 libxcursor1 \
        libxdamage1 libxext6 libxi6 libxrandr2 libxtst6 libnss3 libnspr4 libgl1 \
    && rm -rf /var/lib/apt/lists/*

# 宿主预下载的 emulator/ 与 system-images/（COPY 增量，不覆盖已有 SDK 组件）
COPY . /opt/android-sdk/
