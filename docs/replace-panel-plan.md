# 替换应用面板自动化 + 自定义 —— 实现设计（2026-11-20）

## 需求（用户最终确认）
1. 三包启动三分屏，**packages 次序第一个进侧窗**（用户纠正：不是大窗）。
   - 2026-11-20 起不再写死任何具体三包。侧窗（packages 第一个）由广播 `foreground=true` 在
     `Trigger.foregroundPackage()` 里动态读取「触发前的前台应用」；另外两个包由广播
     `--esa packages` 传入（例如 Via / 计算器）。任何写死的酷安/小黑盒组合都是死配置，禁止沿用。
   - **已验证**：via 这类第三方包能直接起三窗，无 `-92`；个别被拦截的包（如小黑盒）才报 `-92`。
2. **焦点在哪，哪个窗口自动弹一次替换面板**：三窗起来后焦点所在窗口自动弹一次；点击下方小窗（`ContainerView.T(index,450,...)`）焦点切过去后，那个小窗也自动弹一次。**每个窗口每次触发三分屏只自动弹一次**（去重）。
   - 用户选完应用后显示所选应用、面板关闭（正常系统流程，不永久显示网格）。
3. 对替换面板 hook：用户可自定义**隐藏/显示哪些应用** + **应用排序**。
4. 大窗下方的小窗走同一套替换面板 → 与第 3 点天然共享同一 hook。

## 逆向锚点（oplus pscanvas classes.dex）
- 菜单"替换应用"触发：`com.oplus.pscanvas.canvasmode.canvas.t.e1(List, View, int)`
  - 命中 `menu_replace` 后：`t.r`(类型 `...canvas.c`) 调 `.l("menu_replace")` 埋点，再调
    `t.r.n(ContainerView, Intent, -1, userId, -1)` → 弹出替换面板，`t.w`(PopupWindow).dismiss()
  - intent 由 `B1.l.Y(context, label, id)` 构造（`android.intent.action.MAIN` + LAUNCHER/INFO），
    加 FLAG_ACTIVITY_NEW_TASK，putExtra `androidx.flexible.userId`
- 弹面板落点：`com.oplus.pscanvas.canvasmode.canvas.c.n(ContainerView, Intent, int, int, int)`
  - 遍历 `ContainerView.getChildrenPositionOnScreen()` 子窗口，构造 `y1/u`(rect+intent+图标+快照)列表
  - 最终 `ContainerView.f3(List<y1/u>)` 注入数据并显示面板；带 bundle `KEY_REPLACE_ANIMATION`
- 焦点切换入口：`ContainerView.T(int index, int duration, int, boolean animate)`（点击窗口时 T(index,450,...)）
  - 当前焦点 task：`adapter.h()`；adapter 类型 `u1.c`，取法 `fieldOfType(container,"u1.c")`
- 替换最终落点（选完应用后，系统走）：
  `ContainerActivity$EmbeddedWindowCallback.startReplaceSplitWhenNormalSplit(taskId,intent,userId,index)`
  → `c2/d2` → 用新 intent 更新该槽位 task（不新建 embedded 窗口，故无 `-92`）

## 实现模块
### A. 自动弹面板（焦点在哪哪弹，每窗一次）
- 新类 `AutoReplace.java`，pscanvas 进程 install。
- hook `ContainerView.T`：当 `duration==450 && animate` 且三窗(count==3)时，延迟 ~150ms 取当前焦点
  `adapter.h()`，若该窗口本次未弹过 → 触发自动弹面板。
- 首次三窗建立后：hook `ContainerView.N2`（三窗布局完成）或 watchdog 里检测 count==3 且首次，
  对当前焦点窗口弹一次。
- 去重：WeakHashMap<container, Set<taskId>> 或按窗口槽位 index 记账；每次触发新一轮三分屏
  （收到 startPocketStudio / N2 重建）时清空记账。
- 触发弹面板：直接反射调 `c.n(ContainerView, intent, -1, userId, -1)`，intent 用当前焦点窗口
  的旧 intent（`task.f()`）或 `B1.l.Y`。**注意**：`c.n` 是系统原生方法，调用它不等于替换，只是弹出选择面板。

### B. 面板数据源过滤/排序
- hook `ContainerView.f3(List)`：before 里对 list 按配置过滤（隐藏指定包）+ 排序。
- list 元素 `y1/u` 含 intent（`u` 的字段需确认，构造器 `(Rect, Intent, Drawable[,Bitmap])`），
  从 intent 取 package/component 判断。
- 配置：存 CanvasPlus App 私有 SP `autoreplace_prefs`：
  - `hidden_pkgs`（隐藏包名集合）
  - `order_pkgs`（排序，逗号分隔包名；未列出的按原序排后面）
  - 走 Settings.Global 还是 SP：pscanvas 进程读不到 App 私有 SP（不同 UID）！
    → 必须用 Settings.Global（pscanvas 有权限读吗？需验证）或通过广播把配置发给 pscanvas 进程缓存。
    **待验证**：pscanvas 进程能否读 Settings.Global `freelinker_*`（CanvasPlus 现有配置就存这里，
    system_server 和 pscanvas 都能读，已验证可行）。→ 采用 Settings.Global 新 key `canvasplus_autoreplace`。

### C. 配置入口
- 提供广播 `com.canvasplus.SET_REPLACE_CFG` 携带 hidden/order，写入 Settings.Global；
  配置界面后续再做（或复用侧边栏设置）。

## 风险与红线
- 全程 hook pscanvas 进程，`am force-stop com.oplus.pscanvas` 重载即可，不重启框架。
- 不动 NeXtep 横屏全屏代码。
- 每加一处写清"为什么"注释。
- 从备份基线 `canvasplus_clean3pkg_20261119_234003` 开始改。
- 配置备份同步：新增 Settings.Global key 要加进 CFG_KEYS（若 CanvasPlus 有对应机制）。

## 待验证/不确定
- `c.n` 直接反射调用能否成功弹出面板（参数 -1/-1/userId 含义）。
- pscanvas 读 Settings.Global 权限。
- `y1/u` 里 intent 的字段访问方式。
- 去重"每次触发三分屏"的定义：以 N2 重建 or START 广播为准。
