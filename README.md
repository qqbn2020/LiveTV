# 电视直播

独立电视直播 App：只做直播，不做点播、不做爬虫、不做投屏，兼容四类常见直播源，同时适配 Android TV 遥控器和手机触屏，最低支持 Android 5.0（API 21）。

- 应用名：电视直播
- 包名 / applicationId：`com.livetv`
- minSdk 21 / targetSdk 34 / compileSdk 34
- 播放引擎：三内核可切换 —— ExoPlayer 1.4.1 / IJKPlayer 0.8.8（FFmpeg）/ libmpv（FFmpeg + libass），设置里选择切换后自动重载

---

## 功能范围

### 已实现

- 直播源管理：多源保存、切换、编辑、删除、刷新；可读取网络地址、`content://` 本地文件、绝对路径文件
- 四类直播源自动识别：M3U、TXT（`分组,#genre#`）、XML（TVBox 格式）、JSON（FongMi 格式，含子源递归合并）
- 频道组织：分组列表、频道列表、频道号、多线路（同频道多 URL 依次降级）、频道收藏（虚拟「收藏」分组）
- 播放：**三内核可切换**（设置 → 播放设置 → 播放内核）
  - ExoPlayer 1.4.1：系统 MediaCodec 硬解，HLS / DASH / Progressive，RTMP / RTSP 单独处理
  - IJKPlayer 0.8.8：FFmpeg 软解 / MediaCodec 硬解可切，带 `framedrop`、超时与缓冲调优，画面比例用纹理矩阵实现
  - libmpv 0.6.1：FFmpeg + libass，软字幕能力最强，命令式控制（seek / 音量 / 倍速 / 画面比例）
  - 三个内核统一走 `PlayerEngine` 抽象，线路降级、回看、EPG、弹幕等上层逻辑与内核无关
- 线路切换：播放失败自动切下一条线路，全部失败后同一线路重试一次，再失败提示错误
- EPG 节目单：XMLTV 解析，`{date}` 日期模板（昨天/今天/明天合并），1 小时 TTL + LRU（最多 64 条）内存缓存，后台刷新不阻塞界面，按 `tvg-id` → `tvg-name` → 频道名 匹配，当前节目与下一档显示、进度条
- 回看（Catchup）：按 FongMi 的 catchup 语义实现，支持 `default`（模板即完整地址）与 `append`（模板拼到直播地址后），支持 `{utc}` / `{utcend}` / `{(b)格式}` / `{(e)格式}` / `{timestamp}` 等占位符与 `catchup-replace`
- TV 交互：遥控器上下切台、左右打开频道面板、OK 打开面板、长按 OK 打开节目单、菜单键进设置、数字键跳台、媒体键暂停/切台
- 手机交互：单击呼出面板、上下滑切台、双击呼出工具栏、长按频道弹出菜单（收藏/线路/节目单）
- 开机自启（可在设置里开关）、`livetv://add?url=...&name=...&epg=...&ua=...` 深链加源（仅允许 http/https/content，重复地址自动去重并切换）
- 直播时移（本地 DVR）：HLS 直播自动缓存到本地，生成可增长 EVENT 播放列表，支持暂停、-30 秒 / +30 秒、拖动进度与「回到直播」；AES-128 加密或不带滑动窗口的点播流自动回退直连；缓存有时间窗口（默认 10 分钟）+ 磁盘上限（200MB）双重裁剪，每个切片独立记录 MAP 以适配码流切换
- 解码方式：自动（硬解优先）/ 优先软解，切换后自动重载；开启 Media3 解码器回退
- 频道搜索（跨全部分组，结果置顶为临时分组，搜索结果内可收藏/隐藏）、分组长按隐藏/恢复、已隐藏分组显示开关、频道台标异步加载（按 96px 目标降采样 + 40 张 LRU 内存缓存）
- 局域网播放页：设置里开启后即时生效，手机/电脑浏览器访问 `http://电视IP:8899` 看频道列表并播放；`/playlist.m3u` 可导入其他播放器（含 EPG 地址），服务限制最多 24 并发连接，退出 App 自动停止
- 配置型源插件：兼容 FongMi 直播配置的 `lives`（`url` / `api` / `ext` / `jar` / `epg` / `ua` / `origin` / `referer` / `header` / `catchup` / 内联 `groups` / `urls`）与 TVBox 的 `urls` 源列表；`api` 为 HTTP 地址时按普通源拉取并解析，需 jar/js/py 运行时的源会跳过并在加载后提示
- 直播弹幕：频道带 `danmaku` / `danmaku-url`（M3U 属性或 JSON 字段）时，支持 B 站 XML 与 JSON 弹幕，按播放时间滚动显示，设置里可开关
- 横屏全屏沉浸、画面比例（适应/拉伸/裁剪）、缓冲时长可调、跨分组切台可开关
- 干净的直播界面：播放时默认只显示视频画面，不常驻频道信息条/时钟/节目预告；频道信息仅在按 INFO 键后临时显示 6 秒自动隐藏。ExoPlayer 关闭自带缓冲圈，mpv 关闭 OSD/OSC 叠加，切换频道时的短暂转圈为加载反馈

### 明确未实现（与 FongMi 的差异）

- 不包含点播（VOD）
- 不含 DLNA / 投屏；局域网仅有只读播放页与 m3u 导出，无遥控接口和网页管理
- 不含 Python、Node.js、QuickJS、迅雷、TVBus 等外置运行时与 Spider 协议；依赖这些运行时的 FongMi `jar/js/py` 源与 `spider://` 实时弹幕无法执行，只支持配置里自带 HTTP 地址或内联频道的源
- 弹幕只支持源里提供 XML/JSON 弹幕地址的频道，无法像 FongMi 那样由 Spider 实时生成弹幕
- 不含签名 Hook、WebView 嗅探

## 构建

工程已附带可直接构建的 `local.properties`（`sdk.dir` 指向本机 Android SDK）。换机器后改成自己的 SDK 路径即可。

1. 环境：JDK 17、Android SDK Platform 34、Build-Tools 34.0.0；
2. 命令行构建（Windows）：

```bat
set JAVA_HOME=C:\path\to\jdk-17
gradlew.bat assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`（约 7.4 MB）。

工程使用 AGP 8.6.1 + Gradle 8.7（wrapper 已配置腾讯镜像），Java 17 编译，纯 Java 无 Kotlin。

## 直播源格式

### M3U

```
#EXTM3U url-tvg="http://epg.example.com/e.xml"
#EXTINF:-1 tvg-id="cctv1" tvg-name="CCTV1" tvg-logo="http://logo.png" group-title="央视" tvg-chno="1",CCTV-1 综合
#EXTVLCOPT:http-user-agent=Mozilla/5.0
http://a.example.com/cctv1.m3u8
#EXTINF:-1 group-title="央视" catchup="append" catchup-source="?playseek={utc}",CCTV-2 财经
http://b.example.com/cctv2.m3u8
```

支持属性：`group-title` `tvg-id` `tvg-name` `tvg-logo` `tvg-chno` `catchup` `catchup-source` `catchup-replace` `http-user-agent`。
支持指令：`#EXTVLCOPT` / `#EXTHTTP` / `#KODIPROP`（UA、Header、Referer）。
URL 行可写 `http://...|Header=...&Referer=...` 追加请求头。

### TXT

```
央视,#genre#
CCTV-1,http://a.example.com/cctv1.m3u8
CCTV-2,http://b1.example.com/cctv2.m3u8#http://b2.example.com/cctv2.m3u8
卫视,#genre#
湖南卫视,http://c.example.com/hunan.m3u8
```

`名称,地址` 一行一个频道；同一频道多个地址用 `#` 分隔形成多线路；`分组,#genre#` 切换分组；`ua:` 等设置行会应用到后续频道。

### XML（TVBox）

```xml
<channels>
  <group title="央视">
    <channel name="CCTV-1" id="cctv1" logo="http://logo.png">
      <urls><url>http://a.example.com/cctv1.m3u8</url></urls>
    </channel>
  </group>
</channels>
```

### JSON（FongMi）

```json
{
  "lives": [
    {"name": "主源", "url": "http://example.com/live.txt", "epg": "http://example.com/epg.xml"}
  ]
}
```

也支持：

```json
{
  "groups": [
    {"name": "央视", "channels": [
      {"name": "CCTV-1", "urls": ["http://a.example.com/cctv1.m3u8"]}
    ]}
  ]
}
```

`channels` 内字段可用 `url`/`urls`（数组或 `#` 拼接字符串）、`tvgId`/`tvg-id`/`id`、`logo`、`ua`、`header`、`catchup`（对象或 `{...}` 字符串）。`lives` 子源最多递归 2 层，同名分组/频道自动合并线路。

## EPG 与回看

EPG 使用标准 XMLTV：

```xml
<tv>
  <channel id="cctv1"><display-name>CCTV1</display-name></channel>
  <programme start="20261125200000 +0800" stop="20261125213000 +0800" channel="cctv1">
    <title>新闻联播</title>
  </programme>
</tv>
```

- 时间支持 `yyyyMMddHHmmss +0800`、无时区本地时间、10 位秒级时间戳；
- 缺少 `stop` 时用下一条 `start`，或默认 +30 分钟；
- 频道匹配：`tvg-id` → `tvg-name` → 频道名（忽略大小写、空格、连字符）；
- 地址支持 `{date}`，会自动取昨天、今天、明天三份 XMLTV 合并。

回看按频道上的 `catchup` / `catchup-source` 生成地址：

| 写法 | 含义 |
| --- | --- |
| `catchup="default"` + 完整地址模板 | 模板本身就是播放地址 |
| `catchup="append"` / 其他值 | 模板拼到当前直播地址后面 |
| `{utc}` | 节目开始时间（UTC，`yyyyMMddHHmmss`） |
| `{utcend}` | 节目结束时间（UTC，`yyyyMMddHHmmss`） |
| `{(b)yyyyMMddHHmmss}` | 开始时间按指定格式 |
| `{(e)yyyyMMddHHmmss}` | 结束时间按指定格式 |
| `{timestamp}` / `{date}` | 开始时间戳 / 开始日期 |

`catchup-replace="正则,替换"` 会先对直播地址做一次替换，再拼接回看参数。

## 操作说明

### 遥控器（TV）

| 按键 | 作用 |
| --- | --- |
| 上 / 下 | 上一个 / 下一个频道（可跨分组） |
| 左 / 右 | 打开频道面板 |
| OK | 打开频道面板 |
| 长按 OK | 打开当前频道节目单（过去节目可回看） |
| 菜单键 | 进入设置 |
| 数字键 | 输入频道号，1.5 秒后自动跳转 |
| 播放/暂停键 | 暂停 / 继续 |
| 快退 / 快进键（媒体上一首/下一首） | 上一个 / 下一个频道 |
| 返回键 | 关闭面板 → 清除数字输入 → 关闭工具栏 → 双击退出 |
| INFO 键 | 呼出顶部信息条，6 秒后自动隐藏（播放界面默认干净，只显示视频） |

### 手机

- 单击画面：呼出频道面板；再次单击空白处关闭
- 上下滑动：切换频道；左右滑动：呼出面板
- 双击：呼出底部工具栏（频道 / 节目单 / 线路 / 设置）
- 长按频道：收藏、切换线路、节目单

## 添加直播源

1. 设置页「添加直播源」：填名称、地址（网络 URL 或本地文件）、EPG（可选）、UA（可选）；「选择本地文件」用系统文件选择器。
2. 深链：`adb shell am start -a android.intent.action.VIEW -d "livetv://add?url=http%3A%2F%2Fexample.com%2Flive.txt&name=测试&epg=http%3A%2F%2Fexample.com%2Fepg.xml"`，手机浏览器/二维码也可触发。
3. 设置页点击直播源即切换，长按编辑或删除；「刷新直播源」清空 EPG 缓存并重新加载。

## 工程结构

```
LiveTV/
├── build.gradle                    # AGP 8.6.1
├── settings.gradle
├── gradle/wrapper/                # Gradle 8.7
└── app/
    ├── build.gradle               # minSdk 21 / targetSdk 34
    ├── proguard-rules.pro
    └── src/
        ├── test/java/com/livetv/parser/  # JVM 单元测试（CatchupUtil / Model）
        └── main/
            ├── AndroidManifest.xml
            ├── java/com/livetv/
            │   ├── App.java            # Application
            │   ├── Store.java           # SharedPreferences 配置与收藏
            │   ├── model/               # Channel / Group / Playlist / EpgProgram / LiveSource
            │   ├── net/                 # Http（网络/文件读取、gzip、编码探测）
            │   │                        # LanServer（局域网播放页）、ProxyServer（本机 DVR 代理）
            │   ├── parser/              # PlaylistParser / EpgParser / CatchupUtil
            │   ├── data/                # LiveLoader / EpgRepository
            │   ├── player/              # PlayerController / HlsDvr / DanmakuView / DanmakuRepository
            │   ├── receiver/            # BootReceiver 开机自启
            │   └── ui/                  # MainActivity / SettingsActivity / LogoLoader
            │       ├── adapter/         # Group / Channel / Program / Source 适配器
            │       └── dialog/          # 源编辑、节目单、线路、频道菜单
            └── res/
                ├── layout / drawable / values
                └── mipmap-*/ic_launcher.png, drawable/banner.png
```

