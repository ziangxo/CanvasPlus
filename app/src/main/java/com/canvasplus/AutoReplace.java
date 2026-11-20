package com.canvasplus;

import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 三窗自动弹出「替换应用」面板 —— 最小探针（2026-11-20）。
 *
 * 需求：
 *  - 三包起三窗后，焦点在哪个窗口，就给哪个窗口自动弹一次替换面板；
 *  - 之后点下方小窗切焦点，切过去的窗口也自动弹一次；
 *  - 每个窗口每轮三窗会话只弹一次（同一槽位不重复弹）；
 *  - 二分屏绝不触发（count != 3 直接 return，连延迟重试都不做）。
 *
 * 关于焦点（2026-11-20 用户反复强调，红线）：
 *  - **本类绝对不许改动焦点**。不调 ContainerView.T()、不写 adapter 焦点字段、
 *    不模拟点击窗口。焦点完全交给画布的初始布局，本类只「读」当前焦点给它弹面板。
 *  - 曾经尝试过 T(2,450,0,true) 和 T(2,450,0,false) 两种"定位初始焦点"的写法，
 *    用户明确否掉，已全部删除。不要再加回来。
 *  - 旧文档还写过"反射调 c.n 弹面板"——错的。c.n(ContainerView, Intent, int, int, int)
 *    是用户选中应用之后「执行替换 + 动画」的方法，传进去的必须是被选中应用的
 *    launch intent；拿旧 intent 调它只会做一次无意义替换，不会弹面板。
 *
 * 正确链路（dexdump + 真机二分屏验证）：
 *  顶部控制条菜单 t$d.onClick 命中 t1/j.Y（"替换应用"）
 *    → t.C(t).dismiss() 收起菜单
 *    → t.W(t)（静态桥）→ 私有 t.x1()
 *    → t.M0() inflate layout/app_list_layout，poplist.k(2038) 全屏显示
 *  面板选应用：t.e1(List,View,int) → c.n(container, appIntent, -1, userId, -1)
 *
 * 每个窗口的 EmbeddedViewDecor 都有一个 public 字段 h，就是它自己的 t 实例；
 * 所以对某个窗口弹面板 = container.q0(index).h.x1()。
 */
final class AutoReplace {

    private static final String TAG = "AutoReplace";
    private static final String CONTAINER =
            "com.oplus.pscanvas.canvasmode.canvas.view.ContainerView";
    private static final String ADAPTER = "u1.c";

    /**
     * N2 触发后第一次检查窗口数的延迟（2026-11-20 用户定为 400ms）。
     * 命中三窗就立刻弹面板，不再额外加延迟；没到三窗则按 RETRY_DELAY 继续重试。
     */
    private static final long N2_DELAY = 400L;
    /**
     * 用户点击窗口（T(450,animate)）后弹面板的延迟。
     * 原本 550 是等 450ms 焦点动画播完；现在缩到 200，面板在动画收尾时就出来，
     * 体感更跟手。
     */
    private static final long T_DELAY = 200L;

    /**
     * 判定「这一轮刚被 START 触发」的时间窗。
     * 跨进程坑（2026-11-20）：Trigger 在 system_server，本类在 com.oplus.pscanvas，
     * 静态字段不共享，onRoundStart() 改的是 system_server 那份，画布侧永远 pendingRound=false，
     * 结果自动弹面板从来没执行过（日志里只有 installed，没有 round active）。
     * 改由 Trigger 往 Settings.Global 写时间戳、本类读它判断，四窗关一窗回三窗（4→3）
     * 发生在窗口外，不会被误判成新一轮。
     */
    private static final long ROUND_WINDOW_MS = 8000L;
    /** 重试次数 × 间隔要覆盖最慢的三窗落地时间；N2_DELAY 缩短后间隔同步缩短。 */
    /** 侧窗槽位（左窄窗）。这个窗口不自动弹替换面板，见 checkLater。 */
    private static final int SLOT_SIDE = 0;
    private static final int RETRY = 10;
    private static final long RETRY_DELAY = 150L;

