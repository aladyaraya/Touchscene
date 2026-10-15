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
随提交记录的是推送前快照；发布后的 CI 请以对应提交的 [Actions](https://github.com/aladyaraya/Touchscene/actions/workflows/android.yml)
结果为准，未运行时不预记通过。远端分支／重新拉取结果在交接时另行报告。
