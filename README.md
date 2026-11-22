# Tessera

一个为 8.8 英寸安卓平板定制的 iPad 风格桌面。纯 Android View + Canvas 手写，没有 AndroidX、没有 Compose，安装包约 100 KB。

在 Lenovo Y700（TB323FU，Android 16，ZUI）上开发和测试。代码是原创实现，与 Apple、Lenovo 没有关联。

![主屏](docs/screenshots/home.jpg)

## 截图

| 小组件页 | 资源库 |
|---|---|
| ![小组件页](docs/screenshots/today.jpg) | ![资源库](docs/screenshots/library.jpg) |

编辑模式：

![编辑模式](docs/screenshots/edit.jpg)

![搜索](docs/screenshots/search.jpg)

截图里的应用图标和商标属于各自的权利人。

## 功能

- 连续曲率圆角图标（半径 22.37%），老式方形图标会裁边后满铺
- 悬浮毛玻璃 Dock，右侧显示最近使用的应用
- 通知红点角标
- 小组件页：内置时钟、日历、电池，也能放第三方小组件
- 资源库：应用按类别自动分组，第一格是“高频”，按最近 14 天的打开次数排序，3 天前的一次打开只算半次
- 下拉搜索，支持拼音和首字母
- 文件夹、长按快捷菜单、抖动编辑模式、拖拽排序
- 状态栏图标颜色跟随壁纸亮度切换
- 横竖屏自适应：横屏 6×5，竖屏 5×6

## 内存

在 Y700 上用 `dumpsys meminfo` 测得的 PSS：

| | Lawnchair | Tessera |
|---|---|---|
| 桌面在前台 | 约 340 MB | 约 90 MB |
| 退到后台 | 未测 | 约 32 MB |

前台数字里约 65 MB 是系统给全屏窗口分配的显示缓冲。

做法：

- 整个主屏是一个 View，不是一个图标一个控件
- 图标存在 GPU（`Bitmap.Config.HARDWARE`），LRU 上限 9 MB，退到后台降到 3 MB
- 不解码整张壁纸，毛玻璃只采样一张 1/16 的模糊小图
- 文件夹、搜索、菜单打开时才创建，关闭就销毁
- 第三方小组件只为可见页和相邻页创建 View
- 图标渲染结束后主动回收，否则解码的大图会在 native 堆积到约 100 MB

## 构建与安装

需要 JDK 17 和 Android SDK 36。

