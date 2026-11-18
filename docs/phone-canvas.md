# 手机画布多窗口：实现说明

在 ColorOS 手机上把 Pocket Studio（画布）做成"一个主窗 + 多个小窗"的工作台，
窗口都是手机比例，点哪个哪个放大，不破坏系统原来的二分屏。

## 一、整体结构

```
智能侧边栏 (com.coloros.smartsidebar)
    │  点应用
    ▼
广播 com.canvasplus.ADD
    │
    ▼
画布进程 (com.oplus.pscanvas)
    ├─ CanvasLayout  布局、尺寸、点击
    └─ Trigger       广播入口、添加任务
```

三个作用域：

| 作用域 | 用途 |
|---|---|
| `com.oplus.pscanvas` | 画布布局、任务尺寸、点击放大 |
| `com.coloros.smartsidebar` | 点应用时加进画布，而不是开小窗 |
| `android`（可选） | 放开 `getMaxWinNum`、feature 开关 |

只勾前两个就能用。第三个只在需要系统级放开时用。

## 二、画布为什么不能直接用平板布局

平板上 M1 会算出"主窗宽 = 两个小窗宽之和"，比例 0.967，和平板屏幕接近，所以自然。
手机屏幕是 0.455，方形任务放进去只能留白。

解决办法：**把任务尺寸拉成手机比例**。

任务进入画布时是 `1264×1306`（两窗半屏）。我们把它改成：

```
0.95 × 屏幕 = 1201 × 2641   比例 0.4548
```

不能用 `1264×2780`，正好等于屏幕会被系统当成全屏拒绝。

任务比例变了，`M1` 算出来的窗口也跟着变成长条，`calculateFrame` 比例一致，内容铺满。

## 三、尺寸要同步三处

只改一处不行：

| 位置 | 作用 | 不改的后果 |
|---|---|---|
| 任务 bounds（WCT `setBounds` + `setAppBounds`） | 应用真实排版尺寸 | 画面按旧尺寸缩放，错位 |
| `FlexibleTaskView.mLaunchBounds` | `calculateFrame` 的缩放基准 | 视图铺满了但内容被裁 |
| `u1/d.a` | `M1` 读取的任务 bounds | 窗口还是方形，重排 |

改完再调一次 `N2`，`M1` 才按新尺寸重算布局。

## 四、防止布局抖动

打开图片、朋友圈这类页内跳转时，系统会把任务数据重建，`u1/d.a` 被改回方形，
`M1` 算出方形窗口，就会闪一下重排。

三层防护：

1. `u1/d.o()` 永远返回目标尺寸 —— 布局指纹稳定
2. `ContainerView.layoutChildren` 指纹相同就跳过 —— 不重新摆子 view
3. `T()` 点击前强制刷一次布局 —— 保证点击还能放大

任务尺寸另有 watchdog 每 1 秒巡检一次，被系统改回去就修回来。

## 五、二分屏不受影响

用 `multiWindowMode = count >= 3` 判断：

- 两窗：保留原版 PhonePolicy，不改尺寸、不改策略、不跳过布局
- 三窗：换 TabletPolicy，应用长条尺寸和全部防护

所以系统原来的二分屏、侧边栏拖拽替换都不受影响。

## 六、侧边栏加应用

侧边栏点应用默认走 `PageRoutUtils.startActivityAsZoomWindow` 开小窗。

hook 这个方法：

1. 收到点击，判断画布是否在前台
2. 在前台：发广播 `com.canvasplus.ADD` 给画布，并拦掉小窗
3. 不在前台：什么都不做，正常开小窗

画布前台状态由画布进程广播 `com.canvasplus.CANVAS_STATE` 维护：
`onResume` 发 true，`onPause` 发 false，watchdog 每秒补发一次。

侧边栏进程无法查询其他进程（`getRunningAppProcesses` 只返回自己），所以用广播。

画布收到 ADD 后，给 `ContainerActivity` 发带 `androidx.activity.AddIntentToSplit`
的 Intent，它自己走 `addFlexibleTask` 把应用加进来。

## 七、调试

