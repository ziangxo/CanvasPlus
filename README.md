# CanvasPlus

在直板机上做多应用工作台。基于 ColorOS 自己的画布（Pocket Studio / 虚拟屏）实现，不重写窗口管理。

## 现状

**可用**：两个、三个、四个应用同时进画布。

- 三窗：默认进入总览（一左两右整体缩小居中），点窗口放大该窗口，菜单可切换放大/缩小
- 四窗：2×2 四等分总览，点窗口放大，菜单可切换放大/缩小
- 窗口菜单：全屏、浮窗、放大/缩小、替换应用、关闭
- 二窗：保持本项目自己的增强——进二分屏自动放大焦点窗、点窗口放大（上游 v0.2.0 把二窗交回系统原生）
- 画布固定竖屏，不跟随手机旋转
- 三窗/四窗弹输入法时画布整体抬高到焦点窗口露出键盘，按键不会丢
- 拖拽条：只支持点击交换，拖动已禁用
- 入口：桌面快捷方式直接开四窗，智能侧边栏加到当前画布（侧边栏点应用时当场问画布能不能接，接不了才开小窗）

**限制**：主窗无法既占满宽度又保持应用比例、同时不留白。这是画布按「等比缩放」渲染的固有限制，详见 [docs/coloros-multiwindow.md](docs/coloros-multiwindow.md)。

## 原理

ColorOS 的画布（`com.oplus.pscanvas`）本身支持三窗，只是手机策略被砍掉了：

```java
// B1.o（PhonePolicy）
public int c(C0601c adapter, int current, int orientation, boolean restore) {
    if (adapter.getCount() == 2) return orientation == 2 ? 2 : 1;
    return 0;        // 三个及以上清零，第三个窗口 layout 成 0x0
}
```

模块做四件事：

1. 把画布策略换成 `B1.r`（TabletPolicy），它有完整的三窗公式
2. 指定布局模式：竖屏 4（上大）、横屏 6（左大）
3. 关掉 `u1.c.j()`，否则强制走三窗一线
4. 按焦点索引分配矩形，并同步 `resizeTask`，让应用按窗口尺寸重新排版

窗口、动画、拖拽、输入全部是 OPPO 的实现。

## 构建

需要 Android SDK（build-tools + platform 36）和 JDK 17 以上：

```bash
ANDROID_HOME=/path/to/sdk JAVA_HOME=/path/to/jdk bash build.sh
```

`ANDROID_HOME` / `JAVA_HOME` 有默认值（`~/Android/Sdk` 和当前 `javac`），也可以直接 export。
debug keystore 不存在时脚本会自动生成一个。

输出 `dist/CanvasPlus.apk`。

## 安装

1. 安装 APK
2. LSPosed 作用域勾选这两个：
   - **画布** `com.oplus.pscanvas`
   - **智能侧边栏** `com.coloros.smartsidebar`
3. 重启一次，让画布和侧边栏进程重新加载模块

系统框架（`android`）和桌面（`com.android.launcher`）里的 hook 代码还在，但默认不在作用域里：放开窗口数量上限、最近任务入口走画布，不做也不影响正常使用。

## 使用

```bash
# 三个应用进画布
adb shell am broadcast -a com.canvasplus.START \
  --esa packages com.tencent.mm,com.tencent.mobileqq,com.android.settings

# 指定布局模式（3~7）
adb shell am broadcast -a com.canvasplus.START \
  --esa packages com.tencent.mm,com.tencent.mobileqq,com.android.settings \
  --ei mode 6
```

模式：

| 值 | 含义 |
|---|---|
| 3 | 三窗一线 |
| 4 | 大窗在上 |
| 5 | 大窗在下 |
| 6 | 大窗在左 |
| 7 | 大窗在右 |

## 快捷方式与第三方调用

