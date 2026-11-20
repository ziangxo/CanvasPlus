package com.canvasplus;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.XposedHelpers;

/**
 * 广播入口。START/ADD/LAYOUT 三个 action。
 *
 * ===== 三屏规则已写死（2026-11-20 用户要求） =====
 * 触发命令只要一条，不再传包名：
 *   am broadcast -a com.canvasplus.START
 * 打开的三窗固定是：
 *   左窗 = 触发前的前台应用（取不到就按 MRU 往 recents 后面找下一个可用的，
 *          黑名单是画布/桌面/SystemUI，且会跳过翻译和计算器本身）
 *   上窗 = 计算器 com.coloros.calculator
 *   右下窗 = 翻译 com.coloros.translate
 * 三个窗口一次性交给 startPocketStudio（不再分两段补 ADD，那会让面板先亮两窗再跳三窗）。
 * **触发后不许切换焦点**（用户 2026-11-20 红线）：不调 T()、不写焦点字段，
 * AutoReplace 只读取当前焦点去弹替换面板。
 *
 * 广播里仍可以带 --ei mode N 覆盖布局模式（默认 4 = 三/四窗布局链路）；
 * 老的 --esa packages / --ez foreground 参数已经不再读取，传了也不影响结果。
 *
 * 曾尝试改写槽位放伪桌面，但第三方 Activity 作为 embedded 窗口启动会被
 * ColorOS 的 atms_intercept 用 -92 拒绝（普通包名也一样），已回滚到原始逻辑。
 * 坑：force-stop pscanvas 后必须等 receiver 重新注册（日志出现 trigger armed）
 * 再发广播，否则广播没人收、三窗起不来。
 */
final class Trigger {

    private static final String ACTION_START = "com.canvasplus.START";
    private static final String ACTION_ADD = "com.canvasplus.ADD";
    private static final String ACTION_LAYOUT = "com.canvasplus.LAYOUT";
    /** 仅用于验证 AutoReplace 的 decor.h.x1() 链路；正常功能不会发。 */
    private static final String ACTION_TEST_PANEL = "com.canvasplus.TEST_PANEL";
    /**
     * 设置替换面板的隐藏/排序配置，extra "json"：
     * {"hidden":["pkg1"],"order":["pkg2","pkg1"]}
     * 由 system_server 侧写入 Settings.Global（pscanvas 读不到 App 私有 SP）。
     */
    private static final String ACTION_SET_CFG = "com.canvasplus.SET_REPLACE_CFG";
    /** 老的 --esa packages 参数，2026-11-20 起三屏列表写死，不再读取。 */
    private static final String EXTRA_PACKAGES = "packages";
    /**
     * 「本轮触发」时间戳，写到 Settings.Global 传给画布进程。
     * 坑（2026-11-20）：AutoReplace 装在 com.oplus.pscanvas，Trigger 在 system_server，
     * 静态字段不跨进程——AutoReplace.onRoundStart() 只改了 system_server 里那份的
     * 字段，画布侧永远认为没有新一轮，自动弹面板和按槽位切焦点一次都不会执行。
     */
    static final String ROUND_MARK = "canvasplus_round_ts";
    private static final String MANAGER = "com.oplus.flexiblewindow.FlexibleWindowManager";
    private static final String SERVICE = "com.android.server.wm.FlexibleWindowManagerService";

