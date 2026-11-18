package com.canvasplus;


import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 打开 OPPO 已有的 Pocket Studio / Panorama 功能。
 *
 * 直板机上这套功能被三个判断挡住：
 *   1. OplusFeatureConfigManager.hasFeature("oplus.software.pocketstudio.support")
 *   2. OplusFeatureConfigManager.hasFeature("oplus.software.wms.panorama_work_station")
 *   3. OplusPanoramaWorkBranchUtil.hasTabletPanoramaWorkFeature() / hasFoldPanoramaWorkFeature()
 *
 * 这里只改判断结果，不改实现。布局、动画、输入仍然是 OPPO 自己的。
 */
final class FeatureUnlock {

    private static final String TAG = "CanvasPlus";
    private static final String[] UNLOCKED_FEATURES = {
            "oplus.software.pocketstudio.support",
            "oplus.software.wms.panorama_work_station",
            "oplus.software.flexible.subdisplay.support"
    };

    private FeatureUnlock() {}

    static void installSystem(ClassLoader loader) {
        unlockFeatureManager(loader);
        unlockPanoramaUtil(loader);
        unlockFlexibleWindowManager(loader);
        unlockFlexibleWindowUtils(loader);
        fixInitializationOrder(loader);
        unlockMaxWinNum(loader);
        unlockCanvasLayout(loader);
        unlockTaskLayoutPolicy(loader);
    }

    /** Launcher 里决定最近任务长按「分屏」走哪套。 */
    static void installLauncher(ClassLoader loader) {
        unlockLauncherEntry(loader);
    }

