# ColorOS 16 多窗口逆向笔记

设备：一加 Ace 5（PKG110），ColorOS 16.0.10.500，Android 16 / SDK 36，1264×2780，560dpi，swdp 361。

所有结论都来自反编译，样本：

| 文件 | 来源 |
|---|---|
| `oplus-services.jar` | `/system/framework/` |
| `oplus-framework.jar` | `/system/framework/` |
| `SystemUI.apk` | `/system_ext/priv-app/` |
| `OplusLauncher.apk` | `/system_ext/app/` |
| `OplusFlexibleWindowUI.apk` | `/system_ext/app/OplusFlexibleWindowUI/`（包名 `com.oplus.pscanvas`） |

---

## 一、三套多窗口机制

| 机制 | 实现位置 | 进程 | 应用布局 |
|---|---|---|---|
| 普通分屏 | `SystemUI` 的 `SplitScreenController` + `StageCoordinator` | systemui | **resize**，应用重新排版 |
| 画布 / 全景工作台 | `com.oplus.pscanvas` 的 `ContainerActivity` + `ContainerView` | pscanvas | **等比缩放**，应用保持全尺寸 |
| 小窗 | `ZoomWindowManagerService` | system_server | resize 到小窗尺寸 |

OPPO 内部命名：
- Pocket Studio = Panorama Workstation = Flexible Window = 画布
- Zoom Window = 小窗
- SplitScreen = 普通分屏

普通分屏拖到屏幕边缘会提示「松手进入虚拟屏」，松手后切换到画布。两者是不同的实现。

---

## 二、画布（pscanvas）的结构

### 2.1 布局策略

`B1.m` 是策略接口，三个实现：

| 类 | 名称 | 说明 |
|---|---|---|
| `B1.e` | DefaultPolicy | 大屏默认 |
| `B1.r` | TabletPolicy | 平板 / 折叠屏，三窗公式最完整 |
| `B1.o` | PhonePolicy | 手机，只实现 1/2 两种模式 |

选择逻辑在 `B1.n.b(Context)`：

```java
return s.r(context) ? this.f228a   // DefaultPolicy（折叠屏且 dp >= 600）
     : s.I() ? this.f229b          // TabletPolicy（平板）
     : s.x(context) ? this.f230c   // PhonePolicy（dp 300~600）
     : this.f228a;                 // DefaultPolicy
```

设备类型来自 `B1.s.d()`：

```java
if (hasFeature("oplus.software.fold_remap_display_disabled") && hasFeature("oplus.hardware.type.fold")) return 3;
if (hasFeature("oplus.hardware.type.fold")) return 1;
return hasFeature("oplus.hardware.type.tablet") ? 2 : 0;
```

直板机返回 0，`s.x(context)` 为 true，走 PhonePolicy。

### 2.2 PhonePolicy 的关键缺陷

`B1.o.c()`：

```java
public int c(C0601c adapter, int current, int orientation, boolean restore) {
    if (adapter.getCount() == 2) {
        return orientation == 2 ? 2 : 1;
    }
    return 0;        // 三个及以上直接清零
}
```

返回值写进 `adapter.f13796f`，作为布局模式传给 `B1.l.M1`。

模式 0 在 `M1` 里匹配不到任何分支：

- 第一个窗口：`F(list, 0, density)` 不命中任何 case，返回初始的全屏尺寸
- 第三个窗口：`rect` 保持空 `Rect(0,0,0,0)`
- `ContainerView.N2()` 里 `q02.layout(rect...)` → `layout(0,0,0,0)`

**第三个应用变成一个小方块，根因就在这里。**

### 2.3 TabletPolicy 的五种三窗模式

`B1.r.c()` 对 `getCount() == 3` 返回 3~7，对其它数量返回原值（不破坏状态）。

模式含义在 `B1.l.Z0()` 里写明：

```java
3 = THREE_LINE_LAYOUT        三窗一线
4 = THREE_LAYOUT_BIG_UP      大窗在上
5 = THREE_LAYOUT_BIG_DOWN    大窗在下
6 = THREE_LAYOUT_BIG_LEFT    大窗在左
7 = THREE_LAYOUT_BIG_RIGHT   大窗在右
```

### 2.4 布局分发

`ContainerView.w1()`：

```java
if (count == 1) { 单窗口全屏 }
if (k1()) O2();   // k1() = getCount() == 2
if (i1()) N2();   // i1() = getCount() == 3
```

`O2()`（两窗）：

