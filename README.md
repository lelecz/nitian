# 逆天回复

安卓辅助类工具：无障碍读屏实时读取 QQ 群聊新消息 → 调用 AI（商汤 Sensenova）生成逆天回复 → 悬浮窗气泡展示建议 → 点击自动填入并发送。

## 功能

- **无障碍读屏**（核心）：系统控件树直读聊天文字，不依赖截图/OCR
- **悬浮窗气泡**：AI 建议弹出在屏幕顶部，点击自动发送
- **屏幕捕获（可选）**：控件树读不到时兜底；部分 ROM（如 ColorOS）系统不支持，可跳过
- **无需 root / Shizuku**

## 使用

1. 填 API Key（商汤：`sk-` 开头，`https://token.sensenova.cn/v1`，模型 `sensenova-6.8-flash-lite`）
2. 开启无障碍服务（点按钮自动收起气泡防遮挡；提示"应用遮挡"时用诊断找出来关掉）
3. 开启悬浮窗权限
4. 打开 QQ 聊天界面，新消息 → 读屏 → 气泡弹建议 → 点击自动发送

## 构建

```bash
./gradlew :app:assembleDebug
```

- minSdk 26（Android 8.0+）
- 需 JDK 17 + Android SDK

## 下载

最新 APK 见 [Releases](https://github.com/lelecz/ReReply/releases)。
