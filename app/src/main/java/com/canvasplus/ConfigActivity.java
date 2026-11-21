package com.canvasplus;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 替换面板的「隐藏应用 / 手动排序」配置页（2026-11-20）。
 *
 * 数据流：
 *   本页 → Settings.Global "canvasplus_autoreplace"（{"hidden":[...],"order":[...]}）
 *        → pscanvas 进程里的 AutoReplace.cfgList() 读取 → 面板「全部应用」列表过滤/排序
 *
 * 为什么不写 App 私有 SharedPreferences：pscanvas 是不同 UID，读不到。
 * 写 Settings.Global 需要 WRITE_SECURE_SETTINGS（signature 级，普通安装拿不到），
 * 必须 adb grant 一次：
 *   adb shell pm grant com.canvasplus android.permission.WRITE_SECURE_SETTINGS
 * 万一没授权，退化成发广播给 system_server 里跑着的 Trigger，由它代写（它有系统权限）。
 *
 * 为什么不用 RecyclerView 做拖动排序：本项目是手工 javac + android.jar 构建，没有
 * androidx 依赖。这里用 ListView + 每行上下箭头，功能等价、零依赖、不会因为
 * ItemTouchHelper 缺类而崩（缺 androidx 类在 pscanvas/App 里都是 NoClassDefFoundError）。
 *
 * 坑（2026-11-20）：targetSdk 30+ 有包可见性过滤，AndroidManifest 里没写
 * <queries><intent MAIN/LAUNCHER></queries> 时 queryIntentActivities 只返回自己，
 * 配置页会变成空白列表。
 */
public class ConfigActivity extends Activity {

    /** 面板配置的 Settings.Global key，与 AutoReplace.CFG_KEY / Trigger 保持一致。 */
    private static final String CFG_KEY = "canvasplus_autoreplace";
    /** 走 system_server 代写的广播（Trigger 里注册）。 */
    private static final String ACTION_SET_CFG = "com.canvasplus.SET_REPLACE_CFG";
    /**
     * 三分屏启动后的焦点窗口，与 AutoReplace.FOCUS_KEY 保持一致。
     * 值：-1 不干预 / 0 左窗 / 1 上窗（右上）/ 2 右下窗。
     *
     * 为什么单独一个 key：面板配置那份 JSON 是「隐藏 + 排序」，语义不同，
     * 而且面板配置可能被用户备份/还原，焦点配置不该跟着一起漂。
     */
    private static final String FOCUS_KEY = "canvasplus_triple_focus";
    private static final String[] FOCUS_NAMES = {"不指定", "左窗", "上窗", "右下窗"};
    private static final int[] FOCUS_VALUES = {-1, 0, 1, 2};

    /**
     * 三窗/四窗"从菜单缩小之后，点击窗口只切焦点、不放大"开关。
     * 与 CanvasLayout.noClickZoom() 里的 key 保持一致；缺省（没写过）= 开。
     *
     * 为什么单独一个 key：它改的是画布的点击行为，和面板的隐藏/排序无关，
     * 也不该跟着三分屏焦点一起漂。
     */
    private static final String NO_CLICK_ZOOM_KEY = "canvasplus_no_click_zoom";

    /**
     * 替换应用面板自动弹出的延迟（毫秒），范围 100~2000，默认 400。
     *
     * 为什么做成可配置：AutoReplace 里原本写死 N2_DELAY=400ms，用户反馈有的场景
     * 面板弹得太早（三窗动画还没落地）有的太晚，需要自己按机器手感调。
     * 为什么存 Settings.Global 而不是 App 私有 SP：读它的 AutoReplace 跑在
     * pscanvas 进程（不同 UID），读不到本 App 的 SP，和 hidden/order/focus 一样
     * 只能走 Global。
     */
    private static final String PANEL_DELAY_KEY = "canvasplus_panel_delay";
    private static final int PANEL_DELAY_MIN = 100;
    private static final int PANEL_DELAY_MAX = 2000;
    private static final int PANEL_DELAY_DEFAULT = 400;