添加应用（手动）：

```bash
adb shell am broadcast -a com.canvasplus.ADD \
  --es pkg com.tencent.mm
```

三窗布局：

```bash
adb shell am broadcast -a com.canvasplus.START \
  --esa packages com.tencent.mm,com.tencent.mobileqq,com.android.settings \
  --ei mode 4
```

模式 3~7，竖屏用 4，横屏用 6。

停止阀：

```bash
adb shell su -c 'touch /data/system/canvasplus-halt'
```

## 八、四窗

四窗不另写一套排版，直接接进官方三窗那条链路：

```
w1() → i1() → N2() → B1.l.M1() → 摆窗口 + 写 getRectList()
     → layoutChildren 末尾 E.V()/H() 出调节条
     → onLayout 末尾 T(focus) 处理点击放大
```

官方只在两处把四窗挡在门外：

| 位置 | 原逻辑 | 补法 |
|---|---|---|
| `ContainerView.i1()` | `getCount() == 3` | 大于 3 也返回 true（`allowMoreThanThree`） |
| `B1.l.M1()` | `list.size() != 3` 返回空 | 四窗按任务尺寸 2×2 补 4 个矩形 |

矩形写进 `getRectList()` 之后，调节条、点击放大、关闭全部走官方逻辑，不需要自己加 view。

其余三处四窗专用补丁：

| 位置 | 问题 | 补法 |
|---|---|---|
| `s0.d()` | 写死 `count >= 3` 就拒绝 | ThreadLocal 让 `getCount()` 放行一次 |
| `A1.b.l()` | 返回的 `Rect[]` 只有 3 个，`o2()` 越界 | 扩到 4 |
| `ContainerActivity.x1` | 四窗取空列表 `IndexOutOfBounds` | 四窗跳过这条回调 |

### 关闭窗口

菜单"关闭"走 `S1() → T1(item) → u1(item)`。`u1()` 只有 count 2/3 分支，
四窗是空实现。而 `u1` 在同类里会被 ART 内联，hook 不到，所以改在 `T1` 上：
四窗时调 `X1(item)`，和官方三窗同一条删除链路。

### 浮窗

菜单"浮窗"走 `t.F1() → J.g() → I.y0(focus, count)`。`I` 里三处只认 2/3 窗：

- `I.i0()` 只处理 `splitCount == 2/3`
- `I.k0()` 里 `if (i4 != 3) return false`
- `I.n0()` 要求 `adapter.getCount() == splitCount`

`i0`/`k0` 都会被内联，hook 不到，所以在最外层 `y0()` 把窗数参数改成 3，
再让 `n0()` 对四窗放行，动画走现成的三窗流程。

### 快捷方式 / 广播带 4 个 intent

`ContainerActivity.W1()` 用 `B1.l.o0()` 把 intent 列表转成任务，
内部调 `FlexibleWindowManager.calculateFlexibleWindowBounds(List, int, Bundle)`，
binder 到 system_server 的 `calculateFlexibleWindowBoundList`，那里有一行：

```java
if (intentList.size() > 3) {
    Slog.e(TAG, "calculateFlexibleWindowBoundList intent list more then 3");
    return new Bundle();
}
```

画布拿到空结果就以"应用不支持画布"为由把自己关掉，表现是闪一下就没了。
单个 intent 的 `calculateFlexibleWindowBounds(Intent, int, int)` 没有这个限制，
所以客户端 hook `calculateFlexibleWindowBounds(List, int, Bundle)`：
超过 3 个就逐个查，再拼回 `androidx.flexible.layout.info.list`。

### 交换窗口（拖拽条上的按钮）

`ContainerView.k0(E.c)` 对 `getRectList()` 的每个下标取 `q0(i)` 再进 `B2()`。
矩形数比子 view 多的时候 `q0(i)` 返回 null，`B2()` 里 `view.getWidth()` 直接
NPE，画布进程崩掉，表现是"点交换，当前应用突然全屏"。

补一层保护：`k0` 进来先比矩形数和 `getChildEmbeddedViewList().size()`，
少了就调 `T2()` 补齐，还少就跳过这次交换。

