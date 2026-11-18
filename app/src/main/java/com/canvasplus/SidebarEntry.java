package com.canvasplus;

import android.content.Intent;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 智能侧边栏：画布在前台时，点应用不再开小窗，而是加进画布。
 *
 * 侧边栏点应用走 PageRoutUtils.startActivityAsZoomWindow(Intent, ...)，
 * 默认开 ZoomWindow。这里先判断画布是否在前台，是就从最近任务里找该应用的
 * taskId，调 FlexibleWindowManager.startFlexibleWindowForRecentTasks 加进画布，
 * 然后拦掉原来的小窗启动。
 */
final class SidebarEntry {

    private static final String TAG = "CanvasPlus";
    private static final String ROUTE =
            "com.oplus.smartsidebar.panelview.edgepanel.utils.PageRoutUtils";
    private static final String MANAGER =
            "com.oplus.flexiblewindow.FlexibleWindowManager";

    private SidebarEntry() {}

    /** 画布当前是否在前台（由画布进程广播维护）。 */
    private static volatile boolean canvasVisible = false;
    /** 画布窗口数，画布进程通过 CANVAS_STATE 广播同步过来。 */
    private static volatile int canvasCount = -1;
    /** 最近一次收到"画布可见"心跳的时间。 */
    private static volatile long lastVisibleTime = 0L;

    static void install(ClassLoader loader) {
        armReceiver(loader, 0);
        try {
            Class<?> route = XposedHelpers.findClass(ROUTE, loader);
            XposedBridge.hookAllMethods(route, "startActivityAsZoomWindow",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.args[0] instanceof Intent)) return;
                            Intent intent = (Intent) param.args[0];
                            String pkg = intent.getPackage();
                            if (pkg == null) {
                                pkg = intent.getComponent() == null
                                        ? null : intent.getComponent().getPackageName();
                            }
                            if (pkg == null) return;
                            if (!shouldAddToCanvas(loader)) return;

                            try {
                                Object app = XposedHelpers.callStaticMethod(
                                        XposedHelpers.findClass(
                                                "android.app.ActivityThread", loader),
                                        "currentApplication");
                                if (app instanceof android.content.Context) {
                                    Intent add = new Intent("com.canvasplus.ADD");
                                    add.setPackage("com.oplus.pscanvas");
                                    add.putExtra("pkg", pkg);
                                    ((android.content.Context) app).sendBroadcast(add);
                                    param.setResult(null);
                                    log("sidebar broadcast add " + pkg);
                                }
                            } catch (Throwable error) {
                                log("broadcast add failed: " + error);
                            }
                        }
                    });
            log("sidebar hooked");
        } catch (Throwable error) {
            log("sidebar hook failed: " + error);
        }
    }

    /** 侧边栏进程刚起来时拿不到 Context，尽快尝试并在失败时退避重试。 */
    private static void armReceiver(final ClassLoader loader, final int attempt) {
        long delay = attempt == 0 ? 100L : 300L;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (registerStateReceiver(loader)) return;
            if (attempt < 20) armReceiver(loader, attempt + 1);
        }, delay);
    }

    private static volatile boolean stateReceiverReady = false;

    private static boolean registerStateReceiver(ClassLoader loader) {
        if (stateReceiverReady) return true;
        try {
            Object ctx = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", loader),
                    "currentApplication");
            if (!(ctx instanceof android.content.Context)) {
                return false;
            }
            android.content.Context app = (android.content.Context) ctx;
            app.registerReceiver(new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context c, Intent intent) {
                    // 只认"可见"的心跳。侧边栏盖上来时画布会 pause，但 window
                    // 还在，画布会继续报 true；切到别的应用就只剩 false 了。
                    if (!intent.getBooleanExtra("visible", false)) return;
                    canvasVisible = true;
                    canvasCount = intent.getIntExtra("count", -1);
                    lastVisibleTime = android.os.SystemClock.elapsedRealtime();
                    log("canvas visible, count=" + canvasCount);
                }
            }, new android.content.IntentFilter("com.canvasplus.CANVAS_STATE"),
                    android.content.Context.RECEIVER_EXPORTED);
            stateReceiverReady = true;
            log("state receiver registered");
            return true;
        } catch (Throwable error) {
            log("state receiver failed: " + error);
            return false;
        }
    }

    /**
     * 侧边栏点应用只有两种结果：加进画布，或者照旧开小窗。
     * 判断就两条：
     *   1. 栈顶是不是画布（当前确实在分屏界面里）
     *   2. 画布的窗口数还没到 4
     * 其它情况一律不拦，让系统开小窗。
     */
    /**
     * 侧边栏点应用只有两种结果：加进画布，或者照旧开小窗。
     * 只看画布有没有发"可见"心跳，2.5 秒没收到就当作当前没有前台画布。
     */
    private static boolean shouldAddToCanvas(ClassLoader loader) {
        long age = android.os.SystemClock.elapsedRealtime() - lastVisibleTime;
        if (lastVisibleTime == 0L || age > 2500L) {
            log("sidebar: no visible canvas (age=" + age + "), keep zoom");
            return false;
        }
        if (canvasCount >= 4) {
            log("sidebar: canvas full " + canvasCount + ", keep zoom");
            return false;
        }
        return true;
    }

    /** 在最近任务里找这个包的任务。 */
    private static int findTaskId(ClassLoader loader, String pkg) {
        try {
            Object atms = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityTaskManager", loader),
                    "getService");
            Object tasks = null;
            for (Object[] args : new Object[][]{
                    {100, false, false}, {100, false}, {100}}) {
                try {
                    tasks = XposedHelpers.callMethod(atms, "getTasks", args);
                    if (tasks instanceof java.util.List) break;
                } catch (Throwable ignored) {
                }
            }
            if (!(tasks instanceof java.util.List)) return -1;
            for (Object info : (java.util.List<?>) tasks) {
                Object base = XposedHelpers.getObjectField(info, "baseIntent");
                if (!(base instanceof Intent)) continue;
                if (!pkg.equals(((Intent) base).getPackage())) continue;
                Object id = XposedHelpers.getObjectField(info, "taskId");
                if (id instanceof Integer) return (Integer) id;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }
}