长按桌面图标有四条静态快捷方式（`res/xml/shortcuts.xml`）：
「三分屏」「面板配置」「复制三分屏命令」「复制加应用命令」。
后两条不打开界面，直接把对应 shell 命令写进剪贴板并 toast 出来。
「面板配置」页顶部另有「可调用的 shell 命令」清单（等宽字体、可选中、每行带「复制」按钮），
包含带参数的变体（指定布局方向 3~7、切换三窗方向）。

第三方 App 要调起三分屏，用显式的这个组件（透明、无 UI，执行完立即 finish）：

```bash
adb shell am start -n com.canvasplus/.ShortcutActivity -a com.canvasplus.SC_THREE --ei mode 4
adb shell am broadcast -a com.canvasplus.START        # 等价，直接打到系统框架里的 Trigger
```

`ShortcutActivity` 只负责把动作转成广播，真正起画布的是 system_server 里的 `Trigger`
（它持有 `com.oplus.permission.safe.WINDOW` 之类的系统权限，普通 App 直接 startActivity
调 `ContainerActivity` 会被 `SecurityException` 拒）。

注意：普通 App **拿不到**别家的快捷方式——`LauncherApps.getShortcuts()` /
`startShortcut()` 需要 `ACCESS_SHORTCUTS`（signature 级）或桌面角色。静态快捷方式主要给
系统桌面长按菜单用，跨应用调用走上面的显式组件。

如果画布进程没在运行，广播收不到。先拉起：

```bash
adb shell su -c '/system/bin/cmd activity start -n com.oplus.pscanvas/com.oplus.pscanvas.canvasmode.canvas.ContainerActivity'
```

## 日志

```bash
adb shell su -c 'grep -i canvasplus /data/adb/lspd/log/verbose_*.log | tail -20'
```

关键行：

```
layout policy -> tablet
split mode for 3 windows -> 6
resize 811 slot -> Rect(0, 0 - 1264, 1706)
```

## 安全阀

```bash
adb shell su -c 'touch /data/system/canvasplus-halt'
```

重启后模块完全不加载。如果 adb 也进不去，停用 LSPosed 模块。

## 已知问题

- 应用 resize 后内部排版会变，切换焦点时有短暂重排
- 部分应用拒绝 resize，会保持原尺寸显示
- 画布进程退出后广播失效，需要重新拉起

## 文件

| 文件 | 作用 |
|---|---|
| `ModuleEntry` | 入口，按进程分发，停止阀 |
| `CanvasLayout` | 画布布局、四窗、菜单、交换、关闭、浮窗 |
| `FeatureUnlock` | feature 开关、窗口数上限、Launcher 入口 |
| `Trigger` | 广播入口，往画布加窗口；也代写替换面板配置 |
| `SidebarEntry` | 智能侧边栏，点应用时问画布能不能接，接不了才开小窗 |
| `AutoReplace` | 三分屏后自动弹替换面板；面板「全部应用」的隐藏/排序 |
| `ConfigActivity` | 替换面板配置页（勾选隐藏、↑↓ 排序），桌面有图标入口 |

替换面板配置存在 `Settings.Global` 的 `canvasplus_autoreplace`（`{"hidden":[],"order":[]}`），
三分屏焦点存在 `canvasplus_triple_focus`（`-1` 不干预 / `0` 左窗 / `1` 上窗 / `2` 右下窗），
三窗/四窗「从菜单缩小后点窗口只切焦点、不放大」开关存在 `canvasplus_no_click_zoom`
（`1` 开 / `0` 关，缺省为开），三项都在桌面「CanvasPlus 面板配置」里改。写它们需要一次
`adb shell pm grant com.canvasplus android.permission.WRITE_SECURE_SETTINGS`；
没授权时配置页会改走广播让 system_server 代写。改完配置重新打开一次面板、
或重新触发一次三分屏即生效（不需要重启框架）。

## 许可

Apache-2.0。只 hook 公开行为，未复制任何闭源代码。