```java
List N12 = B1.l.N1(context, arrayList, adapter.n(), density);
```

`N2()`（三窗）：

```java
int n3 = adapter.n();
if (n3 < 3) { adapter.e(); n3 = adapter.n(); }
int i3 = adapter.j() ? 3 : n3;          // j() 为 true 时强制三窗一线
List M12 = B1.l.M1(arrayList, i3, density);
for (...) {
    q02.k0(item, item.o(), cornerRadius);
    q02.layout(rect.left, rect.top, rect.right, rect.bottom);
}
```

**`j()` 为 true 会覆盖指定的模式**，直接变成模式 3。

### 2.5 几何公式

两窗 `B1.l.N1()`：第二个窗口和第一个同高或同宽，另一边按比例缩放。

```java
i6 = Math.min(rect.height(), rect3.height());
i7 = (task.q() != 2 || task.J()) ? (i6 / rect.height()) * rect.width() : rect.width();
```

三窗 `B1.l.M1()`：第一个用 `F()`，第二个用 `s1()`，第三个按模式选 `e1()`（放右侧）或 `Y0()`（放下方）。

```java
// F() 模式 6
rect.bottom = 小窗1.height + gap + 小窗2.height;
rect.right = (rect.height() / o3.height()) * o3.width();

// Y0()：放下面，宽度跟随
rect3.top = rect.bottom + gap;
rect3.right = rect.right;

// e1()：放右侧，高度对齐
rect3.left = rect.right + gap;
rect3.right = rect3.left + rect2.width() * (rect.height() / rect2.height());
```

**所有窗口的尺寸都来自 `taskData.o()`（应用真实 bounds），没有任何“主窗占百分之多少”的参数。**

### 2.6 画布整体缩放

`B0.q()` / `t()` / `p()`：

```java
private float t() {
    Rect screen = this.f10203n;                                  // 屏幕
    float w = (screen.width() - 16) / 内容并集宽;
    float h = (screen.height() - 16) / 内容并集高;
    return Math.min(w, h);
}

private void p() {
    ViewGroup canvas = (ViewGroup) this.f10190a.getParent();
    canvas.setTranslationX(...);
    canvas.setScaleX(this.f10197h);
    canvas.setScaleY(this.f10197h);
}
```

「内容并集」是 `ContainerView.getRectListUnion()`，也就是所有窗口矩形的并集。

三个全屏应用摆成 2×2，并集 2528×5560，缩放 = min(1264/2528, 2780/5560) = 0.5。主窗只剩 632×1390。

### 2.7 焦点缩放

点击窗口 → `ContainerActivity` 的 `onAppEnter` → `ContainerView.R(index)` → `T(index, 450, 0, true)`。

`T()` 里 2573 行有一个短路：

```java
if (Q2(context).M0().contains(childrenPositionOnScreen.get(i3))
        && Q2(context).U0()
        && Q2(context).D0() != 0
        && this.f10760Q != null
        && this.f10758P
        && this.f10770c0 == 3) {
    Log.d("autoScale target is inside screen no need to auto scale !");
    return false;
}
```

`f10770c0` 记录上一次动画类型。三窗布局的类型正好是 3，所以之后点击任何窗口都直接返回。

两窗布局的类型不是 3，所以双窗口能点谁谁放大。

### 2.8 窗口尺寸 == 任务尺寸

`FlexibleWindowManager.setFlexibleFrame(taskId, bundle)` 只设置表面位置（`taskFrame` / `taskHandleFrame` / `taskViewFrame`），不改变应用布局。

画布里的视图尺寸由 `containerView.layout(rect)` 决定，而 `EmbeddedViewDecor.k0()` 传给 `FlexibleTaskView.init()` 的矩形是 `taskData.o()`，也就是任务的真实 bounds。

所以：**给小矩形会让视图变小、内容被裁；给全屏矩形视图和内容才一致。**

### 2.9 画布进程有 resize 权限

`ActivityTaskManager.getService().resizeTask(taskId, rect, 0)` 在 pscanvas 进程里调用成功：

```
probe resize ok 811 Rect(0, 0 - 784, 2780)
probe resize ok 826 Rect(784, 0 - 1264, 1390)
probe resize ok 812 Rect(784, 1390 - 1264, 2780)
```

这意味着可以让应用按窗口尺寸重新排版（普通分屏的行为），代价是应用内部布局会变。

---

## 三、三种目标形态的可行性