    /**
     * 写死的三屏尾巴（2026-11-20 用户要求）：packages[1] = 上窗，packages[2] = 右下窗口。
     *
     * 【2026-11-20 第二次修改：原来的计算器 / 翻译换成了两个白板占位 App】
     * 上窗   = com.ziang.a1（原来 com.coloros.calculator）
     * 右下窗 = com.ziang.a2（原来 com.coloros.translate）
     * 理由：三屏里再冷启动计算器（16.4.2）和翻译（16.1.28）两个真实应用，
     * 内存和启动时间都白花；用户要的是「两个空白占位窗口」，所以换成
     * blankapps 里那两个 13KB 的白板。想换回真实应用只改这两行。
     *
     * 注意：包顺序只决定「哪个窗口装哪个应用」，不决定哪个窗口展开。
     * 焦点靠启动顺序保证：packages[2] 不随 startPocketStudio 一起起，而是延迟用
     * ADD 加进来，新加入的窗口天然拿焦点 → 落在下窗口，不需要任何焦点 API。
     */
    private static final String PKG_TRANSLATE = "com.ziang.a2";
    private static final String PKG_CALCULATOR = "com.ziang.a1";
    private static final String[] FIXED_TAIL = {PKG_CALCULATOR, PKG_TRANSLATE};
    /** 黑名单：这些包不能当左窗，否则会把画布自己/桌面塞进去。 */
    private static final String[] BLOCKED = {
            "com.oplus.pscanvas", "com.android.launcher", "com.android.systemui"};

    private Trigger() {}

    static void arm(ClassLoader loader) {
        arm(loader, true);
    }

    /**
     * @param allowStart 是否处理 START 广播。
     * 坑（2026-11-20 LSPosed 日志实测）：Trigger 同时在 system_server(android) 和
     * com.oplus.pscanvas 两个进程注册了 receiver，两处都会处理 START，于是每发一条
     * START 就拉起两轮画布——同一秒里 (system) 和 (pscanvas) 各打一条
     * "canvas requested, intents=3"。后果是 recents 里累积 9 个 pscanvas 的 canvas
     * task（dumpsys activity recents 实测 Recent #4~#12）。
     * 所以 START 只允许 system_server 处理，画布进程只保留 ADD。
     */
    static void arm(ClassLoader loader, boolean allowStart) {
        arm(loader, allowStart, 0);
    }

