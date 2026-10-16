# Awa的安装助手

**What：** 给吉利 ECARX R621 车机（MT6735 / Android 5.1 / API 22）用的安装助手。

**所做的事：**
1. **已安装的软件** —— 列出系统 + 用户应用，标注「系统应用 / 用户应用 / 系统·特权」。点条目看它的安装目录、APK 文件名、和 ADB 卸载命令。
2. **存储里的 APK** —— 扫 U 盘 / 内部存储的 APK，自动判断车机上装没装过（待安装 / 可更新 / 版本更低 / 已安装），点条目看普通安装和系统应用安装的完整教程命令。
3. **文件管理** —— 自带文件管理器，浏览车机存储和 U 盘，复制 / 剪切 / 粘贴 / 重命名 / 删除 / 新建 / 看属性。**支持多选批量操作**：点顶栏「多选」（或长按条目选「多选」）进入多选模式，点条目逐个勾选，工具栏换成 全选 / 复制 / 剪切 / 删除 / 取消，一次处理一批。

**所不做的事（有意为之）：**
本工具**不替你安装、不替你卸载**。它只把命令摆出来让你自己抄 —— 所以清单里没有 `INSTALL_PACKAGES`、`DELETE_PACKAGES`、`MOUNT_UNMOUNT_FILESYSTEMS` 这三条危险权限（装在 `/system/priv-app` 时它们会真的生效，跟「只出教程」的定位冲突）。

**唯一会提权的地方：** 无线 ADB 开关。原因和实现见下面「无线 ADB」。

---

## 界面

```
┌──────────────────────────────┐
│ 设置      重启      关于        │  ← 和原版 AwaAPK 一致的顶栏
├──────────────────────────────┤
│ 已安装的软件 │ 存储里的APK │ 文件管理 │
├──────────────────────────────┤
│  车机   U盘   上一级  新建  粘贴  刷新  多选  │  ← 工具栏（文件管理页）
│  ...                          │
│  ...                          │
└──────────────────────────────┘
```

多选模式（点「多选」后工具栏整条换掉）：

```
┌──────────────────────────────┐
│  全选   复制   剪切   删除   取消   │
├──────────────────────────────┤
│ ■ MyApp.apk              已选   │  ← 勾上的行整行变蓝、方框填实
│ ■ Other.apk              已选   │
│ □ Download               目录   │
├──────────────────────────────┤
│ /sdcard · 3 项 · 已选 2 / 3 项  │
└──────────────────────────────┘
```

设置面板（照原版结构）：

```
打开原生系统设置          ›
─────────────────────────────
本机 IP            192.168.1.5
─────────────────────────────
ADB 调试（USB）      已开启  [开关]
─────────────────────────────
无线 ADB（WiFi）     已开启  [开关]
  adb connect 192.168.1.5:5555 · 27 分钟后自动关闭
  ⚠ 同一 WiFi 下任何人都能连上这台车机，用完记得关
─────────────────────────────
WiFi 联网             已开启  [开关]
─────────────────────────────
车机信息、提权自检、手敲命令说明、关于
```

---

## 无线 ADB

### 为什么必须 root

无线 ADB 靠系统属性 `service.adb.tcp.port` 控制，而**系统属性只有 root 能写**。

想绕开 root 是不行的：Android 5.1 的 `Settings.Global` 里根本没有 `adb_tcp_port` 这个设置项（那是 Android 6.0 才加的），光往 Settings 里写系统压根不听。所以车机内部不存在「免 root 开无线 ADB」的接口。

### 免 root 的另一条路（从电脑开，不需要 root）

USB 连着的时候在电脑上敲一条：

```bash
adb tcpip 5555
```

这条不用 root —— 是让车机上的 `adbd` 自己去改属性。拔掉 USB 再 `adb connect 车机IP:5555` 就行，用完 `adb usb` 关掉。

### 一键开关怎么做的

包里内置 `assets/mtk-su`（65,144 字节），第一次用时自动释放到应用私有目录并 `chmod`。候选提权程序按这个顺序试：

1. 上次跑通的那个
2. `/data/local/tmp/mtk-su` —— SELinux 明确允许应用执行这里的东西，**最稳**
3. 内置的 mtk-su（释放到应用私有目录）
4. 系统本来就带的 su（`/system/bin/su` 等）
5. `/sdcard` 下手动放的 —— 垫底，因为 `/sdcard` 是 FUSE + noexec，放那儿能读不能跑

每个候选先试 `su -c "脚本"`，不行再把脚本喂给 stdin。脚本末尾追加 `echo __AWA_END__`，**输出里 `__AWA_END__` 单独占一行**才算真跑通（不能只看 exit code，有的 su 失败了也返回 0）。

### 这台 R621 的一个坑

上面那套自动化在这台车机上会卡住，原因是 SELinux：