    /**
     * 最近任务长按「分屏」走哪套，由 PocketStudioLocal.isDeviceStateSupport 决定。
     * 它的静态 hasFeature 在类加载时算好，Launcher 启动早于 hook 就是 false。
     */
    private static void unlockLauncherEntry(ClassLoader loader) {
        try {
            Class<?> local = XposedHelpers.findClass(
                    "com.oplus.quickstep.multiwindow.pocketstudio.PocketStudioLocal", loader);
            for (String name : new String[]{"isDeviceStateSupport", "isFullDeviceSupportPS"}) {
                XposedBridge.hookAllMethods(local, name, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(Boolean.TRUE);
                    }
                });
            }
            log("launcher pocket studio entry unlocked");
        } catch (Throwable error) {
            log("launcher entry unavailable: " + error);
        }
    }

    /** 手机上 getMaxWinNum 返回 2，画布至少要 4 个位置。 */
    private static void unlockMaxWinNum(ClassLoader loader) {
        try {
            Class<?> type = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleWindowManagerService", loader);
            XposedBridge.hookAllMethods(type, "getMaxWinNum", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (result instanceof Integer && (Integer) result < 4) {
                        param.setResult(4);
                    }
                }
            });
            log("max win num raised to 4");
        } catch (Throwable error) {
            log("max win num unavailable: " + error);
        }
    }

    /**
     * FlexibleWindowManagerService 在 systemReady 时读一次 feature。
     * 如果那次读取早于本模块加载，mHasPocketStudioFeature 会是 false，
     * onSystemReadyEnd 里的 initialize() 就会被跳过，Panorama controller 为 null。
     * 这里在 systemReady / onSystemReadyEnd 两个时机纠正，并补一次初始化。
     */
    private static void fixInitializationOrder(ClassLoader loader) {
        try {
            Class<?> service = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleWindowManagerService", loader);
            XposedBridge.hookAllMethods(service, "systemReady", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    boolean has = XposedHelpers.getBooleanField(
                            param.thisObject, "mHasPocketStudioFeature");
                    log("FWMS.systemReady hasPocket=" + has);
                    if (!has) {
                        XposedHelpers.setBooleanField(
                                param.thisObject, "mHasPocketStudioFeature", true);
                        log("FWMS hasPocket forced true");
                    }
                }
            });
            XposedBridge.hookAllMethods(service, "onSystemReadyEnd", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    XposedHelpers.setBooleanField(
                            param.thisObject, "mHasPocketStudioFeature", true);
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    log("FWMS.onSystemReadyEnd reached");
                    try {
                        Class<?> controller = XposedHelpers.findClass(
                                "com.android.server.wm.OplusPanoramaWorkBranchController", loader);
                        Object instance = XposedHelpers.callStaticMethod(controller, "getInstance");
                        Object current = XposedHelpers.getObjectField(instance, "mController");
                        if (current != null) {
                            log("Panorama controller ready: " + current.getClass().getSimpleName());
                            return;
                        }
                        Object atms = XposedHelpers.getObjectField(param.thisObject, "mAtms");
                        XposedHelpers.callMethod(instance, "onSystemReady", atms);
                        Object after = XposedHelpers.getObjectField(instance, "mController");
                        log("Panorama manual init -> " + (after == null
                                ? "still null" : after.getClass().getSimpleName()));
                    } catch (Throwable error) {
                        log("Panorama init check failed: " + error);
                    }
                }
            });
            log("FWMS initialization hooks installed");
        } catch (Throwable error) {
            log("FWMS hooks unavailable: " + error);
        }
    }

    private static void unlockFeatureManager(ClassLoader loader) {
        try {
            Class<?> type = XposedHelpers.findClass(
                    "com.oplus.content.OplusFeatureConfigManager", loader);
            XposedBridge.hookAllMethods(type, "hasFeature", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length != 1 || !(param.args[0] instanceof String)) return;
                    String name = (String) param.args[0];
                    for (String target : UNLOCKED_FEATURES) {
                        if (target.equals(name)) {
                            param.setResult(Boolean.TRUE);
                            log("feature forced true: " + name);
                            return;
                        }
                    }
                }
            });
            log("feature manager hooked");
        } catch (Throwable error) {
            log("feature manager unavailable: " + error);
        }
    }

    private static void unlockPanoramaUtil(ClassLoader loader) {
        try {
            Class<?> type = XposedHelpers.findClass(
                    "com.android.server.wm.OplusPanoramaWorkBranchUtil", loader);
            // 手机走非折叠的 panorama 分支，getMaxWinNum 才会给 4，
            // 四窗布局也才会由系统算。
            hookAll(type, new String[]{
                    "hasTabletPanoramaWorkFeature", "hasPanoramaWorkFeature",
                    "isPanoramaWorkBranchEnable",
                    "isPanoramaWorkBranchExcludeSmallDevice"}, Boolean.TRUE);
            hookAll(type, new String[]{
                    "hasFoldPanoramaWorkFeature", "isFoldPanoramaWorkEnable",
                    "isPanoramaWorkBranchForSmallDevice"}, Boolean.FALSE);
            XposedBridge.hookAllMethods(type, "isPanoramaForcedDisable",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.getResult() instanceof Boolean) {
                                param.setResult(Boolean.FALSE);
                            }
                        }
                    });
            forceStaticBoolean(type, "SUPPORT_PANORAMA_WORKSTATION");
            forceStaticBoolean(type, "SUPPORT_TABLET_FEATURE");
            log("panorama util hooked");
        } catch (Throwable error) {
            log("panorama util unavailable: " + error);
        }
    }

    private static void hookAll(Class<?> type, String[] names, Boolean value) {
        for (String name : names) {
            XposedBridge.hookAllMethods(type, name, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getResult() instanceof Boolean) {
                        param.setResult(value);
                    }
                }
            });
        }
    }

    /** 系统侧的画布布局在 CanvasCalculateLayoutRect 里，默认按手机给，改成平板。 */
    private static void unlockCanvasLayout(ClassLoader loader) {
        try {
            Class<?> type = XposedHelpers.findClass(
                    "com.oplus.flexiblewindow.CanvasCalculateLayoutRect", loader);
            hookAll(type, new String[]{"isTabletDevice"}, Boolean.TRUE);
            hookAll(type, new String[]{"isPhoneDisplay"}, Boolean.FALSE);
            log("canvas layout -> tablet");
        } catch (Throwable error) {
            log("canvas layout unavailable: " + error);
        }
    }

    /** 系统里给画布选布局策略，默认是手机那套，换成平板的。 */
    private static void unlockTaskLayoutPolicy(ClassLoader loader) {
        try {
            Class<?> type = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleWindowManagerService", loader);
            XposedBridge.hookAllMethods(type, "getTaskLayoutPolicy",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                java.lang.reflect.Field field =
                                        param.thisObject.getClass()
                                                .getDeclaredField("mTabletCanvasLayoutPolicy");
                                field.setAccessible(true);
                                Object value = field.get(param.thisObject);
                                if (value != null) param.setResult(value);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            log("task layout policy -> tablet");
        } catch (Throwable error) {
            log("task layout policy unavailable: " + error);
        }
    }

    private static void unlockFlexibleWindowManager(ClassLoader loader) {
        try {
            Class<?> type = XposedHelpers.findClass(
                    "com.oplus.flexiblewindow.FlexibleWindowManager", loader);
            XposedBridge.hookAllMethods(type, "isSupportPocketStudio", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(Boolean.TRUE);
                }
            });
            log("flexible window manager hooked");
        } catch (Throwable error) {
            log("flexible window manager unavailable: " + error);
        }
    }

    private static void unlockFlexibleWindowUtils(ClassLoader loader) {
        try {
            Class<?> type = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleWindowUtils", loader);
            XposedBridge.hookAllMethods(type, "isSupportPocketStudio", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(Boolean.TRUE);
                }
            });
            log("flexible window utils hooked");
        } catch (Throwable error) {
            log("flexible window utils unavailable: " + error);
        }
    }

    private static void forceStaticBoolean(Class<?> type, String name) {
        try {
            XposedHelpers.setStaticBooleanField(type, name, true);
            log("static field forced: " + name);
        } catch (Throwable error) {
            log("static field " + name + " skipped: " + error);
        }
    }

    static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }
}
