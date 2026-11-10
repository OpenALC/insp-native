# HarmonyOS 原生版

ArkTS / ArkUI 单机应用，包名 `com.openalc.inspnative`。与旧版 `com.openalc.inspnotes` 分开安装。

当前可在手机和平板布局中使用原生 RichEditor 普通笔记（标题、加粗、斜体、下划线、删除线、高亮）、灵感笔记列表／树形模式、分组、搜索、隐私区和暗色模式。支持从 `.txt` 导入文字、从 `.md` 导入基础行内格式和标题，以及通过系统文件选择器导出纯文本。富文本使用与 Android 相同的基础 JSON 格式；灵感笔记共用同一棵 JSON 树，切换视图不会丢失层级。标题和笔记保存在 HUKS AES-GCM 加密的设备偏好数据中；进入隐私区时调用系统身份验证，离开或切到后台后清除隐私区内容。

平板采用常驻侧栏、笔记列表和编辑区三栏。真机 Huawei MatePad Mini 上已验证安装、启动、富文本输入、灵感笔记分支新增与视图切换、强制结束进程后的内容恢复、删除、暗色模式和纯文本文件导出再导入。

目前 HarmonyOS 的图片、更多段落格式、旧版迁移 ZIP 和 Word／PDF／图片分享尚未接入。Android 版功能更多；两端数据库互不通用，也没有实时同步。

## 构建

使用本机 DevEco Studio API 26 SDK、JBR 和 Hvigor。首次构建前将 `build-profile.example.json5` 复制为 `build-profile.json5`。该文件被 Git 忽略，DevEco 自动签名时可写入本机证书路径和密码密文。

```powershell
$env:JAVA_HOME='D:\ide\Huawei\DevEco Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
& 'D:\ide\Huawei\DevEco Studio\tools\hvigor\bin\hvigorw.bat' --mode module -p product=default -p module=entry@default -p buildMode=debug assembleHap --no-daemon
```

未配置签名时输出 `entry-default-unsigned.hap`，真机需要 DevEco 自动生成的签名并在产品配置中启用 `signingConfig`，输出 `entry-default-signed.hap`。签名证书和配置不进入仓库。