    private static volatile boolean installed = false;
    /** 已经应用过「初始焦点 -> 槽位 2」的那一轮（存 Trigger 写的时间戳），防止反复抢焦点。 */
    private static volatile long focusRound = -1L;

    /** Trigger 收到 START 广播后置 true，等第一个三窗容器出现时消费。 */
    private static volatile boolean pendingRound = false;
    /** 本轮由 START 广播激活的容器。只有这里面的容器会自动弹。 */
    private static final WeakHashMap<Object, Boolean> activeRounds = new WeakHashMap<>();
    /** 每个容器本轮已经自动弹过的槽位 index，防止同一窗口反复弹。 */
    private static final WeakHashMap<Object, Set<Integer>> popped = new WeakHashMap<>();

    private AutoReplace() {}

    static void install(ClassLoader loader) {
        try {
            Class<?> view = XposedHelpers.findClass(CONTAINER, loader);

            // 三窗/四窗布局完成。四窗时 count==4 不处理；三窗延迟确认。
            XposedBridge.hookAllMethods(view, "N2", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    checkLater(param.thisObject, N2_DELAY, 0);
                }
            });

            // 点击窗口切焦点。只跟用户点击的 450ms 动画，onLayout 的 T(...,0,...) 忽略。
            XposedBridge.hookAllMethods(view, "T", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        int duration = (Integer) param.args[1];
                        boolean animate = (Boolean) param.args[3];
                        if (duration != 450 || !animate) return;
                        checkLater(param.thisObject, T_DELAY, 0);
                    } catch (Throwable ignored) {
                        // 别的 T 重载，忽略
                    }
                }
            });

            // 替换面板「全部应用」的隐藏/排序（数据层，与面板实例无关，这里挂一次即可）
            hookPanelData(loader);

            installed = true;
            log("installed (three-window replace panel probe)");
        } catch (Throwable t) {
            log("install failed: " + t);
        }
    }

    /**
     * Trigger 收到 com.canvasplus.START 时调用。
     *
     * 注意：这个方法**只在 system_server 里有效**（Trigger 跑在那），本类实际工作的
     * 实例在 pscanvas 进程，静态字段不共享，所以画布侧真正用的判据是 Trigger 写的
     * Settings.Global 时间戳（isFreshRound）。
     *
     * 为什么必须区分本轮：否则用户从四窗关一个窗口回到三窗（4→3）也会被当成
     * "新触发"弹面板；用户明确要求只有触发三分屏那一次才自动弹。
     */
    static void onRoundStart() {
        if (!installed) return;
        pendingRound = true;
        activeRounds.clear();
        popped.clear();
        log("round start");
    }

    private static void checkLater(final Object container, long delay, final int attempt) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Object adapter = fieldOfType(container, ADAPTER);
                int total = count(adapter);
                if (total != 3) {
                    // 二分屏/四窗一律不碰；只有还在等三窗落地时才重试。
                    if (attempt < RETRY) checkLater(container, RETRY_DELAY, attempt + 1);
                    return;
                }

                if (!activeRounds.containsKey(container)) {
                    if (!isFreshRound()) return;
                    activeRounds.put(container, Boolean.TRUE);
                    log("round active (count=3)");
                    // 【2026-11-20 焦点可配置，改掉了旧的「绝对不许动焦点」约束】
                    // 旧约束下的实测结果：三窗落地后画布把焦点留在槽位 0（左侧大窗），
                    // 而 SLOT_SIDE==0 的窗口不弹替换面板 —— 用户看到的就是
                    // 「焦点在左窗、面板也不弹」。现在按配置切一次焦点，
                    // 切完这一轮先 return，让 T() 的 after hook 再走一轮 checkLater，
                    // 弹面板那一步用的就是新焦点，也不会和 450ms 焦点动画打架。
                    if (applyInitialFocus(container, adapter, total)) return;
                }

                int focus = focusOf(adapter);
                if (focus < 0 || focus >= total) return;
                // 侧窗（槽位 0 = 左边那条窄窗）不自动弹替换面板，只有主窗口（1/2）才弹。
                // 用户要求 2026-11-20：侧窗放的是触发前的前台应用，给侧窗弹面板很容易
                // 弹错窗口、也挡住主窗口内容。
                if (focus == SLOT_SIDE) return;

                Set<Integer> done = popped.get(container);
                if (done == null) {
                    done = new HashSet<>();
                    popped.put(container, done);
                }
                if (done.contains(focus)) return;

                if (showPanel(container, focus)) {
                    done.add(focus);
                    log("auto panel slot=" + focus + " total=" + total);
                }
            } catch (Throwable t) {
                log("panel check failed: " + t);
            }
        }, delay);
    }

    /**
     * 手动测试入口（广播 com.canvasplus.TEST_PANEL）。
     *
     * 只用于验证 decor.h.x1() 这条调用链本身，不受 count==3 与轮次限制；
     * 正常功能不会走到这里，所以不会改变二分屏行为。
     */
    static void testShow() {
        if (!installed) return;
        Object container = currentCanvas();
        if (container == null) {
            log("test: no canvas");
            return;
        }
        Object adapter = fieldOfType(container, ADAPTER);
        int total = count(adapter);
        int focus = focusOf(adapter);
        log("test panel total=" + total + " focus=" + focus);
        if (showPanel(container, focus)) log("test panel shown");
    }

    /** 对指定槽位窗口弹它自己的替换面板。 */
    private static boolean showPanel(Object container, int index) {
        try {
            Object decor = XposedHelpers.callMethod(container, "q0", index);
            if (decor == null) {
                log("show panel: decor[" + index + "] null");
                return false;
            }
            // EmbeddedViewDecor.h 是 public 字段，类型就是 canvas.t（菜单/面板控制器）
            Object menu = XposedHelpers.getObjectField(decor, "h");
            if (menu == null) {
                log("show panel: menu null");
                return false;
            }
            // 「全部应用」的隐藏/排序挂在 install() 里（数据层，只挂一次）；
            // 这里只管弹出面板本身。x1() 内部有 isShowing 判重，已显示时会安全返回。
            XposedHelpers.callMethod(menu, "x1");
            return true;
        } catch (Throwable t) {
            log("show panel failed: " + t);
            return false;
        }
    }

    /**
     * 替换面板配置：存在 Settings.Global，格式
     * {"hidden":["pkg1","pkg2"], "order":["pkg3","pkg1"]}
     * pscanvas 与 App 不同 UID，读不到 App 私有 SP，所以走 Settings.Global
     * （本模块的配置一直这么存，两个进程都能读）。
     */
    static final String CFG_KEY = "canvasplus_autoreplace";

    /**
     * 「全部应用」列表的隐藏 / 排序 —— 挂在数据层，不碰 UI。
     *
     * 【2026-11-20 真机实测，别改回 UI 层 hook】
     *
     * 数据流（dexdump + 运行期日志双证）：
     *   t.x1() → t.M0() 弹面板，同时把 t$g（Runnable）丢到后台线程：
     *     t$g.run()
     *       → 清空 t.t / 拿 PackageManager，遍历 UserManager.getUserProfiles()
     *       → B1.l.n1(pm, userId, ArrayList, ArrayMap)：MAIN+LAUNCHER 枚举 →
     *         stream().filter(B1.j).distinct().forEach(B1.k)
     *       → 每个 ResolveInfo 由 B1.l.b(map, userId, pm, info) 造成一个 u1$a$b
     *         塞进 ArrayMap
     *       → 再把 map 变成 List 交给适配器 u1.a，顺序由面板自己的比较器 L1.a 决定
     *         （它内部是 java.text.Collator，中文按拼音排；t.t.sort(t.v) 里的 v 就是它）
     *
     * 为什么不能在 UI 层改（2026-11-20 踩过两次）：
     *   1) t.Q0(RecyclerView, List, Z) 收到的是**空列表**（它自己先 clear 再塞给适配器），
     *      在 Q0 处替换列表完全没效果；
     *   2) t.V0 / t.h1 也不是填数据的路径 —— 运行期日志里 h1 被调用时 t.t 一直是 0，
     *      而且 t 有多个实例，自动弹出的那个面板和 hook 命中的未必是同一个实例。
     *   数据层 hook 没有并发问题（枚举在后台线程做，比较器只在排序时被调），
     *   也不用猜是哪个 t 实例、哪个列表字段。
     *
     * 隐藏 = 在 B1.l.b 造 item 之前拦掉：命中隐藏名单的 ResolveInfo 直接不产生 item，
     *        后续排序、点击索引天然一致（不会出现「隐藏了但点错应用」）。
     * 排序 = 覆盖 L1.a 的比较结果：配置里列出的应用按配置先后，其余保持原来的拼音顺序
     *        排在后面；只是替换返回值，不改变面板自己的排序调用。
     */
    private static void hookPanelData(ClassLoader loader) {
        try {
            final Class<?> factory = XposedHelpers.findClass("B1.l", loader);
            XposedBridge.hookAllMethods(factory, "b", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        // 签名 (ArrayMap, int, PackageManager, ResolveInfo)，别的 b 重载不管
                        if (param.args == null || param.args.length != 4) return;
                        List<String> hidden = cfgList("hidden");
                        if (hidden.isEmpty()) return;
                        String pkg = resolvePkg(param.args[3]);
                        if (pkg == null || !hidden.contains(pkg)) return;
                        // void 方法：setResult(null) 即「不执行原方法」，这个应用不进列表
                        param.setResult(null);
                    } catch (Throwable t) {
                        log("hide hook failed: " + t);
                    }
                }
            });

            final Class<?> comparator = XposedHelpers.findClass("L1.a", loader);
            XposedBridge.hookAllMethods(comparator, "b", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        // 只认 (u1$a$b, u1$a$b) -> int；同类还有个无参 b() 返回 Set
                        if (param.args == null || param.args.length != 2) return;
                        if (!(param.getResult() instanceof Integer)) return;
                        List<String> order = cfgList("order");
                        if (order.isEmpty()) return;
                        int ia = order.indexOf(pkgOf(param.args[0]));
                        int ib = order.indexOf(pkgOf(param.args[1]));
                        if (ia == ib) return;                 // 都没配：保留面板自己的排序
                        if (ia < 0) {
                            param.setResult(1);
                            return;
                        }
                        if (ib < 0) {
                            param.setResult(-1);
                            return;
                        }
                        param.setResult(Integer.compare(ia, ib));
                    } catch (Throwable t) {
                        log("order hook failed: " + t);
                    }
                }
            });
            log("panel data hooks installed");
        } catch (Throwable t) {
            log("hookPanelData failed: " + t);
        }
    }

    /** ResolveInfo → 包名。 */
    private static String resolvePkg(Object info) {
        try {
            Object activityInfo = XposedHelpers.getObjectField(info, "activityInfo");
            Object pkg = XposedHelpers.getObjectField(activityInfo, "packageName");
            return pkg instanceof String ? (String) pkg : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 配置缓存：隐藏钩子对每个应用都会问一次（本机约 125 个应用），每次读 Settings.Global
     * + 解析 JSON 太重。1 秒 TTL 足够——改完配置再打开面板，间隔远大于 1 秒。
     */
    private static final long CFG_TTL = 1000L;
    private static volatile long cfgAt = 0L;
    private static volatile List<String> cfgHidden = java.util.Collections.emptyList();
    private static volatile List<String> cfgOrder = java.util.Collections.emptyList();

    private static List<String> cfgList(String name) {
        if (System.currentTimeMillis() - cfgAt > CFG_TTL) {
            synchronized (AutoReplace.class) {
                if (System.currentTimeMillis() - cfgAt > CFG_TTL) {
                    cfgHidden = readCfgArray("hidden");
                    cfgOrder = readCfgArray("order");
                    cfgAt = System.currentTimeMillis();
                }
            }
        }
        return "hidden".equals(name) ? cfgHidden : cfgOrder;
    }

    /** 读配置里的一个字符串数组，读不到返回空列表。 */
    private static List<String> readCfgArray(String name) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        try {
            android.content.Context ctx = (android.content.Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication");
            if (ctx == null) return out;
            String raw = android.provider.Settings.Global.getString(
                    ctx.getContentResolver(), CFG_KEY);
            if (raw == null || raw.isEmpty()) return out;
            org.json.JSONArray array = new org.json.JSONObject(raw).optJSONArray(name);
            if (array == null) return out;
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i);
                if (value != null && !value.isEmpty()) out.add(value);
            }
        } catch (Throwable t) {
            log("cfg read failed: " + t);
        }
        return out;
    }

    /**
     * 面板 item（u1$a$b）的包名。
     *
     * 坑（2026-11-20 真机日志纠正）：文档里写的「c()=包名、d()=label」是反的。
     * 实测 u1$a$b 的字段是 a/b 两个 String，getter c() 读 a、d() 读 b，
     * 而构造时 (String,String,Bitmap,int,int) 传的是 (label, pkg)，
     * 所以 c() 拿到的是**应用名**（日志里打印出 "GitHub"、"夸克"），
     * 用 c() 去和包名配置比对永远匹配不上（隐藏/排序都静默失效）。
     * 这里以 d() 为准，同时兼容 "pkg/cls" 写法；d() 取不到才退回 c()。
     */
    private static String pkgOf(Object item) {
        String value = stringOf(item, "d");
        if (value == null || value.isEmpty()) value = stringOf(item, "c");
        if (value == null || value.isEmpty()) return null;
        int slash = value.indexOf('/');
        return slash > 0 ? value.substring(0, slash) : value;
    }

    /** 调一个无参 getter，返回字符串；拿不到返回 null。 */
    private static String stringOf(Object item, String getter) {
        try {
            Object value = XposedHelpers.callMethod(item, getter);
            if (value instanceof String) return (String) value;
            if (value != null) {
                Object name = XposedHelpers.callMethod(value, "getPackageName");
                if (name instanceof String) return (String) name;
            }
        } catch (Throwable ignored) {
            // 非预期结构，忽略
        }
        return null;
    }

    /**
     * 这一轮是不是刚被 START 触发（距 Trigger 写的时间戳在 ROUND_WINDOW_MS 内）。
     * 读 Settings.Global 而不是静态字段，因为本类在 pscanvas 进程、Trigger 在 system_server。
     */
    private static boolean isFreshRound() {
        long mark = roundMark();
        long dt = System.currentTimeMillis() - mark;
        boolean fresh = mark > 0 && dt >= 0 && dt < ROUND_WINDOW_MS;
        if (!fresh) log("round mark stale: dt=" + dt);
        return fresh;
    }

    /** 读 Trigger 写的那一轮时间戳；读不到返回 0。 */
    private static long roundMark() {
        try {
            android.content.Context ctx = (android.content.Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication");
            if (ctx == null) return 0L;
            return android.provider.Settings.Global.getLong(
                    ctx.getContentResolver(), Trigger.ROUND_MARK, 0L);
        } catch (Throwable t) {
            log("round mark read failed: " + t);
            return 0L;
        }
    }

    private static int focusOf(Object adapter) {
        try {
            Object focus = XposedHelpers.callMethod(adapter, "h");
            return focus instanceof Integer ? (Integer) focus : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---------------- 三分屏启动后的焦点 ----------------

    /**
     * 焦点目标存在 Settings.Global（App 侧配置页 ConfigActivity 写），值：
     * -1 = 不干预（保持画布默认，实测常落在槽位 0 左窗）
     * 0 = 左窗 / 1 = 上窗（右上）/ 2 = 右下窗
     *
     * 槽位号就是三窗的启动顺序（Trigger.fixedPackages 给的 packages 顺序），
     * 和替换面板各窗口的 index 是同一套编号。
     */
    private static final String FOCUS_KEY = "canvasplus_triple_focus";
    /** 配置缓存：-2 = 还没读过。1 秒 TTL 与 cfgList 一致。 */
    private static volatile int focusCfg = -2;
    private static volatile long focusCfgAt = 0L;

    private static int tripleFocusCfg() {
        long now = System.currentTimeMillis();
        if (focusCfg != -2 && now - focusCfgAt < 1000L) return focusCfg;
        int value = -1;
        try {
            android.content.Context ctx = appContext();
            if (ctx != null) {
                String raw = android.provider.Settings.Global.getString(
                        ctx.getContentResolver(), FOCUS_KEY);
                if (raw != null && !raw.isEmpty()) value = Integer.parseInt(raw.trim());
            }
        } catch (Throwable t) {
            log("focus cfg read failed: " + t);
        }
        focusCfg = value;
        focusCfgAt = now;
        return value;
    }

    /** pscanvas 进程里的 Application context（读 Settings.Global 用）。 */
    private static android.content.Context appContext() {
        try {
            return (android.content.Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 三窗落地后按配置切一次焦点。
     *
     * 为什么必须切：画布自己把焦点留在槽位 0，而 SLOT_SIDE==0 的窗口不弹替换面板，
     * 「焦点在左窗」时用户既没有预期的焦点、又等不到面板。
     *
     * 为什么每轮只切一次：T() 有 450ms 焦点动画，动画期间的 onLayout 还会调
     * T(...,0,...)（duration=0 那种我们不接）。用 Trigger 写的那一轮时间戳记账，
     * 同一轮重复进来直接跳过，避免和画布自己的初始化互相抢。
     *
     * @return true = 这一轮刚发起切焦点，调用方应先返回，等 T 的 after hook 再检查
     */
    private static boolean applyInitialFocus(Object container, Object adapter, int total) {
        int want = tripleFocusCfg();
        if (want < 0 || want >= total) return false;
        long mark = roundMark();
        if (mark <= 0) return false;
        if (focusRound == mark) return false;
        int now = focusOf(adapter);
        if (now == want) {
            // 画布自己已经落在目标窗口，记账后不再干预
            focusRound = mark;
            return false;
        }
        focusRound = mark;
        log("apply initial focus -> " + want + " (was " + now + ")");
        try {
            // 坑（2026-11-20 对齐上游 v0.2.0 时发现）：CanvasLayout 的 T() hook 现在会
            // 把 T() 当成"用户点了某个窗口"来处理——同步 fourZoomed / 给每个 task 设 p0。
            // 这里是我们程序化切焦点，如果被当成点击，三窗会直接进入"放大态"，
            // 刚起来的总览（fitThreeDefault）就被顶掉，看起来像一进画布就单窗全屏。
            // 所以用一个包内静态标志告诉 T() hook：这次只切焦点，别动缩放状态。
            CanvasLayout.programmaticFocus = true;
            try {
                XposedHelpers.callMethod(container, "T", want, 450, 0, true);
            } finally {
                CanvasLayout.programmaticFocus = false;
            }
            return true;
        } catch (Throwable t) {
            log("apply initial focus failed: " + t);
            return false;
        }
    }

    private static int count(Object adapter) {
        try {
            Object value = XposedHelpers.callMethod(adapter, "getCount");
            return value instanceof Integer ? (Integer) value : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 从 ActivityThread.mActivities 找 ContainerActivity，再取它的 ContainerView。 */
    private static Object currentCanvas() {
        try {
            Object thread = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentActivityThread");
            Object records = XposedHelpers.getObjectField(thread, "mActivities");
            if (!(records instanceof java.util.Map)) return null;
            for (Object record : ((java.util.Map<?, ?>) records).values()) {
                Object activity = XposedHelpers.getObjectField(record, "activity");
                if (activity == null) continue;
                String name = activity.getClass().getName();
                if (name.equals("com.oplus.pscanvas.canvasmode.canvas.ContainerActivity")) {
                    return XposedHelpers.callMethod(activity, "x0");
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object fieldOfType(Object owner, String className) {
        Class<?> type = owner.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.getType().getName().equals(className)) continue;
                try {
                    field.setAccessible(true);
                    return field.get(owner);
                } catch (Throwable ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }
}
