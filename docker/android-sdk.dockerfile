# Android 构建容器：Gradle 8.10 + JDK17 + Android SDK 34（组件宿主预下载 COPY，构建不联网）
FROM gradle:8.10-jdk17

# cmdline-tools
COPY docker/cmdtools.zip /tmp/cmdtools.zip
RUN mkdir -p /opt/android-sdk/cmdline-tools && \
    unzip -q /tmp/cmdtools.zip -d /opt/android-sdk/cmdline-tools && \
    mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest && \
    rm /tmp/cmdtools.zip

# SDK 组件（platform-tools/build-tools 宿主预下载；标准 platform-34 由 sdkmanager 安装——
# review-10 P1-14：ext 平台改名 android-34 会触发 AGP 联网补装标准平台，离线构建失败。
# sdkmanager 在 docker build 时联网装一次进镜像，运行时离线可用）
COPY docker/platform-tools.zip docker/build-tools.zip /opt/android-sdk/
RUN mkdir -p /opt/android-sdk/licenses && \
    echo -e "\n24333f8a63b6825ea9c5514f83c2829b004d1fee" > /opt/android-sdk/licenses/android-sdk-license && \
    cd /opt/android-sdk && \
    unzip -q platform-tools.zip -d /opt/android-sdk && rm platform-tools.zip && \
    mkdir -p build-tools && unzip -q build-tools.zip -d build-tools && mv build-tools/android-* build-tools/34.0.0 && rm build-tools.zip && \
    cd /opt/android-sdk/cmdline-tools/latest/bin && \
    ./sdkmanager --sdk_root=/opt/android-sdk "platforms;android-34" && \
    rm -rf /opt/android-sdk/platforms/android-34-ext*

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk
