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

## 逆向锚点（oplus pscanvas classes.dex，2026-11-20 真机+dexdump 校正）
> 2026-11-20 更正：旧版把 `c.n` 当成"弹面板"，实际 `c.n` 是**选完应用之后执行替换+动画**。
> 弹面板是 `t.M0()`（由 `t.x1()` 调），选应用才是 `t.e1(...)` → `c.n(...)`。

- **菜单入口（顶部条 → 菜单 → 替换应用）**：
  `t$d.onClick` 里把点击项标题与 `t1/j.Y`（字符串资源
  `ps_canvas_control_bar_menu_replace` = "替换应用"）比较，命中后：
  1. `t.C(t).dismiss()` 收起窗口菜单
  2. `t.W(t)`（静态桥）→ 私有 `t.x1()`
  - `t.x1()`：`if (t.w != null && t.w.isShowing()) return; M0();`
- **弹面板（真正构建/显示）**：`t.M0()`
  - inflate `layout/app_list_layout`（标题子布局 `layout/app_list_title_view`）
  - 创建 `com.coui.appcompat.poplist.k`（PopupWindow），`setWindowLayoutType(2038)`（TYPE_APPLICATION_OVERLAY）
  - `showAtLocation(t.n, Gravity.CENTER, 0, 0)`，全屏
  - 两个 COUIRecyclerView：`t.A`（最近使用，数据 `t.u`）、`t.B`（全部应用，数据 `t.t`）
  - 搜索框 `t.z`（COUISearchBar）；`t.C` 是 ViewStub；`t.O` 是空态/占位 View
  - 绑定列表：`t.Q0(RecyclerView, List, boolean)` → `new u1.a(list, context)` + `GridLayoutManager`
- **面板 item 数据**：`u1.a$b`（字段 `a`=label、`b`=包名/组件、`c`=int、`d`=Bitmap、`e`=boolean、`f`=int）
  - 快捷读取：**`c()` 读字段 a → 是 label**；**`d()` 读字段 b → 是包名/组件**；
    `e()`=type、`f()`=id。
  - 2026-11-20 真机日志纠正：旧文档写反了（写成 c()=包名、d()=label），
    按反的写会导致隐藏/排序全部静默失效（拿应用名去比包名）。
- **面板点击选择应用**：`t.e1(List, View, int)`
  - `item = list.get(index)`（`u1/a$b`）；`label=item.d()`、`id=item.f()`
  - `t.n.S = true`（ContainerView 动画中标志）
  - `intent = B1.l.Y(context, label, id)`（MAIN + LAUNCHER/INFO），加 `FLAG_ACTIVITY_NEW_TASK`、
    `putExtra("androidx.flexible.userId", userId)`
  - `c.l("menu_replace")` 埋点
  - `c.n(container, intent, -1, userId, -1)` → **执行替换**，然后 `t.w.dismiss()`
- **替换执行**：`c.n(ContainerView, Intent, int a, int userId, int b)`
  - 目标槽位 = `(b == -1 ? controller.n() : b)`；用传进来的应用 `Intent` 重建该槽位 `u1.d` task
  - `container.f3(List<u1/d>)` 注入新 task 数据（注意不是 `y1/u`）
  - `B1.l.a1(...)` 算新矩形；`y1/u` 是动画帧（rect+intent+图标+快照）
  - `CanvasMaskManager(y1/n).p0/r0` 跑替换动画
- **焦点切换入口**：`ContainerView.T(int index, int duration, int, boolean animate)`（点击窗口 T(index,450,...)）
  - 当前焦点 task：`adapter.h()`；adapter 类型 `u1.c`，取法 `fieldOfType(container,"u1.c")`
- **窗口 decor 与菜单控制器**：`container.q0(index)` → `EmbeddedViewDecor`；
  其 **public** 字段 `h` 就是该窗口的 `t` 实例（`t.u1(decor)` 把 decor 存进 `t.R`）。
- **搜索**：`ContainerActivity.n0(String)` → `getChildEmbeddedViewDecorToZoom().h.m0(text)`
  → `t.Z.d(text)`（`t.Z` 类型 `...canvas.J`）。
- **最近使用数据源**：`t.l1()` 反射调 `com.oplus.splitscreen.OplusSplitScreenManagerInternal
  #getRecentUsedAppWithMultiApp(int, long, boolean, List)`，时间窗 `0x0A4CB800` = 172800000ms（48 小时）。
