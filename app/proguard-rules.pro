# Keep model/parser class names readable in crash logs.
-keep class com.livetv.model.** { *; }
-keep class com.livetv.parser.** { *; }

# --- IJKPlayer：native 层通过 JNI 直接按类名/方法名回调 Java 侧 ---
-keep class tv.danmaku.ijk.media.player.** { *; }
-dontwarn tv.danmaku.ijk.media.player.**

# --- libmpv（dev.jdtech.mpv）：native 方法与事件回调接口，反射探测 MPVLib ---
-keep class dev.jdtech.mpv.** { *; }
-dontwarn dev.jdtech.mpv.**

# --- AndroidTV 快捷方式 / 开机自启等由系统按组件名实例化（Manifest 保留规则已内置） ---

# 常见第三方依赖告警消除
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
