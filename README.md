# CanvasPlus

在直板机上做多应用工作台。基于 ColorOS 自己的画布（Pocket Studio / 虚拟屏）实现，不重写窗口管理。

## 现状

**可用**：两个、三个、四个应用同时进画布。

- 三窗：一左两右（竖屏），点小窗换主窗
- 四窗：2×2 四等分，点窗口放大，菜单可切换放大/缩小
- 窗口菜单：全屏、浮窗、放大/缩小、替换应用、关闭
- 拖拽条：只支持点击交换，拖动已禁用
- 入口：桌面快捷方式直接开四窗，智能侧边栏加到当前画布

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
| `Trigger` | 广播入口，往画布加窗口 |
| `SidebarEntry` | 智能侧边栏，把应用加到当前画布 |

## 许可

Apache-2.0。只 hook 公开行为，未复制任何闭源代码。
