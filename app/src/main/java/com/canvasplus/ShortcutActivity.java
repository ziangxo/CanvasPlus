package com.canvasplus;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * 应用快捷方式 / 第三方调用的统一入口（2026-11-21）。
 *
 * 为什么需要它：
 *   1) 静态快捷方式（res/xml/shortcuts.xml）的 android:targetClass 必须是一个
 *      **本包内的组件**，而模块原来的入口全是「system_server 里动态注册的广播接收器」
 *      （Trigger）和配置页 ConfigActivity，没有一个"点一下就执行动作"的东西；
 *   2) 普通第三方 App 无法通过 LauncherApps.getShortcuts()/startShortcut() 读取或
 *      启动本应用的快捷方式——那两个 API 要 ACCESS_SHORTCUTS（signature 级）或桌面角色。
 *      它们能用的只有显式 intent，所以这里必须导出一个固定组件当锚点。
 *
 * 现在一个入口三种用法，都不需要打开任何界面：
 *   长按图标 →「三分屏」/「面板配置」/「复制三分屏命令」/「复制加应用命令」
 *   第三方  ：adb shell am start -n com.canvasplus/.ShortcutActivity -a com.canvasplus.SC_THREE
 *             （比直接发 com.canvasplus.START 更好用：不用猜 receiver 在哪个进程）
 *
 * 为什么本类只转成广播、不自己起画布：拉起画布要用 FlexibleWindowManager，
 * 需要 com.oplus.permission.safe.WINDOW 之类的系统权限，只有 system_server 里的
 * Trigger 有。普通 App 进程直接 startActivity 调 ContainerActivity 会被
 * SecurityException 拒（实测过）。
 *
 * 安全性：本 Activity 是 exported 的（第三方要能调），等于"任何 App 都能要求开三分屏"。
 * 这和现状一致——com.canvasplus.START 这个动态广播本来就是全局可发的。
 * 如果以后要收紧，加一个 signature 级自定义权限并在 manifest 上声明即可。
 */
public class ShortcutActivity extends Activity {

    /** 快捷方式动作：开三分屏（等价于系统框架里 Trigger 收到 com.canvasplus.START）。 */
    static final String SC_THREE = "com.canvasplus.SC_THREE";
    /** 快捷方式动作：把三分屏命令复制到剪贴板。 */
    static final String SC_COPY_THREE = "com.canvasplus.SC_COPY_THREE";
    /** 快捷方式动作：把"加应用到画布"命令复制到剪贴板。 */
    static final String SC_COPY_ADD = "com.canvasplus.SC_COPY_ADD";

    // 可调用命令的唯一真值来源：快捷方式复制的内容、配置页「命令」清单里显示的文本，
    // 都引用这里，避免两处写不一样。
    static final String CMD_THREE =
            "am start -n com.canvasplus/.ShortcutActivity -a com.canvasplus.SC_THREE";
    static final String CMD_THREE_MODE =
            "am start -n com.canvasplus/.ShortcutActivity -a com.canvasplus.SC_THREE --ei mode 6";
    static final String CMD_START =
            "am broadcast -a com.canvasplus.START";
    static final String CMD_ADD =
            "am broadcast -a com.canvasplus.ADD --es pkg com.tencent.mm";
    static final String CMD_LAYOUT =
            "am broadcast -a com.canvasplus.LAYOUT --ei orientation 4";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handle(getIntent());
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
        finish();
    }

    private void handle(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (SC_THREE.equals(action)) {
            // 交给 system_server 里的 Trigger（它持有系统权限，能真正起画布）。
            // 注意不要 setPackage：Trigger 的 receiver 是动态注册的，
            // 它的包名是 "android"，按包名定向反而可能发不到。
            Intent start = new Intent("com.canvasplus.START");
            start.putExtra("mode", intent.getIntExtra("mode", 4));
            sendBroadcast(start);
        } else if (SC_COPY_THREE.equals(action)) {
            copy("三分屏命令", CMD_THREE);
        } else if (SC_COPY_ADD.equals(action)) {
            copy("加应用命令", CMD_ADD);
        }
    }

    /**
     * 复制到剪贴板并提示。
     *
     * 坑：Android 10+ 只有在持焦点的窗口里才能**读**剪贴板；写不受限，这里只写。
     * 本 Activity 是 Translucent 且马上 finish，Toast 用 ApplicationContext 发，
     * 避免 Activity 销毁后 Toast 被取消。
     */
    private void copy(String what, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("CanvasPlus " + what, text));
                Toast.makeText(getApplicationContext(),
                        "已复制" + what + "：" + text, Toast.LENGTH_LONG).show();
                return;
            }
        } catch (Throwable ignored) {
        }
        Toast.makeText(getApplicationContext(), "复制失败", Toast.LENGTH_SHORT).show();
    }
}
