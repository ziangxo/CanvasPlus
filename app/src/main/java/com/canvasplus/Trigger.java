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
 * 广播入口。
 *
 * START：
 *   adb shell am broadcast -a com.canvasplus.START \
 *       --esa packages com.tencent.mm,com.tencent.mobileqq,com.android.settings
 *   1. FlexibleWindowManager.startPocketStudio 拉起画布容器
 *   2. 逐个调 startFlexibleWindowForRecentTasks 把已有 task 变成灵活窗口
 *
 * ADD：把单个应用加进正在运行的画布，智能侧边栏走这条。
 * LAYOUT：切三窗布局方向。
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

    /**
     * 画布进程刚起来时 currentApplication() 不一定有值，之前固定等 2 秒，
     * 这段时间里侧边栏发来的 ADD 会直接丢掉（表现就是点好几下才有一次生效）。
     * 改成尽快尝试，没有上下文就退避重试。
     */
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

    /** 给画布发 AddIntentToSplit，让它自己走 addFlexibleTask 添加窗口。 */
    /** 侧边栏点应用时，只有一个问题：现在该加进画布，还是照旧开小窗。 */
    private static void addToCanvas(android.content.Context context, String pkg) {
        addToCanvas(context, pkg, 0);
    }
    /** 加完窗口后画布可能还在动画里没重排，补一次。 */
    private static void relayoutLater(final Object activity) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                XposedHelpers.callMethod(activity, "N2");
            } catch (Throwable ignored) {
            }
        }, 300L);
    }

    /**
     * 直接走 s0.d() 加窗口，绕开 ContainerActivity.j0() 里那些前置检查。
     * j0 里只要有一步对不上就静默返回，表现就是"点了没反应"。
     */
    private static boolean addDirect(Object activity, android.content.Context context,
                                     Intent launch) {
        try {
            Object view = XposedHelpers.callMethod(activity, "x0");
            if (view == null) return false;
            Object adapter = adapterOf(view);
            if (adapter == null) return false;
            // s0 内部会把 context 强转成 ContainerActivity，必须传 Activity 本身
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

    /**
     * 加窗口。j0() 依赖 getRectList() 和 adapter 完全同步，画布刚起来或者
     * 刚重排完时会对不上而静默返回。点一次内部退避重试几次，别让用户手点。
     */
    private static void addToCanvas(final android.content.Context context, final String pkg,
                                    final int attempt) {
        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) {
                log("no launch intent for " + pkg);
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // 画布已经在跑的时候，Intent 那条路进不了 addFlexibleTask，直接调
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

                // j0 里那串前置检查（a2/rectList 对齐）比较脆，直接用 s0.d() 加
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

    /** 当前画布里的窗口数量。 */
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

    /** 找出当前活着的画布 Activity。 */
    /** 找出当前活着的画布 Activity，多个画布时优先前台的。 */
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

    /** 拉起画布容器，让 pscanvas 进入可接收任务的状态。 */
    private static void startPocketStudio(Context context, ClassLoader loader, String[] packages) {
        try {
            Object manager = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(MANAGER, loader), "getInstance");
            if (manager == null) return;
            List<Intent> intents = new ArrayList<>();
            for (String pkg : packages) {
                Intent intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
                if (intent == null) continue;
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

    /** 把最近任务里已有的实例逐个转成灵活窗口。 */
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

            // 给全屏矩形，任务保持手机的原始比例，不做 resize。
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

    /** 用 ActivityTaskManager 服务查最近任务，system_server 和画布进程都能调。 */
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
