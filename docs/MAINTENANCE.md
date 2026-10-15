# 开发与交接

## 拉取与提交

从 `only-for-android` 创建工作分支，例如 `codex/fix-camera-state`。提交前使用 `git diff` 和
`git diff --cached` 核对范围；不要 `git add -f` 本地配置、构建结果或 SDK 下载包。
使用清晰、单一目的的提交说明，后续通过 PR 回到 `only-for-android`；不要直接覆盖其他分支。

`main` 保留原项目历史。本次发布为目标分支上的普通快进提交，旧文件从当前树移除但历史仍在。
正常 `git pull --ff-only` 可更新；本地已有改动先自行提交或暂存，不要求队友 reset 或重新建仓。
下载 ZIP／浅克隆也能运行源码保护，因为原始基线随仓库携带，不再依赖原始 commit 在本机存在。

## 检查顺序

1. `node --test tests/minimal-baseline.test.mjs`：11 项源保护，原 101 文件中 98 文件不变，三云适配器例外。
2. README 中 Gradle 命令：JVM、Debug／无密钥未签名 Release／测试包、Lint。
3. `node --test tests/native-apk.test.mjs`：3 项原生来源与两个实际 APK 检查；缺包不跳过。
4. 触及相机／语音／触觉行为时，另做仪器测试和必要真机／人工验证，不能把编译成功当成效果通过。

CI 固定 Actions 的完整 commit SHA，只有 `contents: read`，不接入云 Key；pull_request 使用普通事件，
不使用可执行不可信代码且带权限的 pull_request_target。缓存不应包含 local.properties 或工作区凭据。
只上传 JVM／Lint 报告，保留 14 天，不上传 APK 或整个 build 目录。

## Lint 与已知问题

两个原隐藏镜头／模式 LinearLayout 的 Orientation 错误记录在 `android/app/lint-baseline.xml`，
当前布局 188／200 行。只允许这两项，`abortOnError=true`；新增错误必须处理。
保留原已禁用的 NullSafeMutableLiveData 检测器（历史 Kotlin UAST 工具兼容问题），不扩大禁用范围。
未解决的警告继续显示，不批量刷新基线把它们隐藏。不要为了消除遗留警告重写原算法或布局。

## 原生依赖与许可证

保留原 OpenCV 4.9.0 及 NDK25.2 对应 C++ 运行库。只有经过另行批准、完整回归的依赖升级才可改动它们；
不要删除运行库或仅靠系统提供。二进制以普通 Git 管理，`*.so`／Wrapper JAR 标为 binary，不需要 Git LFS。
第三方许可证只覆盖相应组件，不将其当成本项目整体授权。见 THIRD_PARTY.md。

## 发布范围

当前树仅维护 Android 源码、必要原生依赖、测试、文档及 CI。不要恢复 Web／ESP32、影石模型素材、
SDK 压缩包、旧报告集合、设备现场照片、音频、内部项目记忆或带密钥包。
未来更换模型、算法、包名、默认层、无障碍操作或签名／分发方案均需单独说明和验收。