## 模拟器实测

已完成两次真机化验证（同一份 debug APK）：

| 设备 | 系统 | 结果 |
| --- | --- | --- |
| Android TV 模拟器（tv_1080p，1920×1080） | API 34 | 启动、加源、频道面板、EPG、播放、回看、数字跳台、线路降级全部通过 |
| 手机模拟器（Nexus 5，1080×1920） | Android 5.0（API 21） | 安装、启动、深链加源、EPG 与 HLS 播放通过；触摸手势修复后单击可呼出面板 |

已验证的具体路径：

- 深链 `livetv://add` 加源后自动加载并播放第一个频道；
- 三分组（央视 / 卫视 / 测试）与频道号（1 / 2 / 13 / 31 / 32）正确显示，频道面板右侧显示当前节目名；
- 顶部信息条显示当前节目、下一档节目与进度条；按数字 31 跳台成功；
- 第一条线路为 404 坏地址时，自动降级到第二条线路并播出（日志可见 `broken.m3u8` → 有效 m3u8）；
- 节目单弹窗显示时间与标题，过去节目带「回看」，选中后地址正确拼接为 `...m3u8?playseek=20261125060000`（UTC 14:00+0800）；
- 重启 App 后仍恢复上次播放的频道与分组；
- 手机端单击呼出面板、触摸选台；长按频道弹出「收藏 / 切换线路 / 节目单」菜单。

### 多格式直播源兼容性实测

使用多个公开渠道的示例源地址在 TV 模拟器（API 34）上实测，验证多格式解析与三内核播放（示例源仅用于格式兼容性验证，与本应用无关，此处不列出具体地址）：

| 示例源 | 格式 | 解析结果 |
| --- | --- | --- |
| 源 A（M3U） | M3U | 158 频道 / 3 分组（央视·卫视·地方），台标正常 |
| 源 B（TXT） | TXT | 频道正常加载（默认分组），`#genre#` 结构兼容 |
| 源 C（M3U） | M3U | 339 频道 / 11 分组，台标与频道号正常 |
| 源 D（TXT） | TXT | 11 分组全部识别，多线路标记「2线」正常，画面可播 |

结论：**M3U / TXT 两类公开源均正确解析**，分组、频道号、台标、多线路与 EPG 地址（`x-tvg-url`）识别正常；部分源在模拟器中因运营商限制或 SSL 握手失败导致 EPG / 单个频道流加载慢，属网络环境问题，非解析缺陷。三内核切换后均可加载这些源：ExoPlayer 与 IJK 首帧正常渲染（`first frame decoded` / `VIDEO_RENDERING_START`），mpv v0.41.0 正常出画。

本轮测试中发现并修复的问题：

1. Kotlin stdlib 传递依赖版本冲突导致 `checkDebugDuplicateClasses` 失败 → 统一 `kotlin-stdlib` 1.8.22；
2. `app:useController` 应为 Media3 的 `app:use_controller`，否则资源链接失败；
3. 分组列表适配器加载后未 `setItems`，面板左栏空白；
4. EPG 去重使用 `List.removeAll`，同一对象重复合并时会把整组节目删空 → 改为保留首条的去重；
5. 回看正则 `\{([^}]+)}` 在 Android ICU 下非法导致点击回看崩溃，且 `{utc}` 未实现 → 转义 `}` 并输出 UTC `yyyyMMddHHmmss`；
6. 手机触摸手势缺少 `onDown` 返回 true，单击/滑动/双击全部失效；
7. 面板打开时频道列表未刷新 EPG 文案；
8. mpv 内核切频道后 `time-pos` / `duration` 属性尚未就绪时被进度轮询反复读取，导致每 250ms 打印一条 `property unavailable` 错误 → 改为由 `eventProperty` 回调确认属性真正送值后才读取，实测错误日志从持续刷屏降为 0。

## 已知限制