    /** 当前选中的焦点目标（-1 = 不干预，保持画布默认）。 */
    private int focusSel = -1;
    /** 缩小后点击窗口是否还放大（true = 不放大，只切焦点）。 */
    private boolean noClickZoom = true;
    /** 替换应用面板自动弹出延迟（毫秒）。 */
    private int panelDelay = PANEL_DELAY_DEFAULT;

    /** 一行 = 一个可被替换的应用。 */
    private static final class Item {
        String pkg;
        String label;
        Drawable icon;
        boolean hidden;
    }

    /** 全量应用，顺序就是「全部应用」列表最终顺序。 */
    private final List<Item> items = new ArrayList<>();
    /** 当前显示的子集（搜索过滤后），与 items 顺序一致；同一个 Item 对象引用。 */
    private final List<Item> shown = new ArrayList<>();
    private ArrayAdapter<Item> adapter;
    private String query = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadFocus();
        loadNoClickZoom();
        loadPanelDelay();
        buildUi();
        loadApps();
    }

    private void buildUi() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        // 坑（2026-11-20 实测）：targetSdk 36 在 Android 15+ 强制 edge-to-edge，
        // 内容会画到状态栏/导航栏底下——标题被状态栏压住、最后一行被手势条盖住。
        // getWindow().setDecorFitsSystemWindows(true) 在这套 targetSdk 下被忽略，
        // 所以自己吃 insets 当 padding。不引 androidx，直接用 framework API。
        root.setBackgroundColor(0xFFF2F3F5);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom);
            return insets;
        });

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("画布：焦点 / 隐藏 / 排序");
        title.setTextSize(18);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 命令清单收进弹窗（2026-11-21 用户反馈：上面这一堆控件把下面的应用列表挤没了）。
        // 弹窗里是 ScrollView，再长的命令也能看全，主界面只留一个按钮。
        Button commands = new Button(this);
        commands.setText("命令");
        commands.setTextSize(12);
        commands.setMinWidth(0);
        commands.setMinimumWidth(0);
        commands.setPadding(dp(10), dp(2), dp(10), dp(2));
        commands.setOnClickListener(v -> showCommandsDialog());
        bar.addView(commands);

        Button save = new Button(this);
        save.setText("保存");
        save.setOnClickListener(v -> save());
        bar.addView(save);
        root.addView(bar);

        addFocusRow(root);
        addNoClickZoomRow(root);
        addPanelDelayRow(root);

        // 说明文字压到两行：之前那段长文案 + 命令清单把 ListView 挤成一条缝（用户反馈过）
        TextView hint = new TextView(this);
        hint.setText("勾选 = 在面板「全部应用」里隐藏，↑↓ 调顺序；改完点保存。"
                + "shell 命令在右上角「命令」里，可复制。");
        hint.setTextSize(12);
        hint.setPadding(0, dp(4), 0, dp(4));
        root.addView(hint);

        // 搜索框紧贴应用列表上方（2026-11-21 用户要求）：原先放在标题栏下面、
        // 和焦点行/开关挤在面板顶部，用户觉得不妥。搜索只作用于下面的应用列表，
        // 放到列表正上方所见即所得，顶部区域也留给全局设置项。
        EditText search = new EditText(this);
        search.setHint("搜索应用名或包名");
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                query = s == null ? "" : s.toString().trim();
                applyFilter();
            }
        });
        root.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<Item>(this, 0, shown) {
            // 不用 convertView：每行都是新建的，这样闭包里捕获的 position 永远是最新的。
            // 复用 convertView 时 position 会错位，点 ↑↓ 会移动错行（ListView 老坑）。
            @Override public View getView(final int position, View convertView, ViewGroup parent) {
                LinearLayout row = new LinearLayout(ConfigActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(6), 0, dp(6));

                if (position < 0 || position >= shown.size()) return row;
                final Item item = shown.get(position);

                ImageView icon = new ImageView(ConfigActivity.this);
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(36), dp(36));
                iconLp.setMarginEnd(dp(8));
                if (item.icon != null) icon.setImageDrawable(item.icon);
                row.addView(icon, iconLp);

                CheckBox hide = new CheckBox(ConfigActivity.this);
                hide.setChecked(item.hidden);
                hide.setOnCheckedChangeListener((b, checked) -> item.hidden = checked);
                row.addView(hide);

                // 应用名和包名拆成两个 TextView（2026-11-21 用户要求「包名另起一行，
                // 以免排版拥挤」）。原先一个 TextView 里 label+"\n"+pkg，长包名会折成
                // 两三行、把 ↑↓ 按钮顶得很挤；拆开后包名单独一行、小字灰色，一行高度
                // 稳定，4 个按钮也不会被挤走。
                TextView label = new TextView(ConfigActivity.this);
                label.setText(item.label);
                label.setTextSize(13);
                label.setPadding(dp(8), 0, dp(8), 0);
                LinearLayout textCol = new LinearLayout(ConfigActivity.this);
                textCol.setOrientation(LinearLayout.VERTICAL);
                textCol.addView(label);
                TextView pkgView = new TextView(ConfigActivity.this);
                pkgView.setText(item.pkg);
                pkgView.setTextSize(10);
                pkgView.setTextColor(0xFF888888);
                pkgView.setPadding(dp(8), 0, dp(8), 0);
                textCol.addView(pkgView);
                row.addView(textCol, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                // 排序按钮：↑↓ 相邻移动，顶/底 一键移到两端（2026-11-21 用户要求）。
                // 不用拖动，避免 androidx 依赖；四个按钮最小宽度收窄到 40dp，
                // 配合包名另起一行后一行放得下。
                Button top = new Button(ConfigActivity.this);
                top.setText("顶");
                top.setTextSize(12);
                top.setMinWidth(dp(40));
                top.setMinimumWidth(0);
                top.setPadding(dp(4), dp(2), dp(4), dp(2));
                top.setOnClickListener(v -> moveToEdge(position, true));
                row.addView(top);

                Button bottom = new Button(ConfigActivity.this);
                bottom.setText("底");
                bottom.setTextSize(12);
                bottom.setMinWidth(dp(40));
                bottom.setMinimumWidth(0);
                bottom.setPadding(dp(4), dp(2), dp(4), dp(2));
                bottom.setOnClickListener(v -> moveToEdge(position, false));
                row.addView(bottom);

                Button up = new Button(ConfigActivity.this);
                up.setText("↑");
                up.setMinWidth(dp(40));
                up.setMinimumWidth(0);
                up.setPadding(dp(4), dp(2), dp(4), dp(2));
                up.setOnClickListener(v -> move(position, -1));
                row.addView(up);

                Button down = new Button(ConfigActivity.this);
                down.setText("↓");
                down.setMinWidth(dp(40));
                down.setMinimumWidth(0);
                down.setPadding(dp(4), dp(2), dp(4), dp(2));
                down.setOnClickListener(v -> move(position, 1));
                row.addView(down);

                return row;
            }
        };
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    /**
     * 三分屏焦点选择行。
     *
     * 为什么不放进下面那个 ListView：ListView 是「每个已装应用一行」，
     * 焦点是三个固定槽位的单选，混进去会把应用列表的序号/顺序搞乱。
     */
    private void addFocusRow(LinearLayout root) {
        TextView label = new TextView(this);
        label.setText("三分屏启动后的焦点窗口");
        label.setTextSize(13);
        label.setPadding(0, dp(8), 0, dp(2));
        root.addView(label);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < FOCUS_NAMES.length; i++) {
            final int value = FOCUS_VALUES[i];
            Button b = new Button(this);
            b.setText(FOCUS_NAMES[i]);
            b.setTextSize(12);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setPadding(dp(2), dp(4), dp(2), dp(4));
            b.setOnClickListener(v -> {
                focusSel = value;
                paintFocusButtons(row);
            });
            row.addView(b, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(row);
        paintFocusButtons(row);
    }

    /**
     * 「缩小后点击窗口不放大」开关行。
     *
     * 单独一行而不是塞进 ListView：ListView 是"每个已装应用一行"，
     * 混进全局开关会把应用序号和 ↑↓ 的 position 搞乱（ListView 复用坑）。
     */
    private void addNoClickZoomRow(LinearLayout root) {
        CheckBox box = new CheckBox(this);
        box.setText("缩小后点击窗口不放大（只切焦点，放大走窗口菜单）");
        box.setTextSize(13);
        box.setChecked(noClickZoom);
        box.setOnCheckedChangeListener((b, checked) -> noClickZoom = checked);
        root.addView(box);
    }

    /**
     * 替换应用面板自动弹出延迟的滑块行（2026-11-21 用户要求）。
     *
     * 为什么用 framework SeekBar 而不是 androidx:本项目零 androidx 依赖（见类注释）。
     * SeekBar 的 progress 直接存"毫秒减下限"（0..1900），读时加回 100，
     * 这样滑块位置和真实毫秒一一对应，不需要再乘系数。
     */
    private void addPanelDelayRow(LinearLayout root) {
        TextView label = new TextView(this);
        label.setText("替换应用面板弹出延迟");
        label.setTextSize(13);
        label.setPadding(0, dp(8), 0, dp(2));
        root.addView(label);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final TextView value = new TextView(this);
        value.setText(panelDelay + " ms");
        value.setTextSize(12);
        value.setMinWidth(dp(64));

        android.widget.SeekBar seek = new android.widget.SeekBar(this);
        seek.setMax(PANEL_DELAY_MAX - PANEL_DELAY_MIN);
        seek.setProgress(panelDelay - PANEL_DELAY_MIN);
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int p, boolean fromUser) {
                if (fromUser) {
                    panelDelay = PANEL_DELAY_MIN + p;
                    value.setText(panelDelay + " ms");
                }
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) { }
        });
        row.addView(value);
        row.addView(seek, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);
    }

    /** 读面板延迟；没写过 / 坏值 / 越界一律退回默认 400ms。 */
    private void loadPanelDelay() {
        try {
            String raw = Settings.Global.getString(getContentResolver(), PANEL_DELAY_KEY);
            if (raw == null || raw.isEmpty()) return;
            int value = Integer.parseInt(raw.trim());
            if (value >= PANEL_DELAY_MIN && value <= PANEL_DELAY_MAX) panelDelay = value;
        } catch (Throwable ignored) {
            // 读不到 / 格式坏：保持默认
        }
    }

    /**
     * 可调用的 shell 命令清单（2026-11-21 用户要求：菜单里声明可调用的命令，并能复制）。
     *
     * 为什么做成弹窗而不是页面顶部一块：
     *   页面顶部已经有标题栏 / 搜索框 / 焦点行 / 点击放大开关，再塞 5 条命令后
     *   下面那个带 weight 的 ListView 只剩一条缝（用户反馈"上面菜单选项占用下面
     *   程序滚动容器"）。命令是低频查看项，收进弹窗最合适。
     *
     * 命令文本统一取自 {@link ShortcutActivity} 的常量：
     * 长按图标里的「复制三分屏命令 / 复制加应用命令」用同一份字符串，两边不会写岔。
     * 这里额外列了带参数的变体（布局方向、切换方向），因为这些只能靠参数表达，
     * 做不成固定快捷方式。
     *
     * 每条用等宽字体 + 可选中：万一「复制」按钮被输入法/无障碍挡住，
     * 用户长按文字也能自己复制。
     */
    private void showCommandsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        box.setPadding(pad, dp(8), pad, dp(4));

        TextView hint = new TextView(this);
        hint.setText("点「复制」带走整条，粘到 adb shell 里执行。");
        hint.setTextSize(12);
        box.addView(hint);

        addCommandRow(box, "三分屏（快捷方式入口）", ShortcutActivity.CMD_THREE);
        addCommandRow(box, "三分屏 · 指定布局方向 3~7", ShortcutActivity.CMD_THREE_MODE);
        addCommandRow(box, "三分屏 · 直接广播（等价）", ShortcutActivity.CMD_START);
        addCommandRow(box, "加应用到画布", ShortcutActivity.CMD_ADD);
        addCommandRow(box, "切换三窗布局方向", ShortcutActivity.CMD_LAYOUT);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("可调用的 shell 命令")
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void addCommandRow(LinearLayout root, String label, final String command) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        TextView text = new TextView(this);
        text.setText(label + "\n" + command);
        text.setTextSize(11);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextIsSelectable(true);
        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button copy = new Button(this);
        copy.setText("复制");
        copy.setTextSize(11);
        copy.setMinWidth(0);
        copy.setMinimumWidth(0);
        copy.setPadding(dp(8), dp(2), dp(8), dp(2));
        copy.setOnClickListener(v -> copyCommand(command));
        row.addView(copy);

        root.addView(row);
    }

    /** 写剪贴板。只写不读，所以不需要持焦点（Android 10+ 只限制读取）。 */
    private void copyCommand(String command) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                toast("复制失败：剪贴板不可用");
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText("CanvasPlus 命令", command));
            toast("已复制：" + command);
        } catch (Throwable t) {
            toast("复制失败：" + t);
        }
    }

    /** 读开关；没写过 / 读不到一律按"开"（与 CanvasLayout 的默认值一致）。 */
    private void loadNoClickZoom() {
        try {
            String raw = Settings.Global.getString(getContentResolver(), NO_CLICK_ZOOM_KEY);
            if (raw == null || raw.isEmpty()) return;
            noClickZoom = !"0".equals(raw.trim());
        } catch (Throwable ignored) {
            // 读不到：保持默认开
        }
    }

    /** 选中的按钮加粗 + 蓝色，其余常规灰 —— 不引 androidx 的 RadioButton 也能看清选中项。 */
    private void paintFocusButtons(ViewGroup row) {
        for (int i = 0; i < row.getChildCount() && i < FOCUS_VALUES.length; i++) {
            View child = row.getChildAt(i);
            if (!(child instanceof Button)) continue;
            Button b = (Button) child;
            boolean on = FOCUS_VALUES[i] == focusSel;
            b.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
            b.setTextColor(on ? 0xFF2A6BFF : 0xFF333333);
        }
    }

    /** 读已保存的焦点配置；读不到 / 坏值一律退回「不指定」。 */
    private void loadFocus() {
        try {
            String raw = Settings.Global.getString(getContentResolver(), FOCUS_KEY);
            if (raw == null || raw.isEmpty()) return;
            int value = Integer.parseInt(raw.trim());
            if (value < -1 || value > 2) return;
            focusSel = value;
        } catch (Throwable ignored) {
            // 读不到 / 格式坏：保持默认「不指定」
        }
    }

    private String focusLabel() {
        for (int i = 0; i < FOCUS_VALUES.length; i++) {
            if (FOCUS_VALUES[i] == focusSel) return FOCUS_NAMES[i];
        }
        return "不指定";
    }

    /**
     * 把第 position 个「可见」行和它上方/下方的可见行在完整列表里交换。
     *
     * 为什么不直接 swap(shown)：搜索过滤时上下相邻的两行在全量列表里可能隔很远，
     * 只换 shown 的显示顺序、不换 items，保存时顺序又会被 items 覆盖。
     * 所以交换 items 里的真实位置，然后按新顺序重建 shown。
     */
    private void move(int position, int delta) {
        int target = position + delta;
        if (position < 0 || position >= shown.size()) return;
        if (target < 0 || target >= shown.size()) return;
        int from = items.indexOf(shown.get(position));
        int to = items.indexOf(shown.get(target));
        if (from < 0 || to < 0) return;
        Collections.swap(items, from, to);
        applyFilter();
    }

    /**
     * 把第 position 个可见行移到全量列表的最前（top=true）或最后（top=false）。
     *
     * 和 move() 同一个坑：必须改 items（全量）而不是 shown（搜索后的子集），
     * 否则搜索过滤时移到"顶"只在可见子集里生效，清掉搜索词后顺序又乱。
     * 搜索状态下移到顶/底是"在所有应用里置顶/置底"，这样语义才稳定。
     */
    private void moveToEdge(int position, boolean top) {
        if (position < 0 || position >= shown.size()) return;
        Item item = shown.get(position);
        int from = items.indexOf(item);
        if (from < 0) return;
        items.remove(from);
        if (top) items.add(0, item);
        else items.add(item);
        applyFilter();
    }

    /** 按 query 重建 shown（保持 items 的顺序），再刷列表。 */
    private void applyFilter() {
        shown.clear();
        String q = query.toLowerCase(java.util.Locale.ROOT);
        for (Item item : items) {
            if (q.isEmpty()
                    || item.label.toLowerCase(java.util.Locale.ROOT).contains(q)
                    || item.pkg.toLowerCase(java.util.Locale.ROOT).contains(q)) {
                shown.add(item);
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    /** 读已安装的桌面应用，按已有配置排序并标记隐藏状态。 */
    private void loadApps() {
        try {
            Set<String> hidden = new HashSet<>(readArray("hidden"));
            List<String> order = readArray("order");

            PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved = pm.queryIntentActivities(main, 0);
            Set<String> seen = new HashSet<>();
            for (ResolveInfo info : resolved) {
                if (info.activityInfo == null) continue;
                String pkg = info.activityInfo.packageName;
                if (pkg == null || pkg.equals(getPackageName())) continue;
                if (!seen.add(pkg)) continue;
                Item item = new Item();
                item.pkg = pkg;
                item.label = String.valueOf(info.loadLabel(pm));
                try {
                    item.icon = info.loadIcon(pm);
                } catch (Throwable ignored) {
                    // 个别应用取图标会抛，缺图标不影响功能
                }
                item.hidden = hidden.contains(pkg);
                items.add(item);
            }

            // 已配置过的顺序优先，其余按名称排
            items.sort((a, b) -> {
                int ia = order.indexOf(a.pkg);
                int ib = order.indexOf(b.pkg);
                if (ia >= 0 || ib >= 0) {
                    if (ia < 0) return 1;
                    if (ib < 0) return -1;
                    return Integer.compare(ia, ib);
                }
                return a.label.compareToIgnoreCase(b.label);
            });
            applyFilter();
            if (items.isEmpty()) {
                toast("没读到可启动的应用（检查 Manifest 里 <queries> 是否生效）");
            }
        } catch (Throwable t) {
            toast("读取应用列表失败: " + t);
        }
    }

    private void save() {
        try {
            JSONArray order = new JSONArray();
            JSONArray hidden = new JSONArray();
            for (Item item : items) {
                // order 里也保留被隐藏的包：以后取消隐藏能回到原来的位置。
                // 面板侧 AutoReplace.filterAndSort 是先过滤再按 order 排，
                // 隐藏项留在 order 里不会被显示出来。
                order.put(item.pkg);
                if (item.hidden) hidden.put(item.pkg);
            }
            JSONObject json = new JSONObject();
            json.put("hidden", hidden);
            json.put("order", order);
            final String raw = json.toString();

            // 焦点配置单独一个 key：写失败只影响这一项，不拖累面板配置
            boolean focusOk = false;
            try {
                focusOk = Settings.Global.putString(
                        getContentResolver(), FOCUS_KEY, String.valueOf(focusSel));
            } catch (Throwable ignored) {
                // 没权限：下面的 toast 会提示
            }
            // 点击放大开关同理，单独一个 key
            final String zoomValue = noClickZoom ? "1" : "0";
            boolean zoomOk = false;
            try {
                zoomOk = Settings.Global.putString(
                        getContentResolver(), NO_CLICK_ZOOM_KEY, zoomValue);
            } catch (Throwable ignored) {
                // 没权限：下面的 toast 会提示
            }
            // 面板延迟同理，单独一个 key（AutoReplace 读它，与面板配置 JSON 无关）
            boolean delayOk = false;
            try {
                delayOk = Settings.Global.putString(
                        getContentResolver(), PANEL_DELAY_KEY, String.valueOf(panelDelay));
            } catch (Throwable ignored) {
                // 没权限：下面的 toast 会提示
            }

            if (writeGlobal(raw)) {
                toast("已保存：" + hidden.length() + " 个隐藏，共 " + order.length() + " 个应用，"
                        + "焦点=" + focusLabel() + "，延迟=" + panelDelay + "ms"
                        + (focusOk ? "" : "（焦点项没写进去）")
                        + (zoomOk ? "" : "（点击放大开关没写进去）")
                        + (delayOk ? "" : "（延迟项没写进去）"));
                finish();
                return;
            }

            // 降级：交给 system_server 里的 Trigger 代写（它有系统权限）。
            // 注意 setPackage("android")：Trigger 的 receiver 是在 system_server 进程里
            // 动态注册的，它的包名就是 "android"，不指定包名会发给所有能收的进程。
            sendBroadcast(new Intent(ACTION_SET_CFG)
                    .setPackage("android")
                    .putExtra("json", raw)
                    .putExtra("no_click_zoom", zoomValue)
                    .putExtra("panel_delay", panelDelay));
            // 广播是异步的，等一小会儿回读，给用户真实结果，不要假装成功。
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (raw.equals(readCfg())) {
                    toast("已保存");
                } else {
                    toast("没写进去：请执行 adb shell pm grant com.canvasplus "
                            + "android.permission.WRITE_SECURE_SETTINGS，"
                            + "或确认 LSPosed 作用域包含系统框架");
                }
                finish();
            }, 800L);
        } catch (Throwable t) {
            toast("保存失败: " + t);
        }
    }

    /**
     * 直写 Settings.Global 并回读校验。
     *
     * 坑（2026-11-20）：没权限时 putString **不一定抛异常**，某些实现是静默返回 false，
     * 只看"没抛异常"会误判成功、给用户假的"已保存"，配置其实没变。所以既要看返回值，
     * 也要回读比对。
     */
    private boolean writeGlobal(String raw) {
        try {
            if (!Settings.Global.putString(getContentResolver(), CFG_KEY, raw)) return false;
            return raw.equals(readCfg());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读当前落盘的原始 JSON；读不到返回 null。 */
    private String readCfg() {
        try {
            return Settings.Global.getString(getContentResolver(), CFG_KEY);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读配置里的一个字符串数组，读不到返回空列表。 */
    private List<String> readArray(String name) {
        List<String> out = new ArrayList<>();
        try {
            String raw = readCfg();
            if (raw == null || raw.isEmpty()) return out;
            JSONArray array = new JSONObject(raw).optJSONArray(name);
            if (array == null) return out;
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i);
                if (value != null && !value.isEmpty()) out.add(value);
            }
        } catch (Throwable ignored) {
            // 配置损坏就当没有，页面按默认展示，保存时会重写一份干净的
        }
        return out;
    }

    private void toast(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