| 位置 | 能跑吗 |
|---|---|
| `/sdcard/mtk-su` | ❌ FUSE + noexec，能读不能跑 |
| 应用私有目录的副本 | ❌ SELinux 不允许应用执行自己的副本 |
| `/data/local/tmp/mtk-su` | ✅ SELinux 明确允许应用执行 |

所以**要借一次电脑 ADB**，把 mtk-su 放进 `/data/local/tmp`：

```bash
adb push mtk-su /sdcard/
adb shell "cp /sdcard/mtk-su /data/local/tmp/mtk-su && chmod 755 /data/local/tmp/mtk-su"
```

这一步只需做一次，做完永久有效。所以本工具在自动化全部失败时，会自动导一份 mtk-su 到 `/sdcard/mtk-su`，并弹出「需要连一次电脑」的引导，附上面这条命令；点「我已完成，检测一下」直接重试。

> **⚠ 这里有个连原版 AwaAPK 都踩了的坑，值得单独说：**
> 判定「是否跑通」用的是「输出里有没有 `__AWA_END__`」。但 `ProcessBuilder.start()` 失败时抛的 `IOException`，它的 message 里会带**完整命令行**（`"Errorrunning exec(). Command: [mtk-su, -c, setprop … echo __AWA_END__]…"`），而命令行本身含 `echo __AWA_END__`。于是裸用 `contains()` 判断会把「进程根本没起来」判成「跑通了」——日志里写着 `EXCEPTION…` 却返回成功，真实失败原因被彻底盖住。
>
> 修法是三道保险：① 异常消息里 `Command: [ … Environment:` 整段抹掉；② 以 `EXCEPTION:` 开头的直接判失败；③ 结束标记必须单独占一行。`tests/TestHasEnd.java` 有 16 条断言覆盖这个场景。

### 开多久自动关

**30 分钟**。开着的时候同一 WiFi 下谁都能连上来，所以到点自动关。倒计时时间戳存 `SharedPreferences`，退到后台再回来接着算。应用被杀掉、或者你在电脑上 `adb usb` 手动关了，它不会自作主张再去关一次。

---

## 「打开原生系统设置」

不直接跳设置主页，弹出 14 项入口列表（照原版）：开发者选项 / 应用管理 / 存储 / 无线和网络 / 蓝牙 / 安全 / 位置信息 / 日期和时间 / 设备信息 / WLAN / 流量使用 / 电源使用 / 车机设置（ECARX）/ 全部设置。

每项用**显式 `ComponentName`** 直接拉对应 Activity —— 比 Intent action 准，不同 ROM 的 action 实现不一样。带两级兜底：显式组件打不开 → 退回对应 `android.settings.*` action → 退回设置主页。

---

## 外置存储自动识别

这台 R621 会同时挂出 `/storage/usb0~usb3` 和 `/storage/sdcard1`，空的口也是个目录，光看路径分不出来。检测流程：

1. 从 `/proc/mounts` 读 `vfat / exfat / texfat / ntfs / fuseblk / msdos / iso9660 / udf` 的挂载点（排除内部存储）
2. 补 `/storage` 下非 emulated 的目录（现代 Android 拿卷 ID 当挂载点，如 `/storage/1A2B-3C4D`）
3. 补 24 条固定路径兜底
4. 按「路径含 `usb`(+1000) → 有条目(≤+300) → 路径含 `extsd`(+400)」排序，**第一个就是自动选中的那个**，不再弹「选一个」

---

## 文件管理 · 多选

顶栏「多选」（或长按条目 → 菜单最后一项「多选」）进入多选模式：

| 状态 | 工具栏 | 列表 |
|---|---|---|
| 普通 | 车机 · U盘 · 上一级 · 新建 · 粘贴 · 刷新 · **多选** | 点进入 / 长按出操作菜单 |
| 多选 | **全选** · 复制 · 剪切 · 删除 · 取消 | 点条目勾选；勾上的行整行变蓝 + 方框填实 + 徽章「已选」 |

几个刻意的选择：

- **勾选状态存绝对路径**（`Set<String> selPaths`），不存 `FileEntry` 对象 —— `openDir()` 每刷新一次就把 `FileEntry` 全重建了，存对象的话勾选状态一刷新就丢。
- **换目录 / 换标签页自动清空勾选**，返回键先退出多选 —— 勾的是上一个目录里的条目，留着会指错人。
- **批量复制 / 剪切只负责装剪贴板**，粘贴那一头本来就是循环的（`paste()` 从最早那版就支持多项），直接复用，不另写一套拷贝逻辑。
- **按列表显示顺序取勾选项**（`selFiles()` 遍历 `files` 而不是遍历 `HashSet`）—— 一次拷几十个文件时，落到目标目录里的先后跟眼睛看到的顺序一致，出问题好排查。
- **批量删除放后台线程**，底栏走 `N/M` 进度；确认框里写明条数、总大小、有几个文件夹（文件夹会被连带删掉）。
- **勾选方框是纯色块，不是 `✓` 字符** —— 车机字体不一定有这个字形，画成色块最保险。
- 行是复用的，`getView()` 开头先把方框和行背景还原成默认态，免得文件管理页勾选过的行背景串到另外两个标签上去。