- **替换最终落点（选完应用后，系统走）**：
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
- 触发弹面板（**已用真机验证的路径**）：取焦点窗口 decor `container.q0(index)`，读其 public 字段
  `decor.h`（`t` 实例），反射调 `t.x1()`（内部判 `t.w` 未显示才 `M0()` 构建+show）。
  **不要调 `c.n`**：`c.n(ContainerView, Intent, int, int, int)` 是"用户选中应用后"执行替换+动画，
  传进去的 Intent 必须是被选中应用的 launch intent；拿旧 intent 调它只会触发一次无意义的替换动画，
  不会弹面板（旧版本此处的结论已证伪）。

### B. 面板数据源过滤/排序（**2026-11-20 真机推翻旧方案，已按新方案实现并验证**）
- 面板列表**不是** `ContainerView.f3`（它注入的是 `u1/d` task 数据，不是应用列表）。
- **旧方案（已废弃，别改回去）**：在 `t.Q0(COUIRecyclerView, List, boolean)` 里替换 list。
  实测无效：`Q0` 开头就 `list.clear()`，它把**空 ArrayList** 交给适配器 `u1.a`
  （`u1.a` 构造器原样保存引用、不拷贝），真正的应用数据是随后在**后台线程**
  `t$g.run()` 里异步填的。日志只有一句 `filter would empty the list`。
- **`t.V0` / `t.h1` 也不是填数据路径**：运行期日志里 h1 被调用时 `t.t` 恒为 0，
  且 `t` 有多个实例（自动弹出的面板未必是 hook 命中的那个实例）。
- **新方案（当前实现，已验证隐藏 + 排序都生效）**，见 `AutoReplace.hookPanelData(loader)`：
  - 数据流：`t$g.run()` → `B1.l.n1(pm, userId, ArrayList, ArrayMap)` 用
    `queryIntentActivitiesAsUser(MAIN+LAUNCHER)` 枚举 → `stream().filter(B1.j).distinct()
    .forEach(B1.k)` → 每个 ResolveInfo 由 `B1.l.b(ArrayMap, int, PackageManager, ResolveInfo)`
    造成一个 `u1$a$b` 塞进 ArrayMap → 转 List 给适配器。
  - **隐藏**：hook `B1.l.b`，按 `param.args[3].activityInfo.packageName` 命中隐藏名单
    就 `setResult(null)`（void 方法 = 不执行原方法），该应用根本不进列表，
    后续排序/点击索引天然一致。
  - **排序**：hook 面板自己的比较器 `L1.a.b(u1$a$b, u1$a$b)`（字段 `t.v`，内部是
    `java.text.Collator`，中文按拼音），after 里用配置顺序覆盖返回值；
    配置外的应用保持原拼音序排在后面。
  - 配置缓存 TTL 1s（`B1.l.b` 每个应用调一次，不能每次读 Settings.Global）。
- item `u1/a$b`：`c()`=**label**、`d()`=**包名/组件**（`e()`=type、`f()`=id）。
- 配置：**Settings.Global `canvasplus_autoreplace`**，JSON `{"hidden":[...],"order":[...]}`。
  不用 App 私有 SP —— pscanvas 是不同 UID，读不到。
  权限：写 Settings.Global 需要 `WRITE_SECURE_SETTINGS`（signature 级），
  本机已 `adb shell pm grant com.canvasplus android.permission.WRITE_SECURE_SETTINGS`；
  没授权时配置页降级为发广播给 system_server 里的 `Trigger` 代写。

### D. 三分屏启动后的焦点（2026-11-20 新增）
- 现象：三窗落地后画布自己把焦点留在**槽位 0（左窗）**，而 `SLOT_SIDE == 0` 的窗口
  按设计不弹替换面板 —— 用户看到的是「焦点在左窗、面板也不弹」。
- 配置：`Settings.Global` 的 `canvasplus_triple_focus`
  （`-1` 不干预 / `0` 左窗 / `1` 上窗 / `2` 右下窗），由 ConfigActivity 的
  「三分屏启动后的焦点窗口」四个按钮写入。
- 实现：`AutoReplace.applyInitialFocus()`，在 `checkLater` 确认 count==3 的那一次调用
  `ContainerView.T(index, 450, 0, true)`（就是用户点窗口切焦点同一条链路）。
  切完先 return，让 `T` 的 after hook 再走一轮 `checkLater`，弹面板用的就是新焦点，
  也不会和 450ms 焦点动画打架；用 Trigger 写的那轮时间戳记账，同一轮只切一次。
- 实测：配置 2 → `auto panel slot=2 total=3`；配置 1 → `test panel total=3 focus=1`。
- 白板侧连带修正：`BlankActivity` **不能**在 `onStop` 里 `finishAndRemoveTask()`
  （画布里非焦点窗口常态 onStop，会导致白板自杀、三窗起不来；
  `isInMultiWindowMode()` 在嵌入瞬间还返回 false，也救不了）。
  现在只保留 manifest 的 `excludeFromRecents`。

