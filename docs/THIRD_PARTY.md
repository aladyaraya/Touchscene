# 第三方组件与许可

本次整理不为 TouchScene 整体新增开源许可证，也不更改原源码中的归属或告知。
公开可见不等于所有第三方内容由本项目重新授权。

- OpenCV Java 封装版本为 4.9.0；原 `libopencv_java4.so` 保持原内容。
  官方 4.9.0 LICENSE 已附在 `android/opencv-sdk/licenses/OpenCV-4.9.0-LICENSE.txt`，
  来源：<https://github.com/opencv/opencv/blob/4.9.0/LICENSE>。
- arm64 `libc++_shared.so` 来自官方 NDK r25c／25.2.9519653，匹配原 OpenCV 的构建记录。
  `android/opencv-sdk/native-runtime-provenance.json` 记录官方归档、SHA-1／SHA-256、ELF build-id；
  `NDK-r25c-NOTICE.txt` 是同一归档完整原 NOTICE，保留 LLVM Exceptions 与相关第三方告知。
- Gradle Wrapper 8.11.1 JAR／脚本为构建入口；不是应用产物，不应因忽略 JAR／二进制而删除。
- 其余 AndroidX／CameraX、ML Kit、Material、Glide 等由 Gradle 解析，版本在版本目录和 app 构建文件中，
  其许可归各自组件。影石 SDK 二进制、影石模型／拼接素材和专用 Maven 凭据不在本分支分发。

原生库来源说明见 `android/opencv-sdk/NATIVE-RUNTIME-NOTICE.md`。
这些说明不是对原生二进制所有可能静态链接组件的全量法律审计；对外产品发布应另行完成依赖和许可审计。
