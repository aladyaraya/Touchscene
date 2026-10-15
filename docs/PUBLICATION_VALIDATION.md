# 分支发布验证

日期：2026-10-15。来源为当前手机版实际工作区，基线为原 `3eea391`。
本次只发布公开、无密钥的 Android 源码，不改变模型、算法、UI、包名或库版本。

## 本地发布前检查

- 稳定源码的独立副本构建成功（3 分 33 秒）：97/97 JVM；Debug、无密钥未签名 Release、测试 APK、Lint 均完成。
- Node 14/14 通过：11 项原内容／交互保护，3 项原生来源与 Debug／Release 实包检查。
- 424 个应用及 OpenCV 源码／资源文件与原开发工作区逐文件 SHA-256 比对，差异为 0；应用功能文件未改。
- Lint：0 个未基线化 Error、669 Warning、7 Information。其中一条信息明确报告仅过滤了两个原 Orientation Error；
  不把此结果解读成两项已修复。`abortOnError=true`，不新增检测器禁用。
- Debug／Release 的生成配置 endpoint／Key 均为空；公开树无真实本地配置。
- 463 个待提交文件，原分支当前树删除 683 文件、增加 51 文件、修改 18 文件；约 32.7 MB 工作区文件。
  655 个旧构建文件及 12 个影石素材均不再跟踪，旧内容仍保留在 Git 历史中。
- 暂存路径禁入检查为 0；文本 token／私钥模式和真实本地 Key 值扫描均为 0；公开文档无开发机绝对路径。
- OpenCV 4.9.0 LICENSE 与官方对应 Git blob 一致；原生库及 NDK NOTICE 保留原精确校验值。

本次不重新运行仪器、云端或手机测试，历史结果另见 ACCEPTANCE.md。
下方记录本次实际完成的远端验证；未来提交的 CI 请以对应提交的
[Actions](https://github.com/aladyaraya/Touchscene/actions/workflows/android.yml) 结果为准，未运行时不预记通过。

## 初次推送后的核对

初次发布提交 `60301d33f9d21024c84f06dc45a378225fcdfd0c` 的父提交仍是原 `3eea391`，
远端规范 tree SHA 与本地完全一致。全新远端 depth-1 浅克隆及不含 `.git` 的 ZIP 均通过 11 项源保护和
1 项原生来源检查；这两种未构建副本没有运行那两项需要 APK 的检查，不将其记为 14 项通过。
新副本无 local.properties，不依赖 Git LFS。

首次 CI 在 SDK 初始化阶段失败：固定版本的 setup-android 默认尝试安装已不存在的旧 `tools` 包，
尚未进入应用构建。现显式只安装 platform-tools，并保留后续 SDK35／Build Tools 安装步骤。
此修正仅涉及 CI，不改变应用；后续 CI 结果以关联提交的运行记录为准，不将首轮失败写成成功。

## 完成的远端与重新拉取验证

- CI 修正提交 `3b8f8dc972b4c937ae9af48f6210796de8aa1a24` 的
  [GitHub Actions 38756641266](https://github.com/aladyaraya/Touchscene/actions/runs/38756641266)
  **全部成功**：SDK、Wrapper 校验、源保护、JVM、三个 APK 编译、严格 Lint、原生实包检查和仅报告上传。
- 同一提交的全新远端浅克隆正常 `pull --ff-only` 更新后，在没有 local.properties 的条件下完整重建成功
  （3 分 13 秒），再次通过 97 JVM 与 14 项 Node；没有调用云服务或重新安装手机。
- 远端只更新 only-for-android；main 及其余 4 个开发分支均与操作前一致，仓库保持公开。
- 本次应用／OpenCV 源码与资源仍为 424 项、开发工作区差异 0。最终说明文档提交不改变应用内容，
  每个后续提交仍自动触发同一无密钥 CI；README 状态徽章反映最新运行状态。
- 公开 Git 不含 APK、生成配置、签名、构建输出或个人资料；初次失败保留为可审计记录，不隐藏或删除运行历史。
