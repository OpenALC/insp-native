# 拾页 Native

拾页的移动端原生单机版。当前先开发 Android；`harmonyos/` 留给后续 ArkTS 实现。旧版拾页仍在 `../insp`，两版包名和本地数据彼此独立。

## Android 当前进度

- Kotlin 原生界面，`RecyclerView` 自适应高度卡片、侧栏、分组、搜索、浅色与暗色外观。
- 普通笔记支持本地编辑、加粗、斜体、下划线、引用、列表、对齐、图片、纯文本分享；灵感笔记使用同一棵树在列表和思维树之间切换，可编辑与调整节点层级。
- SQLite 保存笔记与附件；内容、标题、分组名和图片使用 Android Keystore 管理的 AES-GCM 加密。隐私区进入时调用系统身份验证，退出或切到后台时清除会话密钥。
- Android 版无 WebView、Rust 动态库或网络权限，包名为 `com.openalc.inspnative`，不会覆盖旧版拾页。

## 与旧版拾页迁移数据

旧版拾页的「设置 → 导出到拾页 Native」生成迁移 ZIP。Native 的「设置 → 从拾页导入」选择该文件。迁移包含可见的普通笔记、已解锁的隐私笔记、分组、灵感笔记节点和图片。Native 端按原笔记 ID 导入；再次导入会跳过已存在的笔记。旧版数据不会被删除。

迁移 ZIP 含**明文**，应存放在可信位置，导入后由用户自行处置。当前是手动单向迁移，两版没有实时共用数据库。富文本导入保留常见的文字标记、段落、标题、引用、列表、链接和图片；旧版编辑器的高级节点尚未全部映射，迁移文件保留原始内容结构。

## 构建

在 `android/` 运行 `./gradlew assembleDebug`（Windows 使用 `gradlew.bat`）。需要 JDK 17+ 和 Android SDK 36。调试 APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。

当前为早期可运行版本。Markdown/Word/PDF 导出、批量多选、完整富文本工具栏、平板布局和 HarmonyOS 端尚未迁入 Native。
