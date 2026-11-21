package com.canvasplus;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 去掉画布容器启动时的白色 Splash（2026-11-21，尝试一）。
 *
 * 现象：触发三屏时有一个白色动画（白色遮罩）盖住前台程序，动画退出时闪一下
 * 才露出稳定三屏。日志（flash3.log 17:14:02）定位：
 *   02.137  Splash Screen com.oplus.pscanvas attach（白色启动窗口，全屏 1264x2780）
 *   02.664  Splash EXITING → destroySurface（reveal 动画退出）
 *   02.734  OPEN transition #73 finishTransition
 *   02.884  左侧大窗（前台程序 xiaoheihe/coolapk）才 HAS_DRAWN
 * 白色遮罩在 02.664 退场，但左侧大窗要到 02.884 才画完（慢 150~220ms，
 * 因为它要从全屏 resize 进槽位重新 relayout + 重绘），遮罩一撤露出还没画好的
 * 窗口 → 那帧闪白。白板1/2 画得快（02.728 就好），不是闪源。
 *
 * 本方案（尝试一：去掉白色遮罩）：
 * hook OplusStartingWindowManager.ignoreShowStartingWindow(...)：Z —— 这是
 * ColorOS 官方提供给 framework 的"要不要忽略 starting window"扩展点，framework
 * 的 ActivityRecord.showStartingWindow 会先问它。方法内读最后一个参数
 * startedActivity.packageName 判断（如 com.tencent.mm 特判），返回 true=忽略显示
 * Splash（dexdump 确认 return v3=1），返回 false=正常显示（return v4=0）。
 * 我们对画布容器 com.oplus.pscanvas 返回 true，让它不显示白色 Splash。
 *
 * 注意（别再踩）：
 * - 类是 com.android.server.wm.OplusStartingWindowManager（在 oplus-services.jar，
 *   system_server 进程），不是 shell 侧的 OplusShellStartingWindowManager。
 * - 是 system_server hook，装完必须用户在 LSPosed 里手动「重启系统框架」才生效。
 * - 风险提示：去掉遮罩后，大窗重绘那 150ms 可能露出底层（桌面/旧画面），
 *   若实测"闪"没消失甚至更明显，则本方案不成立，改用淡出（让遮罩 alpha 渐隐或
 *   延迟到所有窗口 HAS_DRAWN 再退），见下方注释与后续实现。
 */
final class CanvasSplash {

    private static final String CANVAS_PKG = "com.oplus.pscanvas";
    private static volatile boolean hooked = false;

    static void installSystem(ClassLoader loader) {
        if (hooked) return;
        synchronized (CanvasSplash.class) {
            if (hooked) return;
            try {
                Class<?> cls = XposedHelpers.findClass(
                        "com.android.server.wm.OplusStartingWindowManager", loader);
                XposedBridge.hookAllMethods(cls, "ignoreShowStartingWindow",
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    Object[] a = param.args;
                                    if (a == null || a.length == 0) return;
                                    // 最后一个参数是 startedActivity (ActivityRecord)
                                    Object started = a[a.length - 1];
                                    if (started == null) return;
                                    Object pkg = XposedHelpers.getObjectField(started, "packageName");
                                    if (!(pkg instanceof String)) return;
                                    if (!CANVAS_PKG.equals(pkg)) return;
                                    // true = 忽略显示 starting window（不画白色 Splash）
                                    param.setResult(true);
                                    ModuleEntry.log("canvas splash ignored (no white mask)");
                                } catch (Throwable ignored) {
                                }
                            }
                        });
                hooked = true;
                ModuleEntry.log("canvas splash hook installed on OplusStartingWindowManager");
            } catch (Throwable t) {
                ModuleEntry.log("canvas splash hook failed: " + t);
            }
        }
    }

    private CanvasSplash() {}
}
