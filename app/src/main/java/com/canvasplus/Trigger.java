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
 * START：把广播传入的包名原样交给 startPocketStudio 拉起画布。
 * 曾尝试改写槽位放伪桌面，但第三方 Activity 作为 embedded 窗口启动会被
 * ColorOS 的 atms_intercept 用 -92 拒绝（普通包名也一样），已回滚到原始逻辑。
 *
 * ===== 三屏 + 下窗焦点展开的触发方法（2026-11-20 实测确认，勿删） =====
 * 命令：
 *   am broadcast -a com.canvasplus.START \
 *       --esa packages <上窗包>,<下窗包> \
 *       --ez foreground true --ei mode 4
 * - foreground=true：第一个包由 foregroundPackage() 读当前前台应用（黑名单滤掉
 *   画布/桌面/SystemUI），落到左侧窄窗。不要手写包名（酷安/Via 都是死配置）。
 * - packages[1] → 上窗（小窗），packages[2] → 下窗（一触发就是焦点展开态）。
 *   2026-11-20 实测：传 [计算器,翻译] 时下窗是翻译且展开；对调成 [翻译,计算器]
 *   即可让计算器在下窗展开。目标不是「某个应用变大」，而是「下窗位置一触发
 *   就展开」，装谁无所谓。
 * - mode=4 交给 CanvasLayout 的三/四窗布局链路。
 * 坑：force-stop pscanvas 后必须等 receiver 重新注册（日志出现 trigger armed）
 * 再发广播，否则广播没人收、三窗起不来。
 */
final class Trigger {

    private static final String ACTION_START = "com.canvasplus.START";
    private static final String ACTION_ADD = "com.canvasplus.ADD";
    private static final String ACTION_LAYOUT = "com.canvasplus.LAYOUT";
    private static final String EXTRA_PACKAGES = "packages";
    private static final String MANAGER = "com.oplus.flexiblewindow.FlexibleWindowManager";
    private static final String SERVICE = "com.android.server.wm.FlexibleWindowManagerService";

    private Trigger() {}

    static void arm(ClassLoader loader) {
        arm(loader, 0);
    }

    private static void arm(final ClassLoader loader, final int attempt) {
        long delay = attempt == 0 ? 100L : 300L;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (register(loader)) return;
            if (attempt < 20) arm(loader, attempt + 1);
        }, delay);
    }

    private static boolean armed = false;

    private static IntentFilter buildFilter() {
        IntentFilter filter = new IntentFilter(ACTION_START);
        filter.addAction(ACTION_ADD);
        filter.addAction(ACTION_LAYOUT);
        return filter;
    }

    private static boolean register(ClassLoader loader) {
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
                    if (ACTION_LAYOUT.equals(intent.getAction())) {
                        CanvasLayout.applyOrientation(intent.getIntExtra("orientation", 0));
                        return;
                    }
                    if (ACTION_ADD.equals(intent.getAction())) {
                        String pkg = intent.getStringExtra("pkg");
                        if (pkg == null || pkg.isEmpty()) return;
                        log("add " + pkg);
                        addToCanvas(context, pkg);
                        return;
                    }
                    if (!ACTION_START.equals(intent.getAction())) return;
                    String[] packages = intent.getStringArrayExtra(EXTRA_PACKAGES);
                    if (packages == null || packages.length == 0) return;
                    CanvasLayout.forcedMode = intent.getIntExtra("mode", 0);

                    // 2026-11-20 用户要求：侧窗（packages 第一个）用「触发前的前台应用」，
                    // 不再写死酷安。广播带 foreground=true 时读取当前前台 task 的包名，
                    // 用黑名单滤掉画布/桌面/SystemUI 后取最上面的一个，插到第一位。
                    // 坑：画布起来后它自己会成为前台 task，所以必须靠黑名单把它排除，
                    // 否则取到的是画布自己。另外两个包（Via/计算器）保持广播传入的值。
                    if (intent.getBooleanExtra("foreground", false)) {
                        String fg = foregroundPackage(loader);
                        if (fg != null) {
                            String[] withFg = new String[packages.length + 1];
                            withFg[0] = fg;
                            System.arraycopy(packages, 0, withFg, 1, packages.length);
                            packages = withFg;
                            log("foreground slot -> " + fg);
                        } else {
                            log("foreground package not found (all blacklisted?)");
                        }
                    }
                    log("trigger " + Arrays.toString(packages) + " mode=" + CanvasLayout.forcedMode);
                    startPocketStudio(context, loader, packages);
                    enterWorkspace(loader, packages);
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

    private static void enterWorkspace(ClassLoader loader, String[] packages) {
        try {
            Object manager = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(MANAGER, loader), "getInstance");
            if (manager == null) {
                log("flexible manager unavailable");
                return;
            }
            java.util.HashMap<String, Integer> tasks = recentTasks(loader);
            if (tasks.isEmpty()) {
                log("no recent tasks visible");
                return;
            }
            android.util.DisplayMetrics metrics = android.content.res.Resources
                    .getSystem().getDisplayMetrics();
            android.graphics.Rect full = new android.graphics.Rect(
                    0, 0, metrics.widthPixels, metrics.heightPixels);
            int entered = 0;
            for (String pkg : packages) {
                Integer taskId = tasks.get(pkg);
                if (taskId == null) {
                    log("no task for " + pkg + ", known=" + tasks.keySet());
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
            log("workspace entered=" + entered);
        } catch (Throwable error) {
            log("workspace failed: " + error);
        }
    }

    /**
     * 读当前前台应用包名：取 ATMS task 列表第一个「不在黑名单」的包名。
     * 黑名单必须含画布(pscanvas)和桌面(launcher)/SystemUI——画布启动后自身就是
     * 最上面的 task，不排掉会把它当成前台应用塞进侧窗（2026-11-20 踩过）。
     * 用 getTasks/getRecentTasks 的 MRU 顺序，第一个即前台；topActivity 可能为 null
     * 时用 baseIntent 兜底。
     */
    private static String foregroundPackage(ClassLoader loader) {
        java.util.List<String> order = new java.util.ArrayList<>();
        try {
            Object atms = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityTaskManager", loader),
                    "getService");
            Object value = null;
            Object[][] attempts = new Object[][]{
                    {Integer.MAX_VALUE, 0},
                    {Integer.MAX_VALUE, 0, 0},
                    {Integer.MAX_VALUE, false, false}
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
            return pkg;
        }
        return null;
    }

    private static boolean blacklisted(String pkg) {
        return pkg.equals("com.oplus.pscanvas")
                || pkg.equals("com.android.launcher")
                || pkg.equals("com.android.systemui");
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
                    {Integer.MAX_VALUE, false, false}
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
