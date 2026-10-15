# 应用图标验证记录

## 变更边界

基于 `b797b619c828751486df0bedd34b819269918da8`。只更换 launcher 资源并增加母版、
生成工具、资源检查和维护说明；Manifest、应用名称、包名、功能源码、图像算法、原生库、
Gradle 依赖版本、原基线及 Lint 豁免不变。

## 本轮软件检查

- 人工查看生成的预览：圆角、圆形、浅／深主题示例及 24／32／48／64／96px，
  图形与触点保留间隔；标志符合 108dp 画布、中央 66dp 圆形安全区。
- `python tools/generate_icons.py --check`：18/18 资源与定义一致，无写入。
- Node：20/20，包括原源码保护 11、图标检查 6、原生来源及实际 APK 检查 3；无跳过。
- JVM：97/97，失败／错误／跳过均为 0。
- Debug、无密钥未签名 Release、Android 测试 APK 构建和严格 `lintDebug` 完成；
  本轮成功构建耗时 1 分 43 秒，218 任务（49 执行、169 无需重跑）。
- Lint：0 个未基线化错误、669 警告、7 信息；只过滤既有两个 Orientation 错误，未改基线。
- Debug／Release 生成配置的 endpoint 和 Key 为空；项目未新增本地配置。
- 源码及 OpenCV／C++ 库、Manifest、Gradle 配置、原基线与 Lint 豁免相对父提交无差异。
- Debug 和 Release 资源表均保留五个逻辑资源、15 个文件配置（10 PNG、5 XML），
  Release 的资源路径由原 AAPT 优化重命名，通过资源表解析核对，不依赖 ZIP 内的原文件名。

初版图标检查曾错误地只按轮廓宽度判断标志大小，遗漏独立触点；现检查完整标志。
小尺寸 PNG 的 LANCZOS 重采样存在最多三阶颜色偏差，检查保留透明度及合理的颜色容差，
并另以生成像素完全一致检查防止资源漂移。首轮 PowerShell 构建参数被拆分，
在未执行任务前失败；引号修正后成功，不属于 Android 代码错误。

本文件记录本地实际结果；推送后的 CI 请查看对应提交的
[Android checks](https://github.com/aladyaraya/Touchscene/actions/workflows/android.yml)，不预记通过。

## 明确未运行

- 未安装／卸载应用，未清除手机数据。
- 未重新运行仪器测试、真实拍照、实际听音／振感和 TalkBack。
- 桌面图标预览不等于各厂商桌面的真机显示验收。
