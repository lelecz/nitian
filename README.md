# 逆天回复

> v1.6.0 · Android 8.0+ (API 26+) · Kotlin · 无需 Root · 无需 Shizuku

一款基于 Android Accessibility Service 的群聊辅助工具：通过无障碍控件树实时读取 QQ / 微信 / 抖音 / 快手 的聊天上下文，接入任意 OpenAI 兼容接口生成多风格回复建议，以悬浮窗形式呈现，点击即可自动填入并发送。

---

## 目录

- [工作原理](#工作原理)
- [架构概览](#架构概览)
- [功能特性](#功能特性)
- [AI 接口接入](#ai-接口接入)
- [快速上手](#快速上手)
- [项目结构](#项目结构)
- [构建](#构建)
- [签名说明](#签名说明)
- [常见问题](#常见问题)
- [下载](#下载)
- [更新日志](#更新日志)
- [声明](#声明)

---

## 工作原理

逆天回复不依赖通知监听、不依赖截图 OCR，核心数据通路走 Android 的无障碍控件树（AccessibilityNodeInfo）。

```
用户授权无障碍服务
        │
        ▼
NotifyAccessibilityService 监听聊天应用的 WINDOW_STATE_CHANGED / WINDOW_CONTENT_CHANGED 事件
        │
        ▼
ScreenReader 遍历根节点，定位 RecyclerView / ListView 消息容器，采集 TextView 文本节点
        │
        ├─ 主路径：无障碍控件树直读（零耗时、零内存占用）
        │
        └─ 兜底路径：OcrManager 发起 MediaProjection 截图 + Tesseract (chi_sim) 中文识别
        │
        ▼
去重（内容哈希）+ 节流（1s）+ 自触发过滤
        │
        ▼
AiClient 组装 System Prompt（人设 + 抽象程度 + 软情趣 + 毒舌）
        │
        ▼
POST {baseUrl}/chat/completions（OpenAI 兼容协议）
        │
        ▼
解析 response.choices[0].message.content → 按行拆分为 3 条建议
        │
        ▼
ReplyFloatService 悬浮窗展示（WindowManager + SYSTEM_ALERT_WINDOW）
        │
        ▼
用户点击建议
        │
        ├─ autoSend = true → AccessibilityNodeInfo.ACTION_SET_TEXT 填入 → GestureDescription 点击发送按钮
        │
        └─ autoSend = false → 复制到 ClipboardManager
```

**为什么不需要 Root / Shizuku：**
自动发送通过无障碍服务的 `ACTION_SET_TEXT`（填字）和 `GestureDescription`（模拟点击）完成，这两项能力是 Android Framework 对无障碍服务的标准授权，无需更高权限。

---

## 架构概览

```
┌─────────────────────────────────────────────────────────────────────┐
│ UI Layer  (SettingsActivity, 悬浮窗 Layout)                          │
│  └── SettingsStore / BackupManager (SharedPreferences / JSON)       │
├─────────────────────────────────────────────────────────────────────┤
│ Service Layer  (Android Lifecycle)                                  │
│  ├── NotifyAccessibilityService  —— AccessibilityService            │
│  │     ├── 前台保活 (startForeground) + PARTIAL_WAKE_LOCK            │
│  │     ├── 事件监听 + 防抖 + 去重                                     │
│  │     └── 自动发送 (ACTION_SET_TEXT + GestureDescription)          │
│  ├── ReplyFloatService           —— Service + WindowManager          │
│  ├── AccessibilityGuard          —— AlarmManager + 恢复通知          │
│  ├── GuardReceiver               —— USER_PRESENT (解锁检查)          │
│  └── BootReceiver                —— BOOT_COMPLETED (开机自启)        │
├─────────────────────────────────────────────────────────────────────┤
│ Domain Layer                                                        │
│  ├── ScreenReader   —— AccessibilityNodeInfo 遍历 / 清洗 / 快照      │
│  ├── OcrManager     —— MediaProjection + Tesseract                  │
│  └── Persona        —— 15 个人设枚举（含 System Prompt 覆盖）       │
├─────────────────────────────────────────────────────────────────────┤
│ Data Layer                                                          │
│  ├── AiClient       —— OkHttp + 协程 + OpenAI 兼容协议              │
│  └── StatsManager   —— 用量统计 / token 消耗 / 7 天趋势             │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 功能特性

### 核心能力

| 特性 | 说明 |
|---|---|
| 无障碍读屏 | 基于 AccessibilityNodeInfo 遍历控件树，实时提取聊天界面消息上下文 |
| OCR 兜底 | 主路径不可用时（如特殊界面、图片消息）自动切 MediaProjection + Tesseract chi_sim |
| AI 自动回复 | OpenAI 兼容协议（Chat Completions），按行解析返回，输出 3 条建议 |
| 悬浮窗交互 | WindowManager + SYSTEM_ALERT_WINDOW，支持拖动位置、点选发送、刷新、关闭 |
| 自动发送 | 优先匹配屏幕底部 EditText 填入文本，匹配"发送/Send" 按钮点击；失败回退到回车坐标模拟 |
| 手动模式 | autoSend = false 时仅复制到剪贴板，不触发点击 |
| 模型列表拉取 | 设置页一键调用 `GET /models` 获取服务商可用模型，可滑动列表点选回填 |
| 延迟显示 | 测试 AI 连接时显示往返延迟（毫秒），成功/失败均可看到 |

### 可调参数

| 参数 | 范围 | 作用 |
|---|---|---|
| 抽象程度 (craziness) | 1-5 | 从正常礼貌到极致抽象，控制回复风格的"逆天"强度 |
| 软情趣 (flirty) | 1-10 | 从朋友距离到撩人天花板，控制暧昧浓度；有底线约束 |
| 毒舌程度 (toxic) | 1-10 | 从纯夸不损到阴阳拉满；有底线约束，不人身攻击、不揭伤疤 |
| 人设 (persona) | 15 个枚举值 | 覆盖 System Prompt 的角色覆盖，详见下方 |
| 响应超时 (timeoutSec) | 10~300 秒（步进 10，默认 60） | 模型响应等待上限；连接超时固定 15 秒 |
| autoSend | Boolean | 点选后自动发送 or 仅复制到剪贴板 |
| groupFilter | String | 逗号分隔的群名关键词，仅匹配群聊触发 |

### 人设包 (Persona)

| 分类 | ID | 角色 |
|---|---|---|
| 默认 | normal | 无人设覆盖，通用群聊气氛组 |
| 星穹铁道 | silverwolf / kafka / blade / sparkle / march7th | 银狼、卡芙卡、刃、花火、三月七 |
| 龙族 | caesar / finger | 凯撒·加图索、芬格尔 |
| 原创 | nyaruru | 喵露露（《咸鱼喵喵》主角） |
| 性格类 | catgirl / yandere / brat / tsundere / naive | 猫娘、病娇、雌小鬼、傲娇毒舌、幼稚单纯 |
| 搞笑 | eagle | 神鹰哥 |

每个人设在 `Persona.systemAppend` 中定义独立的 System Prompt 追加段，覆盖词汇、语气、角色行为边界。

### 守护与保活

Android 系统对后台服务有严格限制，逆天回复通过 5 层机制维持无障碍服务的存活：

1. **前台服务** —— `onServiceConnected` 时 `startForeground()`，通知栏常驻，进程优先级提升
2. **WakeLock** —— `PARTIAL_WAKE_LOCK` 常持，阻止 Doze 模式下 CPU 休眠导致无障碍挂起
3. **AlarmManager 定时检查** —— 守护进程周期性检测无障碍服务是否存活
4. **USER_PRESENT 解锁检查** —— 屏幕亮起时重新检查状态
5. **BOOT_COMPLETED 开机自启** —— 设备重启后自动恢复
6. **销毁后四重检测** —— `onDestroy` 后 500ms / 2s / 5s / 10s 递进检测，快速弹恢复通知

### 设置管理

| 功能 | 实现 |
|---|---|
| 设置持久化 | SharedPreferences (`reply_settings`, MODE_PRIVATE) |
| 导出/导入 | `exportAll()` / `importAll()` 全量 JSON 序列化，换机恢复 |
| 用量统计 | StatsManager 记录回复次数 + token 消耗，展示 7 天趋势柱状图 + 常用人设 Top5 |

### 兼容支持

| 聊天应用 | 识别逻辑 |
|---|---|
| QQ | 包名匹配 `mobileqq` / `tencent.mobileqq`，排除 `qqmusic` |
| 微信 | 包名匹配 `weixin` / `wechat` |
| 抖音 | 包名匹配 `aweme` / `ugc.aweme` |
| 快手 | 包名匹配 `gifmaker` / `kuaishou` |

---

## AI 接口接入

### 协议

遵循 OpenAI Chat Completions API 协议：

```
POST {baseUrl}/chat/completions
Content-Type: application/json
Authorization: Bearer {apiKey}

{
  "model": "{model}",
  "messages": [
    { "role": "system", "content": "{systemPrompt}" },
    { "role": "user",   "content": "{userPrompt}" }
  ],
  "temperature": 0.95,
  "max_tokens": 10000
}
```

### 参数填写要求

| 字段 | 要求 | 示例 |
|---|---|---|
| Base URL | **必须** 以 `/v1` 结尾；**不得** 包含 `/chat/completions` 路径 | `https://token.sensenova.cn/v1` |
| API Key | `sk-` / `Bearer ` 前缀格式；禁止填入 URL | `sk-xxxxxxxxxxxxxxxx` |
| Model | 与服务商平台提供的模型名完全一致 | `sensenova-6.8-flash-lite` |

### 兼容服务商

任何提供 OpenAI 兼容协议的接口均可：商汤 (SenseNova)、火山引擎 (Doubao)、智谱 (GLM)、DeepSeek、阿里通义千问、月之暗面 (Moonshot)、自建 One-API / New API 聚合，以及 OpenAI 官方。

### Prompt 构造

System Prompt 由 4 段动态拼装：

```
{crazyDesc}      // 抽象程度档位 1-5 的文本描述
{flirtyDesc}     // 软情趣档位 1-10 的文本描述
{toxicDesc}      // 毒舌程度档位 1-10 的文本描述
{personaAppend}  // 人设专属的 System Prompt 覆盖（可选）
```

User Prompt 结构：

```
群聊最近记录：
{chatContext.takeLast(8).joinToString("\n")}

最新消息：{lastMessage}

请给出 3 条回复建议：
```

### 响应解析

```kotlin
// AiClient.parseReplies()
json.optJSONArray("choices")
    ?.optJSONObject(0)
    ?.optJSONObject("message")
    ?.optString("content", "")
    .split("\n")        // 按行拆分
    .map { it.trim() }
    .filter { it.isNotBlank() }
```

---

## 快速上手

### 前置条件

- Android 8.0+ (API 26)
- 可访问的 OpenAI 兼容 API 端点

### 步骤

**1. 配置 AI 接口**

打开应用 → 设置页 → 填写 Base URL / API Key → 点「拉取模型」从列表中选择模型（也可手动输入）→ 点"测试 AI 连接"验证（会显示往返延迟）。

**2. 开启无障碍服务**

设置页 → "开启无障碍服务" → 系统无障碍列表 → 找到"逆天回复" → 打开开关。

若系统提示"应用遮挡"，使用设置页的"诊断"功能定位遮挡悬浮窗并关闭；或重启设备后立即开启。

**3. 开启悬浮窗权限**

设置页 → "开启悬浮窗权限" → 系统悬浮窗管理界面 → 允许"逆天回复"。

**4. 使用**

打开 QQ / 微信 / 抖音 / 快手 → 进入任意聊天 → 等待消息触发 → 点选悬浮气泡建议 → 自动发送或复制。

---

## 项目结构

```
app/src/main/java/com/lelecz/reply/
├── ai/
│   ├── AiClient.kt          OkHttp 客户端 + 协程 + OpenAI 兼容协议请求/响应
│   └── Persona.kt           人设枚举（15 个）+ System Prompt 构造
├── service/
│   ├── NotifyAccessibilityService.kt   核心服务：无障碍监听 + 读屏 + 自动发送
│   ├── ReplyFloatService.kt            悬浮窗：WindowManager + 交互
│   ├── ScreenReader.kt                 无障碍控件树遍历与聊天快照提取
│   ├── OcrManager.kt                   MediaProjection + Tesseract OCR 兜底
│   ├── AccessibilityGuard.kt            守护检查 + 恢复通知
│   ├── GuardReceiver.kt                 USER_PRESENT 广播接收
│   └── BootReceiver.kt                  BOOT_COMPLETED 广播接收
├── ui/
│   └── SettingsActivity.kt              设置页
├── data/
│   ├── SettingsStore.kt                 SharedPreferences 设置读写
│   ├── BackupManager.kt                 JSON 导入/导出
│   └── StatsManager.kt                  用量统计 + token 消耗记录
├── App.kt                 Application 子类 + 全局崩溃捕获
└── AndroidManifest.xml
```

### 依赖版本（gradle/libs.versions.toml）

| 依赖 | 作用 |
|---|---|
| okhttp3 | HTTP Client |
| kotlinx-coroutines-android | 协程调度 |
| tesseract4android | OCR 兜底（chi_sim.traineddata 随包） |
| androidx.core / appcompat | AndroidX 基础库 |

---

## 构建

```bash
./gradlew :app:assembleDebug
```

**环境要求：**

| 项 | 值 |
|---|---|
| JDK | 17 |
| compileSdk | 35 |
| minSdk | 26 (Android 8.0) |
| targetSdk | 35 |
| Gradle Wrapper | 已内置 |

### 签名

debug 与 release 构建共用同一套签名，覆盖安装不提示签名不一致。**明文 keystore 不在仓库中**，仓库只保存加密版 `release.keystore.enc`（OpenSSL AES-256-CBC + PBKDF2）。

| 项 | 值 |
|---|---|
| 签名文件 | `release.keystore`（仅本机，已被 gitignore） |
| 仓库内备份 | `release.keystore.enc`（加密版） |
| Alias | `rereply` |
| 算法 | RSA 2048 + SHA256（V1/V2/V3 签名方案） |
| 有效期 | 36500 天 |
| SHA256 指纹 | `C0:62:BF:98:CC:DF:79:7E:CD:76:0F:58:80:10:9F:FD:8E:A2:EF:50:9D:E7:62:3D:41:CB:C8:E2:40:72:20:16` |

**本机构建**：直接 `./sign_and_build.sh`——本地已有明文 keystore 就直接用；没有则自动从 `.enc` 解密后构建并验签。

**手动恢复密钥库**：

```bash
openssl enc -d -aes-256-cbc -pbkdf2 \
  -in release.keystore.enc -out release.keystore
```

解密口令与构建签名口令不公开，联系作者获取。

> 历史版本（v1.8.0 及更早）曾将签名文件与口令直接提交在仓库中，该凭据已作废重置；请拉取最新代码构建。上架应用商店请自行重新生成并妥善保管签名。

---

## 常见问题

**Q：点"授权屏幕截图"直接退回桌面？**
部分手机系统（如 ColorOS / OPPO / OneUI 旧版）不支持 MediaProjection 授权流程，属于系统级限制，无法绕过。不影响主功能——无障碍读屏不依赖截图，跳过即可。

**Q：测试 AI 连接报 HTTP 404 / 405？**
检查 Base URL 是否以 `/v1` 结尾（例：`https://token.sensenova.cn/v1`）；检查 Model 名是否与服务商文档完全一致。

**Q：开启无障碍后仍读不到消息？**
1. 系统设置中确认无障碍开关真的处于打开状态（部分 ROM 会定时自动关闭）
2. 确认悬浮窗权限已开启
3. 重启应用或重启设备后再次尝试

**Q：系统提示"应用遮挡"无法开启无障碍？**
Android 无障碍设置页不允许有任何悬浮窗覆盖。使用设置页"诊断"功能定位遮挡应用的悬浮窗并关闭；或重启设备后第一时间进入无障碍设置。

**Q：隐私安全？**
应用仅在本地无障碍控件树层面读取用户正在查看的聊天界面文本，请求直接发往用户自行配置的 AI 接口。不采集设备 ID、不跨应用读取其他数据、不向任何第三方上传。

---

## 下载

最新 Release APK 见 [GitHub Releases](https://github.com/lelecz/nitian/releases)。

---

## 更新日志

### v1.9.0
- 内容分级管理：设置页输入管理员密码解锁 18R（包内仅存 SHA-256 哈希，连错 5 次锁 60 秒，可随时撤销）
- 18R 状态显式化：回复风格卡新增「18R状态」行，仅「已解锁 + 软情趣 10 级」同时满足才切换成人向措辞，状态所见即所得
- OCR 屏幕兜底大修：
  - 安卓 10~13 修正 FGS 启动顺序（投影获取后再启动），修复授权后 5 秒必崩的 RemoteServiceException
  - VirtualDisplay + ImageReader 常驻复用，修复第二次截图永远失败
  - 投影注册 onStop 回调，系统侧停投可感知；节流 / 没等到帧 / 授权失效等失败原因全部透传到管线诊断
  - 自动发送内容标记为已处理，修复潜在的自回复循环

### v1.8.0
- 回复管线可视化：处理过程悬浮窗顶部显示状态胶囊（读取控件树 → AI 生成中），点击穿透不挡操作
- 设置页新增「管线诊断」：最近 5 次链路逐步耗时、标红最慢一步、跳过原因（防抖 / 自己发的 / OCR 兜底）全标注

### v1.7.1
- 底部导航栏：主页 / API / 设置三页 Tab 直达
- 壁纸边到边全覆盖（状态栏区域含内）+ 半透明玻璃卡片

### v1.7.0
- 三页拆分：主页（总开关）/ API 配置 / 应用设置，共用 BaseActivity 骨架
- 全部弹窗统一为液态玻璃样式（Android 12+ 窗口级模糊）
- 自定义壁纸：SAF 选图、内部存储不进相册、三页共享、自动蒙层保证可读性
- 主页总开关点击即时生效（修复开关无响应的历史 bug）

### v1.6.0
- 新增模型列表拉取：设置页「拉取模型」调用 `GET {baseUrl}/models` 获取可用模型，可滑动列表点选回填
- 测试 AI 连接显示往返延迟（成功/失败均显示延迟毫秒数）
- 新增响应超时自定义：滑条 10~300 秒（步进 10），默认 60 秒与原固定值一致，后台服务改设置后即时生效
- 「拉取模型」与「保存设置」并排一行

### v1.5.0
- 新增人设包「喵露露」（《咸鱼喵喵》主角），人设总数达 15 个
- 新增设置 JSON 导入/导出：换机 / 重装后一键恢复 API 配置与风格参数
- 新增加量统计：今日 / 累计回复数、累计 token 消耗、7 天趋势柱状图、常用人设 Top5
- AiClient 解析 `usage.total_tokens` 并写入 StatsManager

### v1.4.0
- 人设包扩充至 14 个：花火、三月七、凯撒、芬格尔
- ScreenReader 新增抖音 (aweme)、快手 (gifmaker / kuaishou) 包名匹配
- 自动发送优化：输入框选屏幕最底部 EditText；发送按钮同时匹配"发送" / "Send"

### v1.3.0
- 人设包新增「傲娇毒舌」「幼稚单纯」
- 毒舌程度独立滑块 (1-10)
- 提示词注入毒舌底线：不人身攻击、不揭伤疤、不骂脏话

### v1.2.0
- 人设包新增「银狼」「卡芙卡」「刃」（星穹铁道系列）
- 无障碍服务升级为前台服务，降低后台被杀概率
- 新增 PARTIAL_WAKE_LOCK 防 Doze
- 守护检测升级为 500ms / 2s / 5s / 10s 四重递进
- 首次引入项目专属签名
- Manifest 声明 `foregroundServiceType="specialUse"` 合规

### v1.1.8
- max_tokens 提升至 10000
- 悬浮窗拖动 ClassCastException 修复
- 读屏去重优化（仅比较最新一条消息）
- 读屏节流缩短至 1s
- API 异常时悬浮窗直接展示原始返回，无需抓 logcat

### v1.1.7 — v1.1.1
- 悬浮窗拖动崩溃修复
- AI 返回多级兜底解析
- max_tokens / 单条回复长度放宽
- 读屏协程与 AI 请求协程分离，修复"不主动读屏"回归
- 协程取消与真实异常分离，消除误报
- vivo / OriginOS 系统自动关闭无障碍的防护
- 悬浮窗刷新按钮新增

---

## 声明

- 本工具仅用于娱乐与学习，请勿用于骚扰他人或违反平台规则
- AI 生成内容由模型决定，使用后果自负
- 不同 ROM 对权限管理有差异，如遇问题请先查阅上方 FAQ
