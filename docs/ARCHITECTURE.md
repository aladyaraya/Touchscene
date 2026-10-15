# 架构与修改边界

本版本基于原 Touchscene `3eea391f32985ddd79f94a560fc1dabc27aa5b8c`，不是三页手机版重写。
本次发布只整理仓库、配置模板、基线和 CI，不改变应用功能。
原已批准的详细交互 [需求](specs/unified-accessible-controls/requirements.md) 与
[设计](specs/unified-accessible-controls/design.md) 随仓库保留，历史验证另见 ACCEPTANCE.md。

## 编译源集

`android/app/build.gradle.kts` 中 `phoneSourceDirs` 是权威入口：

- `app/src/mobile/java`：当前 Application、Activity、Preview、原 YUV 辅助类型。
- `app/src/main/java/com/insta360/kmpsdk/demo/{touchscene,widget,ui/localimage,phonecamera}`：
  原算法、触觉 View／session／语音，以及手机相机／首页适配。
- main 其他原 Demo 源码仅保留参考，不参与手机版构建；原影石 assets 不编译且不在本分支分发。
- `opencv-sdk`：原 OpenCV Java 封装／资源、原 arm64 库和匹配的 C++ 运行库。

不要因为 namespace 仍含 `insta360` 就修改原类名、包名或算法。安装 ID 是 `com.touchscene.minimal`。

## 数据流

CameraX 1.4.2 真正拍照／首页系统 OpenDocument 选图 → 既有 URI 解码和方向归一化 →
原 YUV／RGB 处理 → 原 OpenCV 边缘／GrabCut 主体与轮廓 → 冻结 session → 原 TactileMapView 与触觉。
AI 从冻结会话的原图生成描述，不读取绘制层；本地触觉不等待云请求。

拍照指令只产生一张 JPEG，保存相册和分析共用同一 URI。新图成功前保留旧地图。
地图接触期间不暗换图；旧图片描述、后台结果和取消的语音指令由原代际机制丢弃。
没有服务器、数据库或账号系统。图像上云须遵循当前应用同意机制和提供方配置。

## 手势与录音

TapHoldGesture 管理 500 ms 点按／长按边界；HoldToTalkSession 单独管理实际录音 300 ms 最小值和
30 s 取消上限。长按本身不拍照，录音开始前停播停震。滑出、多指、后台、退出、权限授权后的重新按住、
忙碌防重和串行录音清理均须保留。使用原 ManualWavRecorder 的 16 kHz 单声道 PCM16 WAV。

## 原生库故障必须显式处理

曾因缺少 `libc++_shared.so` 导致 OpenCV 加载失败并静默回退 Canny；旧的普通流程测试没发现此事。
现保留原 OpenCV 库，补 NDK r25c／25.2.9519653 对应 arm64 运行库。启动 JNI 预检，降级明确提示。
来源、精确 SHA-256 和 NOTICE 在 `android/opencv-sdk/`；APK 检查同时校验 ELF、依赖和实际加载段。
不得用更改参数、分割模型或备用 Canny 来“修复”缺运行库。