### 矩形列表会累加

`w1()` 每帧先 `getRectList().clear()` 再调 `N2()`，`N2()` 里 `addAll()`。
但 `hookResume`、四窗补排版这些路径会直接调 `N2()`，绕过了 `w1()` 的清空，
矩形就越积越多（3、6、9……）。交换、调节条、点击放大拿到的数量全不对。

在 `N2()` 入口统一清 `getRectList()` 和 `getRectListUnion()`，不管从哪进来都干净。

### 替换应用后不刷新

菜单"替换应用"最终改的是 adapter 里的 taskData，靠 `notifyDataSetChanged()`
之后的重排把新窗口画出来。重排有个防抖判断（`layoutKey`），原来只用任务的
`o()` 矩形拼 key，而新旧任务的 bounds 完全一样，于是被当成"没变化"跳过，
界面保持旧窗口。

`layoutKey` 里加上 `task.k()`（应用标识）和 `task.t()`（taskId）。同应用内换页
时这两个不变，防抖照旧；替换成别的应用后 key 变化，重排正常触发。

### 三窗隐藏放大/缩小菜单

窗口菜单在 `t.e0()` 里构建，末尾统一 add 进 `t.W`。三窗时在 after 里
按标题把 `menu_enlarge` / `menu_narrow` 两项摘掉——这两个功能只在四窗下有意义。

### 四窗的放大/缩小

四窗默认四等分：画布按 2×2 总尺寸缩放到屏幕（`A2(fit, 0, 0, ...)`）。
`onLayout` 每次都会调 `T(focus, 0, 0, true)` 自动聚焦放大，四窗下用一个
`fourZoomed` 状态挡住它，没点放大之前一直保持四等分。

菜单点击走 `c.m(container, task, true/false)`，四窗时接管：

- 放大：`B1.l.a0()` 算出焦点窗口铺满屏幕的缩放和位移，套到 `A2()` 上
- 缩小：`A2(fit, 0, 0, 450, 4)`
- 同时写 `task.p0(true/false)`，菜单靠这个状态决定显示"放大"还是"缩小"

直接点窗口放大（不通过菜单）时也要同步这个状态，否则菜单还显示"放大窗口"。

### 侧边栏加窗口

侧边栏点应用只有两个结果：加进画布，或者照旧开小窗。

判断不查任务栈（侧边栏进程没权限，`getTasks` 经常返回空），改用心跳：

- 画布 watchdog 每秒广播 `{count}`，侧边栏记录最近一次时间
- 3 秒内有心跳说明画布进程还活着
- 心跳 + `count < 4` → 发 ADD 拦掉小窗，否则不拦

画布的 `visible` 状态不能用来判断：侧边栏面板一弹出，画布就 `onPause`，
`visible` 立刻变 false，表现就是"第一次点了没反应，第二次才行"。

画布收到 ADD 后加窗口的顺序：

1. `ContainerActivity.j0(intent)` —— 里面有几道前置检查（`a2`、rectList 对齐），
   经常静默返回，数量不变就往下走
2. `s0.d(taskData)` —— 直接用 Activity 当 context 调，绕开 `j0` 的检查。
   注意不能用 Application context，`s0` 内部会把 context 强转成 `ContainerActivity`
3. 400ms 后重试，最多 3 次
4. 还不行才走 `AddIntentToSplit` 的 intent 兜底

加成功后延迟 300ms 补一次 `N2()`，因为画布可能还在动画里没重排。

## 九、已知限制

- 四窗调节条的拖动已禁用（三窗、四窗都只支持点击），二分屏保持官方行为
- 画布在后台时点侧边栏仍然会把应用加进画布，不会开小窗
- 打开图片/朋友圈仍有极短的尺寸切换，但不再重排
- 横屏模式没有单独适配，`mode 6` 的窗口比例还是按竖屏算
- 广播直接拉起四窗仍然依赖画布进程已在运行；画布没起来时广播没人接收
  （Trigger 在 system_server 的注册需要 LSPosed 勾选"系统框架"作用域）
