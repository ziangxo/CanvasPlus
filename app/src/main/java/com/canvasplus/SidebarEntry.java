package com.canvasplus;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 智能侧边栏：画布在前台时，点应用加进画布，否则照旧开小窗。
 *
 * 侧边栏点应用走 PageRoutUtils.startActivityAsZoomWindow(Intent, ...)。这里拦下来，
 * 发一条有序广播问画布进程"能不能接一个新窗口"。画布自己看 Activity 是否可见、
 * 窗口数是否到 4，用 resultCode 回答：
 *
 *   1 → 画布接了，随后异步加窗口
 *   0 → 画布不在前台、已经满了、或者进程没在，回退到原来的小窗
 *
 * 判断交给画布自己做，侧边栏不用跨进程保存任何状态。
 *
 * 为什么不再用心跳（v0.1.0 的做法）：心跳是"画布每秒广播一次我可见/我有几个窗口"，
 * 侧边栏只记 2.5 秒内收到的最新值。这套在本机有两个坑：
 *   1) 判断分散在两个进程，侧边栏拿到的是几秒前的旧状态，点得快就误判；
 *   2) 画布的 isVisible()/isShown() 在侧边栏盖上来时读数不稳定，曾长期读成 false，
 *      表现为"加第三个应用永远失败"，只能靠 watchdog 每秒刷一次来掩盖。
 * 现在侧边栏问、画布当场答（看 Activity 的 mResumed/mStopped + 窗口数），
 * 不依赖任何跨进程缓存，也不会被心跳时间窗卡住。
 * 同时画布侧那条 CANVAS_STATE 每秒广播可以整个删掉，省掉每秒两条跨进程广播。
 */
final class SidebarEntry {

    private static final String TAG = "CanvasPlus";
    private static final String ROUTE =
            "com.oplus.smartsidebar.panelview.edgepanel.utils.PageRoutUtils";
    private static final String CANVAS = "com.oplus.pscanvas";
    private static final String ACTION_ADD = "com.canvasplus.ADD";

    /** 兜底重放开小窗时，别让 hook 再拦一次。 */
    private static final ThreadLocal<Boolean> replaying = new ThreadLocal<>();

    private SidebarEntry() {}

    static void install(final ClassLoader loader) {
        try {
            Class<?> route = XposedHelpers.findClass(ROUTE, loader);
            XposedBridge.hookAllMethods(route, "startActivityAsZoomWindow",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (Boolean.TRUE.equals(replaying.get())) return;
                            if (!(param.args[0] instanceof Intent)) return;
                            String pkg = packageOf((Intent) param.args[0]);
                            if (pkg == null) return;

                            param.setResult(null);
                            Context ctx = application(loader);
                            if (ctx == null) {
                                replay(param);
                                return;
                            }
                            ask(ctx, pkg, param);
                        }
                    });
            log("sidebar hooked");
        } catch (Throwable error) {
            log("sidebar hook failed: " + error);
        }
    }

    /** 问画布能不能加，回执不是 1 就还回去开小窗。 */
    private static void ask(Context ctx, final String pkg,
                            final XC_MethodHook.MethodHookParam param) {
        Intent add = new Intent(ACTION_ADD);
        add.setPackage(CANVAS);
        add.putExtra("pkg", pkg);
        add.putExtra("ordered", true);
        try {
            ctx.sendOrderedBroadcast(add, null, new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent result) {
                    if (getResultCode() == 1) {
                        log("sidebar add " + pkg + " -> canvas");
                        return;
                    }
                    log("sidebar add " + pkg + " -> zoom fallback");
                    new Handler(Looper.getMainLooper()).post(() -> replay(param));
                }
            }, null, 0, null, null);
        } catch (Throwable error) {
            log("sidebar ask failed: " + error);
            replay(param);
        }
    }

    /**
     * 画布没接，按原参数再调一次，让系统开小窗。
     *
     * 坑（上游 v0.2.0 记录并已避开）：参数里有 null，
     * XposedHelpers.callMethod(method, thisObject, args) 会按参数**类型**去查方法，
     * 遇到 null 直接抛异常，小窗就开不出来了。这里用原生反射 invoke。
     */
    private static void replay(XC_MethodHook.MethodHookParam param) {
        replaying.set(Boolean.TRUE);
        try {
            ((java.lang.reflect.Method) param.method).invoke(param.thisObject, param.args);
        } catch (Throwable error) {
            log("zoom fallback failed: " + error);
        } finally {
            replaying.remove();
        }
    }

    private static String packageOf(Intent intent) {
        String pkg = intent.getPackage();
        if (pkg != null) return pkg;
        return intent.getComponent() == null
                ? null : intent.getComponent().getPackageName();
    }

    private static Context application(ClassLoader loader) {
        try {
            Object app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", loader),
                    "currentApplication");
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable error) {
            log("no application: " + error);
            return null;
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }
}
