package com.canvasplus;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 消除三屏启动"白色遮罩退出闪一下"——淡出/延迟方案（2026-11-21）。
 *
 * 根因（flash3.log 17:14:02，flash_analyze.log 16:37:42 两次复现）：
 *   02.137  画布容器 Splash attach（白色遮罩，全屏 1264x2780）
 *   02.457  OplusShellStartingWindowManager.removeWindow playRevealAnimation=true
 *   02.664  Splash EXITING → destroySurface（白色遮罩退出）
 *   02.734  OPEN transition finishTransition
 *   02.884  左侧大窗（前台程序）才 HAS_DRAWN —— 比遮罩退出晚 220ms
 *   遮罩一撤、大窗还没画完 → 露底闪一下。白板1/2 画得快（02.728），不是闪源。
 *
 * 已排除的方案（别再试）：
 * - 去掉白色遮罩（hook OplusStartingWindowManager.ignoreShowStartingWindow 返回 true）：
 *   实测白闪变黑闪（失去遮罩后露出底层），遮罩有用，已停用（见 CanvasSplash.java）。
 * - hook StartingSurfaceRemoveRunnable.run() 做 alpha 渐隐：画布 Splash 走的是 shell 侧
 *   reveal 即时移除，不走这个延迟 Runnable，hook 它不命中。
 * - 复用"替换应用三窗收缩"的 G0=true：那是画布进程 c.n 路径，闪屏是 system_server 的
 *   starting window/transition，进程/时机/机制都不同，日志里 G0 全程没命中。
 *
 * 本方案（系统原生延迟移除，风险低、确定命中）：
 * OplusStartingWindowManager.interceptRemoveStartingWindow(...) 内部调用
 *   StartingWindowUtils.isRemoveStartingWindowDelayedForWhiteList(String, String):Z
 * 命中返回 true → 走 postDelayed(mRemoveRunnable, 1000ms) —— 系统本来就有"让 Splash
 * 多留一会儿等 app 画完第一帧"的机制（日志 "interceptRemoveStartingWindow: delay removing"）。
 * 画布现在没命中白名单（Splash 只活 527ms 就撤），所以卡在大窗画完前。
 * 我们让画布容器 com.oplus.pscanvas 命中这个白名单 → 遮罩多停留 ~1s 盖住大窗重绘，
 * 移除时三屏已画好 → 不再闪。系统原生路径，不改 transition、不动所有 app 启动。
 *
 * 注意：
 * - 类是 com.android.server.wm.startingwindow.StartingWindowUtils（在 oplus-services.jar，
 *   system_server 进程）；hook 的是静态工具方法，参数含包名。
 * - 是 system_server hook，装完必须用户在 LSPosed 里手动「重启系统框架」才生效。
 * - 代价：白色遮罩会多停留约 1 秒（系统固定 1000ms 延迟），观感像稍长的转场。
 *   若觉得太久，后续再想办法把 delay 调短（需 hook interceptRemoveStartingWindow 的
 *   postDelayed 延迟值，风险更高）。
 */
final class CanvasSplashFade {

    private static final String CANVAS_PKG = "com.oplus.pscanvas";
    private static volatile boolean hooked = false;

    static void installSystem(ClassLoader loader) {
        if (hooked) return;
        synchronized (CanvasSplashFade.class) {
            if (hooked) return;
            try {
                Class<?> utils = XposedHelpers.findClass(
                        "com.android.server.wm.startingwindow.StartingWindowUtils", loader);
                XposedBridge.hookAllMethods(utils,
                        "isRemoveStartingWindowDelayedForWhiteList", new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    Object[] a = param.args;
                                    if (a == null) return;
                                    // 该方法两个参数都是包名字符串，任一命中即可（不纠结顺序）
                                    for (Object arg : a) {
                                        if (CANVAS_PKG.equals(arg)) {
                                            param.setResult(true);
                                            ModuleEntry.log("canvas splash delayed-remove ON");
                                            return;
                                        }
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        });
                hooked = true;
                ModuleEntry.log("canvas splash fade hook installed on StartingWindowUtils");
            } catch (Throwable t) {
                ModuleEntry.log("canvas splash fade hook failed: " + t);
            }
        }
    }

    private CanvasSplashFade() {}
}