### C. 配置入口（已实现）
- `com.canvasplus.ConfigActivity`（app 侧，纯 framework UI，无 androidx）：
  搜索框 + 每行「隐藏勾选框 / 应用名+包名 / ↑↓」，保存写入上面的 JSON。
  入口：桌面应用图标（LAUNCHER intent-filter）或 `adb shell am start -n com.canvasplus/.ConfigActivity`。
- 兜底广播 `com.canvasplus.SET_REPLACE_CFG`（`--es json ...`）由 system_server 的
  `Trigger` 代写 Settings.Global。
- 排序用 `↑/↓` 而不是拖动：手工 javac + android.jar 构建，没有 androidx，
  引 `ItemTouchHelper` 会 NoClassDefFoundError。

## 风险与红线
- 全程 hook pscanvas 进程，`am force-stop com.oplus.pscanvas` 重载即可，不重启框架。
- 不动 NeXtep 横屏全屏代码。
- 每加一处写清"为什么"注释。
- 从备份基线 `canvasplus_clean3pkg_20261119_234003` 开始改。
- 配置备份同步：新增 Settings.Global key 要加进 CFG_KEYS（若 CanvasPlus 有对应机制）。

## 待验证/不确定
- `t.x1()` 在 pscanvas 进程内反射调用是否受 hidden API/私有方法限制（Xposed 走反射一般可行，需实测）。
- 三分屏下焦点窗口的 decor 取法：`container.q0(focusIndex)` 还是 `getChildEmbeddedViewDecorToZoom()`。
- pscanvas 读 Settings.Global 权限。
- 去重"每次触发三分屏"的定义：以 N2 重建 or START 广播为准。

## 真机实测记录（2026-11-20，二分屏）
- 设备：OnePlus PKR110 / ColorOS V16.1.0 / 1264x2780；pscanvas 为
  `/system_ext/app/OplusFlexibleWindowUI/OplusFlexibleWindowUI.apk`（单 classes.dex，4,320,808 字节）。
- 当前画布：二分屏，小黑盒（com.max.xiaoheihe）为放大/focus 窗口。
- 窗口控制条：`dumpsys window` 里为 `Embedded{CanvasControlBar}`，
  frame `(0,140)-(1264,280)`，可点手柄 touchableRegion `(457,140)-(807,280)`；点 `(632,210)` 弹菜单。
- 菜单项纵坐标（截图像素）：全屏 y≈220、浮窗 y≈307、缩小窗口 y≈458、**替换应用 y≈780**、关闭 y≈933；
  实际点击用 `input tap 695 780` 命中替换应用。
- 替换面板：
  - 窗口：`PopupWindow`（pscanvas，uid 10249），type `APPLICATION_OVERLAY`(2038)，全屏 1264x2780；
    另一个 `APPLICATION_PANEL`(1000) 的 `PopupWindow` 是窗口菜单，二者都是 pscanvas 的 `poplist.k`。
  - 布局：`layout/app_list_layout`（标题子布局 `layout/app_list_title_view`）
  - UI：标题 “替换应用” `[318,318]-[446,382]`；搜索框 `[56,427]-[1208,609]`（EditText 提示“搜索应用”）；
    分区 “最近使用” `[112,637]-[308,703]`、“全部应用” `[112,1095]-[308,1161]`
  - item：`com.oplus.pscanvas:id/click_item`（LinearLayout）内含 icon ImageView +
    `text_label` TextView；分区标题 `text_title`；4 列网格，item 宽约 190~310px
  - 最近使用示例：QQ / GitHub / 计算器 / FreeLinker（y=966）
  - 全部应用示例：Anikku / BetterVia / 巴法 / 百度地图（y=1424）…
  - 搜索：`ContainerActivity.n0(String)` → 焦点窗口 `decor.h.m0(text)` → `t.Z.d(text)`
- 关键纠正：面板显示与 `c.n` 无关。`c.n` 是面板 item 点击后 `t.e1` 调用的替换执行方法；
  面板显示入口是菜单 `t$d` → `t.W()` → `t.x1()` → `t.M0()`。
- 证据文件：`_rev/pscanvas_dump.txt`（dexdump 全量）、`_rev/_cp2w.png`（二分屏）、
  `_rev/_cp_menu.png`（窗口菜单）、`_rev/_cp_replace.png`（替换面板）、
  `_rev/_cp_replace.xml`（面板 UI 树）、`_rev/app_list_layout.txt`（布局资源映射）。