---

## 构建

### 依赖

| 东西 | 干什么 | 从哪来 |
|---|---|---|
| JDK 17 | `javac` + 跑 d8 | [adoptium.net](https://adoptium.net) |
| `r8.jar` | 含 `com.android.tools.r8.D8`，class → dex | [maven.google.com](https://maven.google.com/web/index.html#com.android.tools:r8) |
| `android-all-22.jar` | Android 5.1 编译期库 | [Robolectric](https://repo1.maven.org/maven2/org/robolectric/android-all/) |
| `python3` + `cryptography` | 打包签名 | `pip install cryptography` |

### 步骤

```bash
export JDK=/path/to/jdk-17.0.2
export ANDROID_JAR=/path/to/android-all-22.jar
export R8_JAR=/path/to/r8.jar
export PYTHON=python3

./build.sh          # javac → d8 → 打包签名
python3 verify.py   # 170 项出包校验
```

产物在 `dist/`，第一次构建会生成签名私钥 `tools/awa_key.pem`（**已在 .gitignore 里，别提交**）。

### 两个构建上的坑

- **javac 必须用 `-classpath`，不能用 `-bootclasspath`。** `android-all-22.jar` 里只有 `android/*` 和部分 `java/security/*`，没有 `java/lang/*`，用 `-bootclasspath` 会直接报 `Unable to find package java.lang`。
- **d8 那句 `Type java.lang.Runnable was not found` 是警告不是错误。** `--lib` 只给了 android jar，没有 JDK 的 `java.lang`，desugar 用不上，不影响产物。

---

## 关于「装成系统应用」

`REBOOT`（重启）和 `WRITE_SECURE_SETTINGS`（ADB 调试开关）都是 `signature|privileged` 级权限，普通应用拿不到。

**Android 5.1 没有 privapp 权限白名单**，所以只要把本应用装进 `/system/priv-app/`，权限就自动生效，不用 root。装在 `/data/app` 当普通应用的话，这两个开关改不了 —— 本工具会把命令显示给你，不会自己去提权。

完整教程在工具里：「存储里的 APK」→ 点本工具的 APK → 安装教程「二、安装为系统程序」。

---

## 自绘开关

没用系统 `Switch`，因为 4.x 那个 Holo 胶囊又大又丑。照原版 `ToggleSwitch` 实现的 `android.view.View` 子类：

- 尺寸 56×30 dp
- 轨道 `rect(0,0,w,h)`，圆角半径 `h/2`，`drawRoundRect`
- 滑块圆心 `x = on ? (w - r) : r`，`y = r`，半径 `r - dp(3)`
- 轨道：开 `#FF33B5E5` / 关 `#FF5A5F6B` / 不可用-开 `#6633B5E5` / 不可用-关 `#FF44474F`
- 滑块：开 `#FFFFFFFF` / 关 `#FFB0B0B0` / 不可用 `#FF8A93A6`

---

## 目录

```
├── AndroidManifest.xml      二进制清单（权限已调整）
├── resources.arsc           应用名已改成「Awa的安装助手」
├── build.sh                 编译流水线
├── build_apk.py             装 APK + v1 签名
├── verify.py                170 项出包校验
├── src/
│   └── com/awa/apkinstaller/
│       ├── MainActivity.java    全部 UI 和功能（约 3000 行）
│       └── AboutActivity.java
├── assets/
│   └── mtk-su              内置提权工具，65,144 字节
├── res/
│   └── ic_launcher.png
├── tests/
│   └── TestHasEnd.java    提权跑通判定的单元测试（16 条断言）
└── docs/
    └── 说明.html           完整说明文档
```

---

## 许可

[MIT](LICENSE)。随便用、随便改、随便商用，只要求保留版权声明和许可声明。

**关于 `assets/mtk-su`：** 这是第三方二进制（来源 `akkdiy.cn`），它的授权情况不明。它只是被打进包里，本身不在本项目的 MIT 授权范围内 —— 你自己用时请注意这一点。

---

## 免责声明

本工具只显示教程命令，不执行安装 / 卸载 / 提权（唯一例外是无线 ADB 开关）。把它装进 `/system/priv-app` 会改动系统分区，操作前备份。作者不对使用本工具造成的任何后果负责。

目标设备：吉利 ECARX R621（MT6735 / Android 5.1 / API 22 / userdebug）。别的车机没测过。