```bash
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

授权并设为默认桌面：

```bash
adb shell appops set app.homepad GET_USAGE_STATS allow
adb shell appops set app.homepad MANAGE_EXTERNAL_STORAGE allow
adb shell appwidget grantbind --package app.homepad --user 0
adb shell cmd notification allow_listener app.homepad/app.homepad.BadgeService
adb shell cmd role add-role-holder android.app.role.HOME app.homepad
adb shell am force-stop com.zui.launcher
```

包名是 `app.homepad`，这是项目改名前的名字，保留它是为了能覆盖安装。

首次运行的默认布局写在 `Model.kt` 的 `seed` 里，可按自己的应用改。

## 下拉面板

`shade/` 用 fabricated overlay 把 ZUI 的控制中心和通知改成 iOS 风格：面板从约 382dp 加宽到 640dp，通知卡片换成大圆角和半透明磨砂，并改成左侧 40dp 大图标、右侧文字的排版。不改系统文件，需要 root。

```bash
shade/build.sh
adb push shade/build/shade.dex /data/local/tmp/shade.dex
adb shell "su -c 'CLASSPATH=/data/local/tmp/shade.dex app_process / Shade'"
```

首次应用后要重启一次 SystemUI，媒体卡片才会按新宽度排版。还原时把最后一条命令的 `Shade` 换成 `Shade off`。

`panel/` 是独立的控制中心和通知下拉，`settings/` 是 iPad 风格设置。`daemon/` 是 root helper（`tesserad`），开机由 Magisk `service.d` 拉起：

```bash
daemon/install.sh <adb-serial>
```

## ZUI 上的注意事项

- 系统的手势上滑和多任务由 `com.zui.launcher` 提供，不能卸载它。只禁用桌面入口：

  ```bash
  adb shell su -c 'pm disable com.zui.launcher/.drawer.NormalLauncher'
  ```

- 换默认桌面后要重启一次 ZUI Launcher，否则上滑还会回到旧桌面。
- ZUI 会弹窗询问“读取应用列表”，允许之前系统返回的应用列表是空的。

恢复 ZUI 桌面：

```bash
adb shell su -c 'pm enable com.zui.launcher/.drawer.NormalLauncher'
adb shell cmd role add-role-holder android.app.role.HOME com.zui.launcher
```

## 字体

截图使用阿里巴巴普惠体 3.0，由设备上的 Magisk 模块全局替换。字体不在这个仓库里，请到[官网](https://www.alibabafonts.com/)下载。不装这个字体，桌面也能正常运行。

## 已知限制

- 状态栏、通知中心、控制中心、锁屏和多任务界面仍是系统的
- 这台设备不支持跨窗口模糊，毛玻璃是壁纸采样，不会模糊背后的图标
- 拖拽排序和添加小组件还没在真机上完整测试

## Android 11（API 30）适配分支

本仓库是 [biyan113/Tessera](https://github.com/biyan113/Tessera) 的 fork，把最低版本从 API 33 降到
**API 30（Android 11）**，并在 Lenovo Tab P11（TB-J606F，Android 11）上实测通过：横屏 6×5 网格、
悬浮 Dock、小组件页（时钟/日历/电池）均正常，**重启后自动作为桌面启动，无崩溃**。
配套的 `panel`（iOS 风格控制中心）和 `settings`（iPadOS 风格两栏设置）也在这个版本上验证可用。

只改 `minSdk` 不够——原代码里直接调用了 API 31/33 才有的方法，运行期会抛
`NoSuchMethodError`。本分支补了版本判断：

| 位置 | API | 处理 |
|---|---|---|
| `HomeActivity.onBackInvokedDispatcher` | 33 | `SDK_INT >= 33` 走新回调；旧版改用 `onBackPressed` |
| `HomeActivity.registerReceiver(..., RECEIVER_EXPORTED)` | 33 | 旧版退回两参 `registerReceiver(receiver, filter)` |
| `HomeActivity.blurTo` / `HomeView` 的 `setRenderEffect` | 31 | `SDK_INT >= 31` 才设置，旧版不改模糊 |
| `panel.Sheet` 的 `setBackgroundBlurRadius` | 31 | 同上，旧版跳过 |
| `settings.SettingsActivity` 的 `onBack` 字段 | 33 | **字段类型改成 `Any?`**，见下 |
| `panel.Sheet` / `settings.SettingsActivity` | 33 | 旧版用 `onBackPressed()` 兜底 |

**有个坑值得单独说：** `SettingsActivity` 原本写成
`private val onBack = OnBackInvokedCallback { ... }`。字段的类型是 API 33 的接口，
所以 Activity 类**加载时**就要解析它，Android 11 上直接
`NoClassDefFoundError: Failed resolution of Landroid/window/OnBackInvokedCallback;`
——这种崩溃发生在 `Class.newInstance` 阶段，`if (SDK_INT >= 33)` 包起来也没用
（判断代码还没跑到）。必须把字段类型声明成 `Any?`，只在 API 33+ 的分支里赋
`OnBackInvokedCallback` 实例，调用处再 `as` 回转。

依赖库的 release 构建也一并关闭 R8（`isMinifyEnabled = false`）：`panel` 和 `settings`
开着混淆时会出现 `NoClassDefFoundError: Failed resolution of: La/h2;`（混淆后的类找不到）。

另外把 `settings` 里硬编码的机型名 `"Legion Y700"` 改成 `Build.MODEL`，换机器也能正确显示。

代价：**Android 11 上没有真正的窗口模糊**，Dock 的毛玻璃是壁纸采样做出来的近似效果；
状态栏图标配色与模糊只在 API 31+ 完全生效。

### 控制中心怎么唤出

`panel` 没有桌面图标，靠 root 守护进程 `tesserad` 读触摸屏原始事件来判断手势：

- **屏幕顶部右侧下滑** → 控制中心（`ControlActivity`）
- **屏幕顶部其他位置下滑** → 通知中心（`NoticeActivity`）

手指必须落在距屏幕顶边 26dp 以内，向下拖动超过 22dp 且以纵向为主，才会被判定为下拉。
守护进程只在存在 `/data/adb/tessera/takeover` 时才接管：该文件存在时会把系统的下拉通知栏
禁用（`cmd statusbar send-disable-flag statusbar-expansion`），改由这两个页面顶替。
删掉这个文件即可立刻把通知栏还给系统。

不在接管状态时，控制中心里的开关也**点不动** —— 它们通过抽象 socket `tesserad` 转给 root
执行，守护进程没跑就只是静默失败。所以装完 `panel` 记得确认 `tesserad` 在运行：

```bash
adb shell su -c 'ps -A -o PID,ARGS | grep Tesserad'
```

### 在 Windows 上安装守护进程

`daemon/` 下的脚本以 `#!/system/bin/sh` 开头，**行尾必须是 LF**。如果仓库在 Windows 上按
`core.autocrlf=true` 检出，脚本会带上 CRLF，装进 `/data/adb/service.d/` 后 Magisk 虽然会
`exec` 它，但解释器路径变成 `/system/bin/sh\r` 导致**静默失败**（不产生任何日志，也不报错）。
本仓库的 `.gitattributes` 已强制 `*.sh` 用 LF；若在你本地仍然失效：

```bash
adb shell su -c 'sed -i "s/\r$//" /data/adb/service.d/tessera-daemon.sh'
adb shell su -c 'chcon u:object_r:magisk_file:s0 /data/adb/service.d/tessera-daemon.sh'
```
