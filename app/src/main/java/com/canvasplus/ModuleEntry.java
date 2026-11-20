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

        switch (param.packageName) {
            // 下面两个进程默认不在作用域里，留着是为了以后需要时手动勾选
            case "android":
                if (halted()) return;
                FeatureUnlock.installSystem(param.classLoader);
                Trigger.arm(param.classLoader);
                break;

            case "com.oplus.pscanvas":
                // 画布容器进程，布局问题的真正位置。
                // 2026-11-20：移除 AutoReplace —— 不再用布局后 hook T() 模拟点击切焦点。
                // 坑：实测 packages 顺序不决定大窗槽位（计算器放第 1 位大窗是翻译，放第 2 位
                // 大窗又变成别的包），所以「计算器放第一个=大窗」是错误假设，已删除，禁止据此臆测。
                // 大窗/焦点由系统逻辑决定，如何让指定应用直接成为大窗仍需基于可工作的放大
                // 窗口模式（CanvasLayout.hookFourZoom）逆向验证，不能靠改 packages 排序碰运气。
                CanvasLayout.install(param.classLoader);
                Trigger.arm(param.classLoader);
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
