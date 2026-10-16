# 配置、安全与安装

本分支是公开源码。`android/local.properties.example` 是唯一配置示例，不含真实 Key。
复制为 `android/local.properties`，让 Android Studio 设置 `sdk.dir`，或使用 `ANDROID_HOME`。
云字段如下，值从本机忽略提交的文件或显式 Gradle 属性读取：

| 字段 | 用途 |
| --- | --- |
| touchscene.ai.endpoint | 北京百炼 OpenAI-compatible chat completions 入口，见示例 |
| touchscene.ai.key | 自行申请、具有相应模型权限的百炼 Key；默认空 |
| touchscene.ai.model | 当前沿用 qwen3.8-flash，不表示未来始终是最新型号 |
| touchscene.ai.asrModel | 当前沿用 qwen-audio-3.0-asr-flash |

ASR 和系统 TTS 不可用时的既有 CosyVoice 后备，沿用当前适配器及同一百炼授权。
此仓库不放入提供方账号、工作空间私密地址、Key、签名证书或原影石 Maven 账号。
Key 失效时检查授权／模型开通情况，不擅自换服务。不要把 Key 放在命令行历史、Issue 或 CI。

## 构建不等于云配置完成

无 Key 可构建并测试本地流程。Release 强制将 endpoint 和 Key 清空，不提供线上云能力配置方案；
它也没有配置发布签名。不能把无密钥 Release 当成可直接分发的完整云功能产品。
填 Key 后的 Debug 会把 Key 编进 APK，仅内部测试；自行分发前应另行设计服务端凭据代理等发布方案，
本轮未实现，也未创建 GitHub Release。任何构建目录或 APK 都不得提交本仓库。

## 安装到自有测试设备

仓库发布不会自动安装手机。持机者明确要求更新后，先确认设备授权，再从 `android/` 执行：

```sh
adb -s YOUR_DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s YOUR_DEVICE_SERIAL shell am start -n com.touchscene.minimal/com.insta360.kmpsdk.demo.MainActivity
```

不要使用卸载／清数据来处理更新问题。OEM 拒绝 USB 安装时由持机者确认，不绕过设备保护。
ADB 日志、照片、录音和触摸轨迹仅在必要、授权的测试中采集，不随源码发布。

若曾将真实凭据提交到 Git，先吊销／轮换，并单独协调历史清理；只删除当前文件不能撤回历史暴露。
本次仅以正常提交更新目标分支，不重写全仓库历史。