- 三内核均不含 DRM；少数极冷门编码/容器仍可能无法解码（可切换到 IJK 或 mpv 内核尝试）。
- 直播时移仅对 HLS 生效，且**仅 ExoPlayer 内核支持**（IJK / mpv 直接连源，不做本地缓存）；需要能持续拉取切片，AES-128 加密流或单文件直播流会回退为普通直播。
- IJK / mpv 内核的包体较大（各含 FFmpeg native 库），APK 体积明显增加；切换内核会自动重建播放器并重载当前频道。
- mpv 内核以 `app/libs/libmpv-classes.jar` + `app/src/main/jniLibs/{abi}/` 集成（从上游 AAR 拆出，规避其 `minCompileSdk 36` 限制以兼容本工程 API 34）；含 arm64-v8a / armeabi-v7a / x86 / x86_64 四种 ABI。
- 单 APK 同时声明 `LEANBACK_LAUNCHER` 与 `LAUNCHER`，TV 与手机共用同一份界面；TV 上的文字输入依赖系统输入法（电视盒子通常无输入法，建议用深链或文件方式加源）。
- Android 5（API 21）对 TLS 1.2 服务端兼容性较差，个别 HTTPS 源可能握手失败；优先选择 HTTP 或较新系统设备。
- `usesCleartextTraffic=true` 是为了兼容大量 HTTP 直播源，如只使用 HTTPS 可自行收紧。
- EPG 与直播源解析都在子线程执行，但未做分页/懒加载；超大源（数万频道）首次加载会慢一些。
- 台标内存缓存以 URL 为键、不含 UA，同一 URL 在不同 UA 下可能命中同一张图片。
- 局域网播放页不设鉴权，同网段设备均可访问并读取直播源地址；仅在可信网络环境开启，不用时到设置里关闭。

## 代码检查与测试

已完成一轮覆盖全部源码（约 5,500 行 Java）的代码检查，并修复了下列功能正确性与健壮性问题：

- **直播源解析**：XML `<urls><url>` 嵌套结构、JSON `catchup` 的字符串形式、`#KODIPROP` 指令、`js`/`py`/`ext` 字段、全局 catchup 被频道级空值覆盖等问题；
- **EPG**：改用写时复制列表避免子线程刷新时遍历崩溃，缓存改 LRU + 对象复制防止污染，修复夏令时日期偏移与单频道 EPG 误套全部频道；
- **播放器 / 时移**：DVR 回退时未关闭的会话资源、`seen` 集合与磁盘缓存无上限、跨码流切换的 MAP 错配、回到直播的定位精度、解码/缓冲设置需重启才生效；
- **弹幕**：由墙钟时间改为跟随播放器位置，暂停/缓冲/时移不再与画面错位；
- **交互与设置**：INFO 键行为、局域网开关的即时启停、深链的 scheme 校验与去重、快速切源的竞态、删除源导致的使用中源漂移；
- **安全**：网络读取加字节上限、HTTP 响应头 CRLF 消毒、局域网与本机的并发连接上限、移除无用的通用代理路由、`allowBackup=false`。

验证方式（JDK 17 + SDK Platform 34 / Build-Tools 34）：

```bat
set JAVA_HOME=C:\path\to\jdk-17
gradlew assembleDebug
gradlew testDebugUnitTest
```

- `assembleDebug`：通过，产物 `app-debug.apk`（含三内核 native 库，约 61 MB）；
- `testDebugUnitTest`：通过，15 个 JVM 单元测试覆盖 catchup 时间模板替换、`$` 转义、分组/频道的去重与复制语义。核心解析层已抽离为纯 Java 类，便于后续继续补充测试。

## 常见问题

- **解析不到频道**：确认地址可访问、编码为 UTF-8 或 GBK；txt 源必须有 `分组,#genre#` 行；json 源需符合 `lives` 或 `groups` 结构。
- **频道没有节目单**：源未配置 EPG，或 `tvg-id` 与 XMLTV 的 `channel id` 不一致；也可直接在设置里为源填 EPG 地址。
- **回看点了没反应**：该频道未声明 catchup，或节目尚未结束；节目单里只有已结束节目才会触发回看。
- **播放失败**：多为源地址失效或需要特定 UA/Referer，可在源里设置 UA，或在 m3u 的属性中写 `http-user-agent`。



## 免责声明

- 本应用是一个**播放器工具**，本身不提供、不内置、不推荐任何直播源，也不包含任何频道内容；应用首次启动时需要用户自行添加直播源地址或本地文件。
- 用户通过本应用访问的所有内容均来自用户自行配置的第三方地址，与本项目开发者无关。
- 使用者应确保所添加的直播源已获得合法授权，并遵守所在国家/地区的法律法规；因使用不当产生的任何法律责任由使用者自行承担。
- 本项目仅供学习与技术交流使用，请勿用于任何商业或非法用途。
