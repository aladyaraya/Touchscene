# TouchScene · Android 手机相机版

<img src="android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" height="96" alt="TouchScene 应用图标：温暖的轮廓线与触点">

[![Android checks](https://github.com/aladyaraya/Touchscene/actions/workflows/android.yml/badge.svg?branch=only-for-android)](https://github.com/aladyaraya/Touchscene/actions/workflows/android.yml)

通过手机拍照或选择本地图片，沿用原 Demo 的 OpenCV 边缘／GrabCut 轮廓处理，
让用户通过触摸位置和手机整体振动探索平面结构；百炼负责原图描述与语音指令。
手机单马达不会产生局部触觉像素，轮廓可感知程度仍需真实用户验证。

这是 `only-for-android` 的维护入口，不是历史 Web／ESP32 原型，也不是另一版三页重写应用。

## 拉取与打开

```sh
git clone --branch only-for-android --single-branch https://github.com/aladyaraya/Touchscene.git
cd Touchscene
```

Android Studio 打开 `android/`。工具链：JDK 17、Android SDK 35、Gradle Wrapper 8.11.1；
minSdk 29。只含 arm64-v8a 原生库，真机需支持 arm64；x86 模拟器仅在支持 ARM 翻译时可运行 JNI。
依赖通过 Maven 下载，不需要影石账号、Maven 凭据、NDK 安装或外部相机。

## 本地配置

复制 `android/local.properties.example` 为同目录 `local.properties`，填写本机 SDK 路径和自己的百炼 Key。
也可通过 Android Studio／`ANDROID_HOME` 设置 SDK。不要提交本地配置。

- 无 Key 可构建、拍照、选图和本地触觉探索；不保证云描述、云 ASR 或云 TTS 可用。
- 识图 `qwen3.8-flash` 使用归一化原图，不使用轮廓图；ASR 为 `qwen-audio-3.0-asr-flash`。
- 系统中文 TTS 优先，仅不可用时使用既有百炼 `cosyvoice-v3-flash`／`longanyang` 后备。
- Debug 会嵌入本地 Key，只能内部测试，不能公开分发；Release 强制清空云 endpoint／Key，且未签名。

详见 [配置与安全](docs/CONFIGURATION.md)。不要把 Key 写入源码、Issue、Actions 或聊天示例。

## 构建与检查

Node 22+ 用于仓库检查，无 npm 依赖。先执行源码检查，再构建两个变体，最后检查 APK：

```sh
node --test tests/minimal-baseline.test.mjs
node --test tests/app-icon.test.mjs
cd android
./gradlew --no-daemon -Pkotlin.incremental=false :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug
cd ..
node --test tests/native-apk.test.mjs
```

Windows 用 `gradlew.bat`。如果 JDK 因中文路径无法加载类，可临时把 `android/` 映射到空闲 ASCII
盘符后构建，完成后撤销映射；不要修改全局配置或提交本机路径。源码检查支持 ZIP 和浅克隆，
原生 APK 检查必须先构建 Debug／Release，缺包会明确失败。

APK 只在本机 `android/app/build/outputs/apk/` 生成，不提交 Git。调试包名
`com.touchscene.minimal`；不要卸载旧应用或清除数据。真机仪器测试方法见 [验收记录](docs/ACCEPTANCE.md)。

## 使用与源码入口

首页上半打开相机，下半选图。相机画面占上方 75%，底部整块按钮短按冻结／描述或恢复；
按下短震，长按 500 ms 后开始录音，松开发送，实际录音不足 300 ms 丢弃。
冻结中可直接说“拍一张”，同一照片保存相册并刷新探索与描述；“录像／录音／摄影”及对应停止命令控制录像。
双击画面按原顺序切换原图、边缘、轮廓。

手机版入口位于 `android/app/src/mobile/java`；共用触觉、相机输入及本地图片页位于
`android/app/src/main/java/com/insta360/kmpsdk/demo/` 的明确源集子目录。
同名原 SDK Activity／Preview 等源码仅供参考，不参与构建，不要误改。详见 [架构与边界](docs/ARCHITECTURE.md)。

## 文档与维护

- [验收结果、已知问题和待体验项目](docs/ACCEPTANCE.md)
- [开发与交接规范](docs/MAINTENANCE.md)
- [第三方依赖与许可证](docs/THIRD_PARTY.md)
- [应用图标、矢量母版与资源生成](docs/branding/README.md)
- [本次分支整理规格与进度](docs/publication/TASKS.md)

CI 不使用云 Key，不发布 APK，不运行需要手机的感知验收。原有两个隐藏容器 Lint 错误有精确基线，
新增错误会阻断检查，警告保留。项目整体未新增 MIT／Apache 等授权；第三方组件遵循各自许可。