    private static void arm(final ClassLoader loader, final boolean allowStart,
                            final int attempt) {
        long delay = attempt == 0 ? 100L : 300L;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (register(loader, allowStart)) return;
            if (attempt < 20) arm(loader, allowStart, attempt + 1);
        }, delay);
    }

    private static boolean armed = false;

    private static IntentFilter buildFilter() {
        IntentFilter filter = new IntentFilter(ACTION_START);
        filter.addAction(ACTION_ADD);
        filter.addAction(ACTION_LAYOUT);
        filter.addAction(ACTION_TEST_PANEL);
        filter.addAction(ACTION_SET_CFG);
        return filter;
    }

    private static boolean register(ClassLoader loader, boolean allowStart) {
        if (armed) return true;
        try {
            Object thread = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", loader),
                    "currentActivityThread");
            Context appContext = (Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", loader),
                    "currentApplication");
            final Context context = appContext != null
                    ? appContext
                    : (Context) XposedHelpers.callMethod(thread, "getSystemContext");
            if (context == null) {
                log("no context for trigger");
                return false;
            }

            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent intent) {
                    // 手动验证面板调用链：只在显式广播时执行，正常流程不会触发。
                    if (ACTION_TEST_PANEL.equals(intent.getAction())) {
                        AutoReplace.testShow();
                        return;
                    }
                    if (ACTION_LAYOUT.equals(intent.getAction())) {
                        CanvasLayout.applyOrientation(intent.getIntExtra("orientation", 0));
                        return;
                    }
                    if (ACTION_SET_CFG.equals(intent.getAction())) {
                        // 只让 system_server 那份写（它一定有 WRITE_SECURE_SETTINGS）
                        if (!allowStart) return;
                        String json = intent.getStringExtra("json");
                        if (json == null || json.isEmpty()) return;
                        try {
                            android.provider.Settings.Global.putString(
                                    context.getContentResolver(), AutoReplace.CFG_KEY, json);
                            log("replace cfg updated: " + json);
                        } catch (Throwable error) {
                            log("replace cfg failed: " + error);
                        }
                        // 画布「缩小后点击窗口不放大」开关：没 grant 写权限时由这里代写。
                        // key 必须与 CanvasLayout.noClickZoom() 一致。
                        String zoom = intent.getStringExtra("no_click_zoom");
                        if (zoom != null && !zoom.isEmpty()) {
                            try {
                                android.provider.Settings.Global.putString(
                                        context.getContentResolver(),
                                        "canvasplus_no_click_zoom", zoom);
                                log("no click zoom updated: " + zoom);
                            } catch (Throwable error) {
                                log("no click zoom failed: " + error);
                            }
                        }
                        return;
                    }
                    if (ACTION_ADD.equals(intent.getAction())) {
                        String pkg = intent.getStringExtra("pkg");
                        if (pkg == null || pkg.isEmpty()) {
                            setResultCode(0);
                            return;
                        }
                        // 对齐上游 v0.2.0：侧边栏改用心跳 → 有序广播问"能不能接"。
                        // 这里当场看 Activity 状态和窗口数，用 resultCode 回答；
                        // 侧边栏收到 0 会自己重放 startActivityAsZoomWindow 开小窗。
                        // 为什么不在侧边栏判断：跨进程只能拿到旧状态，画布可见性
                        // 这个进程里才是准的（isVisible 在本机还会读成 false）。
                        Object activity = currentCanvas();
                        int count = activity == null ? -1 : canvasCount(activity);
                        boolean visible = isForeground(activity);
                        boolean room = count >= 0 && count < 4;
                        log("add request " + pkg + " visible=" + visible + " count=" + count);
                        if (intent.getBooleanExtra("ordered", false)) {
                            setResultCode(visible && room ? 1 : 0);
                        }
                        if (visible && room) addToCanvas(context, pkg);
                        return;
                    }
                    if (!ACTION_START.equals(intent.getAction())) return;
                    // 画布进程也注册过 receiver，但 START 只许 system_server 处理，
                    // 否则一次广播拉起两轮画布（见 arm(loader, allowStart) 注释）。
                    if (!allowStart) return;
                    // 2026-11-20 用户要求：三屏规则写死，不再需要广播里的 packages。
                    //   左窗 = 触发前的前台应用（取不到就退到最近任务里找下一个可用的）
                    //   上窗 = 翻译 com.coloros.translate
                    //   大窗 = 计算器 com.coloros.calculator（触发即焦点展开）
                    // 所以触发命令只剩：am broadcast -a com.canvasplus.START
                    CanvasLayout.forcedMode = intent.getIntExtra("mode", 4);
                    // 标记"新一轮三窗"：AutoReplace 只在收到 START 的这一轮里自动弹面板，
                    // 四窗关一窗回到三窗（4→3）不会被误当成新触发。
                    AutoReplace.onRoundStart();
                    // 跨进程把「本轮」传到画布进程（见 ROUND_MARK 注释）。
                    try {
                        android.provider.Settings.Global.putLong(
                                context.getContentResolver(), ROUND_MARK,
                                System.currentTimeMillis());
                    } catch (Throwable error) {
                        log("round mark failed: " + error);
                    }
                    String[] packages = fixedPackages(loader);
                    log("trigger " + Arrays.toString(packages) + " mode=" + CanvasLayout.forcedMode);
                    // 一次性把三个窗口全部交给 startPocketStudio。
                    //
                    // 曾经试过「先起两个、再用 ADD 补第三个」的两段方案，为的是让第三个
                    // 窗口最后加入、天然拿焦点。实测虽然焦点确实落在下窗口，但代价是
                    // 面板先亮两窗、再等 0.8~3.5 秒才跳成三窗，观感像坏掉了，
                    // 用户 2026-11-20 要求回滚成一次成型。
                    startPocketStudio(context, loader, packages);
                    // 2026-11-20 起决定不调用 enterWorkspace（原上游设计是在这里同步调
                    // startPocketStudio + enterWorkspace，后者用 recents 里的 taskId 把
                    // 已有实例转成灵活窗口）。停用理由，三条都有日志证据：
                    // 1) 时序不可靠：紧跟 startPocketStudio 同步调用时，目标 app 的 task
                    //    还没创建，recentTasks() 只返回 com.oplus.pscanvas，每个包都
                    //    "no task for X"、workspace entered=0，整段是空转；
                    // 2) 改成延迟重试能拿到 taskId（20:51:54 实测 entered=3，最终仍是
                    //    count=3，对已在画布里的 task 是幂等的），但调用点会落到触发后
                    //    0.9~2.3s，用户若已退出画布会被重新拽回前台，收益不抵风险；
                    // 3) 不需要它：startPocketStudio 自己就能开三窗（21:02:23
                    //    entered=0，21:02:53 仍然是 canvas visible count=3）。
                    // 代价：画布里的 app 是冷启动的新实例，不恢复上次停留的页面。
                    // 要恢复就取消注释下面这行（enterWorkspace 的返回值可忽略）。
                    // enterWorkspace(loader, packages);
                }
            };
            context.registerReceiver(receiver, buildFilter(),
                    Context.RECEIVER_EXPORTED);
            armed = true;
            log("trigger armed");
            return true;
        } catch (Throwable error) {
            log("trigger registration failed: " + error);
            return false;
        }
    }

    private static void addToCanvas(android.content.Context context, String pkg) {
        addToCanvas(context, pkg, 0);
    }

    private static void relayoutLater(final Object activity) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                XposedHelpers.callMethod(activity, "N2");
            } catch (Throwable ignored) {
            }
        }, 300L);
    }

    private static boolean addDirect(Object activity, android.content.Context context,
                                     Intent launch) {
        try {
            Object view = XposedHelpers.callMethod(activity, "x0");
            if (view == null) return false;
            Object adapter = adapterOf(view);
            if (adapter == null) return false;
            Object taskData = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("B1.l", activity.getClass().getClassLoader()),
                    "n0", activity, launch, -2, -1);
            if (taskData == null) {
                Object bounds = null;
                try {
                    bounds = XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass("B1.l",
                                    activity.getClass().getClassLoader()),
                            "o", activity, launch, -2, 0);
                } catch (Throwable ignored) {
                }
                log("add direct: taskData null bounds=" + bounds
                        + " extra=" + launch.getExtras());
                return false;
            }
            XposedHelpers.callMethod(taskData, "s0", 0);
            XposedHelpers.callMethod(taskData, "l0",
                    launch.getIntExtra("androidx.activity.LaunchEmbeddedTaskId", -1));
            Object controller = XposedHelpers.callMethod(view, "getContainerController");
            XposedHelpers.callMethod(controller, "d", taskData);
            return true;
        } catch (Throwable error) {
            log("add direct failed: " + error);
            return false;
        }
    }

    private static Object adapterOf(Object view) {
        for (java.lang.reflect.Field field : view.getClass().getDeclaredFields()) {
            if (field.getType().getName().equals("u1.c")) {
                try {
                    field.setAccessible(true);
                    return field.get(view);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static void addToCanvas(final android.content.Context context, final String pkg,
                                    final int attempt) {
        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) {
                log("no launch intent for " + pkg);
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            Object activity = currentCanvas();
            if (activity != null) {
                int before = canvasCount(activity);
                if (before >= 4) {
                    log("canvas full(" + before + "), skip " + pkg);
                    return;
                }
                try {
                    XposedHelpers.callMethod(activity, "j0", launch, 0, false);
                    int after = canvasCount(activity);
                    log("j0 add " + pkg + " " + before + "->" + after
                            + " try=" + attempt);
                    if (after > before) {
                        relayoutLater(activity);
                        return;
                    }
                } catch (Throwable error) {
                    log("j0 direct failed: " + error);
                }

                if (addDirect(activity, context, launch)) {
                    int now = canvasCount(activity);
                    log("direct add " + pkg + " " + before + "->" + now
                            + " try=" + attempt);
                    if (now > before) {
                        relayoutLater(activity);
                        return;
                    }
                }

                if (attempt < 3) {
                    new Handler(Looper.getMainLooper()).postDelayed(
                            () -> addToCanvas(context, pkg, attempt + 1), 400L);
                    return;
                }
            }

            Intent canvas = new Intent();
            canvas.setClassName("com.oplus.pscanvas",
                    "com.oplus.pscanvas.canvasmode.canvas.ContainerActivity");
            canvas.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            Bundle extra = new Bundle();
            extra.putParcelable("androidx.activity.NewReplacingIntent", launch);
            extra.putInt("androidx.activity.NewReplacingUserId", 0);
            canvas.putExtras(extra);
            canvas.putExtra("androidx.activity.AddIntentToSplit", true);
            context.startActivity(canvas);
            log("add intent sent " + pkg);
        } catch (Throwable error) {
            log("add intent failed: " + error);
        }
    }

    /**
     * 画布在不在前台。
     *
     * 坑（上游 v0.2.0 记录）：这里用 Activity/Context 的 isVisible() 拿到的值不对
     * （decor 明明是 VISIBLE 却返回 false），而侧边栏是悬浮窗，盖在画布上时不该算
     * "离开前台"。直接看 Activity 自己的两个状态字段：侧边栏盖上来不打断 resumed，
     * 切到别的应用才会 pause/stop，正好是需要的语义。
     */
    private static boolean isForeground(Object activity) {
        if (activity == null) return false;
        try {
            if (XposedHelpers.getBooleanField(activity, "mStopped")) return false;
            return XposedHelpers.getBooleanField(activity, "mResumed");
        } catch (Throwable error) {
            return false;
        }
    }

    private static int canvasCount(Object activity) {
        try {
            Object view = XposedHelpers.callMethod(activity, "x0");
            if (view == null) return -1;
            Object adapter = null;
            for (java.lang.reflect.Field field : view.getClass().getDeclaredFields()) {
                if (field.getType().getName().equals("u1.c")) {
                    field.setAccessible(true);
                    adapter = field.get(view);
                    break;
                }
            }
            if (adapter == null) return -1;
            Object value = XposedHelpers.callMethod(adapter, "getCount");
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable error) {
            return -1;
        }
    }

    private static Object currentCanvas() {
        try {
            Object thread = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentActivityThread");
            Object records = XposedHelpers.getObjectField(thread, "mActivities");
            if (!(records instanceof java.util.Map)) return null;
            Object fallback = null;
            for (Object record : ((java.util.Map<?, ?>) records).values()) {
                Object activity = XposedHelpers.getObjectField(record, "activity");
                if (activity == null) continue;
                if (!activity.getClass().getName().equals(
                        "com.oplus.pscanvas.canvasmode.canvas.ContainerActivity")) {
                    continue;
                }
                try {
                    if (!XposedHelpers.getBooleanField(record, "paused")) {
                        return activity;
                    }
                } catch (Throwable ignored) {
                }
                if (fallback == null) fallback = activity;
            }
            return fallback;
        } catch (Throwable error) {
            log("current canvas failed: " + error);
        }
        return null;
    }

    private static void startPocketStudio(Context context, ClassLoader loader, String[] packages) {
        try {
            Object manager = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(MANAGER, loader), "getInstance");
            if (manager == null) return;
            List<Intent> intents = new ArrayList<>();
            for (String pkg : packages) {
                // 回滚到原始版本：一律用普通的 launch intent，不构造伪桌面 intent。
                Intent intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
                if (intent == null) {
                    log("no launch intent for " + pkg + ", skipped");
                    continue;
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intents.add(intent);
            }
            int[] taskIds = new int[intents.size()];
            Arrays.fill(taskIds, -1);
            Bundle extra = new Bundle();
            extra.putBoolean("androidx.canvas.to.multi.flexible", true);
            XposedHelpers.callMethod(manager, "startPocketStudio",
                    context, intents, taskIds, new int[intents.size()], 0, extra);
            log("canvas requested, intents=" + intents.size());
        } catch (Throwable error) {
            log("canvas request failed: " + error);
        }
    }

    /**
     * 把最近任务里已有的实例逐个转成灵活窗口（上游原版设计，2026-11-20 起停用）。
     *
     * 调用点见 onReceive 末尾的注释块：同步调用必然拿不到 taskId（entered=0 空转），
     * 延迟调用又会在触发后 0.9~2.3s 把 task 拽回前台，且 startPocketStudio 自己就能
     * 开三窗，所以现在不调用。保留实现是为了以后要恢复「沿用上次页面」时能一行恢复。
     *
     * 已知行为（20:51:54 实测）：对已经在画布里的 task 是幂等的，entered=3 之后画布
     * 仍然是 count=3，不会多开窗口。
     *
     * @return 没找到 task 的包；null 表示整体失败（管理器不可用/无 recents）。
     */
    private static String[] enterWorkspace(ClassLoader loader, String[] packages) {
        try {
            Object manager = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(MANAGER, loader), "getInstance");
            if (manager == null) {
                log("flexible manager unavailable");
                return null;
            }
            java.util.HashMap<String, Integer> tasks = recentTasks(loader);
            if (tasks.isEmpty()) {
                log("no recent tasks visible");
                return null;
            }
            android.util.DisplayMetrics metrics = android.content.res.Resources
                    .getSystem().getDisplayMetrics();
            android.graphics.Rect full = new android.graphics.Rect(
                    0, 0, metrics.widthPixels, metrics.heightPixels);
            java.util.ArrayList<String> missing = new java.util.ArrayList<>();
            int entered = 0;
            for (String pkg : packages) {
                Integer taskId = tasks.get(pkg);
                if (taskId == null) {
                    log("no task for " + pkg + ", known=" + tasks.keySet());
                    missing.add(pkg);
                    continue;
                }
                try {
                    Object result = XposedHelpers.callMethod(
                            manager, "startFlexibleWindowForRecentTasks", taskId, full);
                    log("enter " + pkg + " task=" + taskId + " " + result);
                    entered++;
                } catch (Throwable error) {
                    log("enter failed " + pkg + ": " + error);
                }
            }
            log("workspace entered=" + entered + " missing=" + missing);
            return missing.toArray(new String[0]);
        } catch (Throwable error) {
            log("workspace failed: " + error);
            return null;
        }
    }

    /**
     * 组成写死的三屏列表：左窗 + 翻译 + 计算器。
     *
     * 左窗按 MRU 取触发前的前台应用；如果它被 pickPackage 排除（黑名单、或本身
     * 就是翻译/计算器），就继续往 recents 后面找下一个可用的——这就是用户说的
     * 「前台获取不了就取多任务」。找不到就只给两窗（翻译+计算器），不会为了凑数
     * 重复塞同一个包，因为同一个包开两个窗口会把第三个挤掉（2026-11-20 实测
     * trigger [translate, translate, calculator]）。
     */
    private static String[] fixedPackages(ClassLoader loader) {
        String left = pickPackage(loader, FIXED_TAIL);
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (left != null) {
            out.add(left);
        } else {
            log("left slot empty (foreground & recents all blocked), two windows only");
        }
        // 顺序完全由 FIXED_TAIL 决定，避免这里和常量两处写反。
        for (String pkg : FIXED_TAIL) {
            out.add(pkg);
        }
        return out.toArray(new String[0]);
    }

    /**
     * 读当前前台应用包名：取 ATMS task 列表第一个「不在黑名单、不在 exclude 里」的包名。
     * 黑名单必须含画布(pscanvas)和桌面(launcher)/SystemUI——画布启动后自身就是
     * 最上面的 task，不排掉会把它当成前台应用塞进侧窗（2026-11-20 踩过）。
     * 用 getTasks/getRecentTasks 的 MRU 顺序，第一个即前台；topActivity 可能为 null
     * 时用 baseIntent 兜底。
     *
     * attempts 里最后一组带 displayId=0 的四参版本（Android 13+）是必须的：
     * filterOnlyVisibleRecents=false 能拿到不可见的 recents，否则像 21:05:11 那次
     * candidates 里只剩 [com.android.launcher]，前台应用根本取不到。
     */
    private static String pickPackage(ClassLoader loader, String[] exclude) {
        java.util.List<String> order = new java.util.ArrayList<>();
        try {
            Object atms = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityTaskManager", loader),
                    "getService");
            Object value = null;
            Object[][] attempts = new Object[][]{
                    {Integer.MAX_VALUE, 0},
                    {Integer.MAX_VALUE, 0, 0},
                    {Integer.MAX_VALUE, false, false},
                    {Integer.MAX_VALUE, false, false, 0}
            };
            for (Object[] args : attempts) {
                try {
                    value = XposedHelpers.callMethod(atms, "getRecentTasks", args);
                    if (value instanceof java.util.List) break;
                } catch (Throwable ignored) {
                }
                try {
                    value = XposedHelpers.callMethod(atms, "getTasks", args);
                    if (value instanceof java.util.List) break;
                } catch (Throwable ignored) {
                }
            }
            if (value instanceof java.util.List) {
                for (Object info : (java.util.List<?>) value) {
                    String pkg = null;
                    try {
                        Object top = XposedHelpers.getObjectField(info, "topActivity");
                        if (top instanceof android.content.ComponentName) {
                            pkg = ((android.content.ComponentName) top).getPackageName();
                        }
                    } catch (Throwable ignored) {
                    }
                    if (pkg == null) {
                        Object intent = XposedHelpers.getObjectField(info, "baseIntent");
                        if (intent instanceof Intent) pkg = ((Intent) intent).getPackage();
                    }
                    if (pkg != null && !pkg.isEmpty()) order.add(pkg);
                }
            }
        } catch (Throwable error) {
            log("foreground task scan failed: " + error);
        }
        log("foreground candidates=" + order);
        for (String pkg : order) {
            if (blacklisted(pkg)) continue;
            if (contains(exclude, pkg)) continue;
            return pkg;
        }
        return null;
    }

    private static boolean blacklisted(String pkg) {
        return contains(BLOCKED, pkg);
    }

    private static boolean contains(String[] list, String value) {
        if (list == null) return false;
        for (String item : list) {
            if (item.equals(value)) return true;
        }
        return false;
    }

    private static java.util.HashMap<String, Integer> recentTasks(ClassLoader loader) {
        java.util.HashMap<String, Integer> out = new java.util.HashMap<>();
        try {
            Object atms = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityTaskManager", loader),
                    "getService");
            Object value = null;
            Object[][] attempts = new Object[][]{
                    {Integer.MAX_VALUE, 0},
                    {Integer.MAX_VALUE, 0, 0},
                    {Integer.MAX_VALUE, false, false},
                    {Integer.MAX_VALUE, false, false, 0}
            };
            for (Object[] args : attempts) {
                try {
                    value = XposedHelpers.callMethod(atms, "getRecentTasks", args);
                    if (value instanceof java.util.List) break;
                } catch (Throwable ignored) {
                }
                try {
                    value = XposedHelpers.callMethod(atms, "getTasks", args);
                    if (value instanceof java.util.List) break;
                } catch (Throwable ignored) {
                }
            }
            if (!(value instanceof java.util.List)) return out;
            for (Object info : (java.util.List<?>) value) {
                Object intent = XposedHelpers.getObjectField(info, "baseIntent");
                if (!(intent instanceof Intent)) continue;
                String pkg = ((Intent) intent).getPackage();
                if (pkg == null) continue;
                Object id = XposedHelpers.getObjectField(info, "taskId");
                if (id instanceof Integer) out.put(pkg, (Integer) id);
            }
        } catch (Throwable error) {
            log("recent tasks failed: " + error);
        }
        return out;
    }

    private static void log(String message) {
        ModuleEntry.log(message);
    }
}
