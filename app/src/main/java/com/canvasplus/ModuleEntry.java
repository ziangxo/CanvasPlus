package com.canvasplus;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 多窗口工作台。
 *
 * ColorOS 的画布（Pocket Studio）本身支持多窗，手机上被策略限制成最多两个：
 * pscanvas 的 PhonePolicy 对三个及以上返回 0，第三个窗口被 layout 成 0x0。
 *
 * 模块做的事：
 *   1. 放开窗口数量判断，四窗接进官方的 w1 → N2 → M1 布局链路
 *   2. 布局、调节条、焦点缩放、关闭、浮窗全部走原实现，只补四窗缺的分支
 *   3. 智能侧边栏点应用时加进当前画布，满了（4 个）就还是开小窗
 *   4. 桌面快捷方式直接打开四窗
 *
 * 安全阀：/data/system/canvasplus-halt 存在时完全不加载。
 */
public final class ModuleEntry implements IXposedHookLoadPackage {

    private static final String HALT_MARKER = "/data/system/canvasplus-halt";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (param == null || param.packageName == null) return;
        // 安全阀统一在入口判断（对齐上游 v0.2.0）：以前只拦了 android 分支，
        // 画布侧和侧边栏侧照样加载。
        if (halted()) return;

        switch (param.packageName) {
            // 下面两个进程默认不在作用域里，留着是为了以后需要时手动勾选
            case "android":
                FeatureUnlock.installSystem(param.classLoader);
                Trigger.arm(param.classLoader);
                break;

            case "com.oplus.pscanvas":
                // 画布容器进程，布局问题的真正位置。
                // 2026-11-20：AutoReplace **不许改动焦点**（用户反复强调的红线）。
                // 不调 T()、不模拟点击，焦点完全交给画布初始布局。
                // 它只做一件事：三窗落地后，给当前焦点窗口自动弹一次替换面板
                // （container.q0(index).h.x1()），不碰二分屏。
                CanvasLayout.install(param.classLoader);
                AutoReplace.install(param.classLoader);
                // 只收发 ADD，不处理 START：START 由 system_server 处理。
                // 坑（2026-11-20 LSPosed 日志实测）：两个进程都处理 START 时，一条广播
                // 会拉起两轮画布（同一秒 system 和 pscanvas 各打一条
                // "canvas requested, intents=3"），recents 里累积了 9 个 canvas task。
                Trigger.arm(param.classLoader, false);
                break;

            case "com.coloros.smartsidebar":
                // 智能侧边栏：画布前台时点应用直接加进画布。
                SidebarEntry.install(param.classLoader);
                break;

            case "com.android.launcher":
                FeatureUnlock.installLauncher(param.classLoader);
                break;

            default:
                break;
        }
    }

    private static boolean halted() {
        try {
            return new java.io.File(HALT_MARKER).exists();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void log(String message) {
        XposedBridge.log("CanvasPlus: " + message);
    }
}