| 方案 | 窗口比例 | 应用内容 | 代价 |
|---|---|---|---|
| A. 画布等比缩放 | 等于应用比例 | 完整，有留白 | 主窗最多占一半，利用率低 |
| B. 画布 + resizeTask | 任意 | 应用重新排版，填满 | 部分应用拒绝，切换有重排 |
| C. VirtualDisplay | 任意 | 应用以为自己是独立屏幕 | 等于重写，不复用画布 |

「主窗 62% 宽、满高、内容完整、不留白」这四条同时成立，只有 B 和 C。

A 的数学限制：窗口宽高比 = 应用宽高比 = 1264/2780 = 0.4545。要让宽度占 62%（784px），高度就是 784/0.4545 = 1724px，只能占屏幕高度的 62%，下方必然空出来。

---

## 四、当前模块

作用域只需要 `com.oplus.pscanvas`。

| 文件 | 作用 |
|---|---|
| `ModuleEntry` | 按进程分发，`/data/system/canvasplus-halt` 停止阀 |
| `FeatureUnlock` | feature 开关、`getMaxWinNum`、Launcher 入口 |
| `CanvasLayout` | 画布布局、四窗、菜单、交换、关闭、浮窗 |
| `Trigger` | 广播入口，加窗口 |
| `SidebarEntry` | 智能侧边栏加窗口 |

`CanvasLayout` 装的 hook：

| 目标 | 目的 |
|---|---|
| `B1.n.b(Context)` | 换成 TabletPolicy |
| `B1.r.c(...)` | 固定三窗布局方向（竖屏 4、横屏 6） |
| `ContainerView.i1()` | 放开数量判断，四窗也走 `N2()` |
| `u1.c.j()` | 关掉强制三窗一线 |
| `B1.l.M1(...)` | 四窗补 4 个 2×2 矩形 |
| `ContainerView.N2()` | 入口清矩形列表，四窗布局后按方向重排 |
| `B1.l.M(...)` | 关闭/替换时补四窗结果 |
| `ContainerView.T1(...)` | 四窗关闭窗口 |
| `I.y0()` / `I.n0()` | 四窗浮窗 |
| `FlexibleWindowManager.calculateFlexibleWindowBounds` | 快捷方式一次带 4 个 intent |
| `ContainerView.k0()` | 交换前补齐子 view |
| `E.r0()` / `E.q0()` | 禁掉调节条拖拽（三窗、四窗） |
| `t.e0()` | 三窗隐藏放大/缩小菜单 |

广播：

```bash
adb shell am broadcast -a com.canvasplus.START \
  --esa packages com.tencent.mm,com.tencent.mobileqq,com.android.settings \
  --ei mode 6
```

`mode` 可选 3~7，不传按方向自动选。

---

## 五、失败记录

| 尝试 | 结果 | 原因 |
|---|---|---|
| 只放 feature 开关 | 只能进两个应用 | PhonePolicy 对 3 个返回 0 |
| hook `calcLaunchBounds`（system_server） | 任务 bounds 变了，画布视图没变 | 两层坐标系不一致 |
| hook `layoutLaunchBounds` 改 task bounds | 窗口错位 | 画布整体缩放叠加 |
| 给 M1 返回屏幕坐标矩形 | 内容被裁 | 视图尺寸小于内容尺寸 |
| M1 返回全屏 + setScale | 焦点窗口显示不全 | 和 `B0.p()` 的整体缩放叠加 |
| 清 `f10770c0` 字段 | 无法定位字段 | jadx 重命名，dex 里是混淆名 |
| swap adapter 顺序 | 布局不变 | `f10786l` 没清空，矩形是追加的 |

---

## 六、后续方向

### 如果继续在画布上做

1. 修 `T()` 的短路。需要的字段在 dex 里是混淆名，可以通过 `javap` / smali 定位，而不是猜。
2. 焦点缩放成功后，再决定用 A（缩放）还是 B（resize）。
3. resize 路径已经验证可用，主要问题是应用重排时的视觉跳变，可以加过渡。

### 如果换实现

1. **SystemUI 分屏扩展**：`StageCoordinator` 固定两个 stage，要加第三个得改它的布局、拖拽、transition，工作量大但交互最原生。
2. **VirtualDisplay**：每个应用独立屏幕，任意比例，但等于从零写容器、输入转发、IME、焦点管理。
3. **ZoomWindow 组合**：小窗本身支持多个、可拖动、可调整大小，把多个小窗按主/副位置排列。

---
