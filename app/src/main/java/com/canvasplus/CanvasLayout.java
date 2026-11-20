package com.canvasplus;

import android.content.Intent;
import android.graphics.Rect;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 画布：沿用原本的三窗布局，把任务尺寸统一成屏幕可用区。
 *
 * 平板上任务比例接近屏幕比例，所以 M1 算出来刚好。手机上任务数据是方形，
 * 窗口跟着方形，和屏幕差太多。这里把任务 bounds 改成屏幕可用区
 * （宽度 × 高度减状态栏），并同步 FlexibleTaskView.mLaunchBounds 和
 * u1/d 里的布局缓存，M1 才会按手机比例算窗口。
 *
 * 四窗不另写排版，接进官方的 w1 → N2 → M1 链路：只放开数量判断、
 * 给 M1 补 4 个矩形，其余（调节条、焦点缩放、关闭、浮窗）全走原实现。
 */
final class CanvasLayout {

    private static final String TAG = "CanvasPlus";
    private static final String POLICY_SELECTOR = "B1.n";
    private static final String TABLET_POLICY = "B1.r";
    private static final String CONTAINER_VIEW =
            "com.oplus.pscanvas.canvasmode.canvas.view.ContainerView";
    private static final String FLEXIBLE_TASK_VIEW =
            "com.oplus.flexiblewindow.FlexibleTaskView";
    static volatile int forcedMode = 0;
    /** 三分屏布局方向，默认 6 = 一左两右，0 表示交给系统。 */
    static volatile int forcedOrientation = 6;
    private static int attempts = 0;
    private static volatile boolean relayoutScheduled = false;
    private static final java.util.WeakHashMap<Object, String> layoutKeys =
            new java.util.WeakHashMap<>();
    /** 活着画布实例，切换布局方向时需要用。 */
    private static final java.util.WeakHashMap<Object, Boolean> containers =
            new java.util.WeakHashMap<>();
    private static volatile int skipped = 0;

    /**
     * 两窗放大统一记账：记录哪些"画布实例"已经做过首次自动放大。
     *
     * 需求：进入系统画布二分屏时，默认让一个窗口占满（88%），另一个留一条（12%）。
     * 按 container 记账，保证同一画布会话里自动放大只发生一次；画布重建后新
     * container 会重新放大一次。
     */
    private static final java.util.WeakHashMap<Object, Boolean> twoAutoZoomed =
            new java.util.WeakHashMap<>();
    /** 正在放大动画中，避免布局回调在动画期间重复触发。 */
    private static final java.util.WeakHashMap<Object, Boolean> twoZoomPending =
            new java.util.WeakHashMap<>();
    /**
     * 每个窗口(task)是否已经放大过——自动放大和点击放大共用这一张表。
     *
     * 坑（已修 bug）：原来自动放大按 container 记（twoAutoZoomed），点击放大按 task
     * 记（clickZoomed），两套 key 互不共享。结果：进两窗时自动放大了虎扑，焦点切到
     * 酷安再回虎扑时，点击放大查 clickZoomed 里虎扑没记录，又放大一次。
     * 修法：无论自动还是点击，实际放大前都在 doAutoEnlargeTwo 里按目标 task 查这张表，
     * 放大后立刻记进去；两种入口共享，保证一个窗口一生只放大一次。
     * key 用 task 对象（u1/d），窗口关闭后自动从弱表回收。
     */
    private static final java.util.WeakHashMap<Object, Boolean> enlargedTasks =
            new java.util.WeakHashMap<>();

    /** 布局参数指纹：任务数、应用标识、taskId、bounds，用来跳过没变化的重排。 */
    private static String layoutKey(Object container) {
        try {
            Object adapter = fieldOfType(container, "u1.c");
            if (adapter == null) return "";
            int count = count(adapter);
            StringBuilder sb = new StringBuilder();
            sb.append(forcedOrientation).append(':').append(count).append(':');
            for (int i = 0; i < count; i++) {
                Object task = XposedHelpers.callMethod(adapter, "getItem", i);
                if (task == null) continue;
                Object rect = XposedHelpers.callMethod(task, "o");
                Object source = null;
                Object taskId = null;
                try {
                    source = XposedHelpers.callMethod(task, "k");
                } catch (Throwable ignored) {
                }
                try {
                    taskId = XposedHelpers.callMethod(task, "t");
                } catch (Throwable ignored) {
                }
                // 替换应用后 bounds 一样，必须靠应用标识区分，否则这次重排会被跳过
                sb.append(source).append('@').append(taskId).append(rect);
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private CanvasLayout() {}

    private static volatile boolean multiWindowMode = false;
    /** 只在三屏布局那一瞬间锁定 o()。 */
    private static volatile boolean oLock = false;
    private static final java.util.WeakHashMap<Object, Integer> taskCounts =
            new java.util.WeakHashMap<>();

    static void install(ClassLoader loader) {
        useTabletPolicy(loader);
        forceSplitMode(loader);
        allowMoreThanThree(loader);
        ignoreLineLayout(loader);
        hookTaskDataBounds(loader);
        hookFullscreen(loader);
        hookAutoEnlargeTwo(loader);
        hookTaskInfo(loader);
        hookResume(loader);
        hookClick(loader);
        hookDragLock(loader);
        hookOrientation(loader);
        hookAddLimit(loader);
        hookFlexibleUiArray(loader);
        hookRectOrientation(loader);
        hookNativeFour(loader);
        hookLayoutCalc(loader);
        hookZoomFour(loader);
        hookFourIntents(loader);
        hookExchange(loader);
        hookWindowMenu(loader);
        hookFourZoom(loader);
    }

    /**
     * 三窗时窗口菜单里的"放大窗口/缩小窗口"用不了，直接不给这两项。
     * 菜单在 t.e0() 末尾统一 add 进 t.W，这里加完再把它们摘掉。
     */
    private static void hookWindowMenu(ClassLoader loader) {
        try {
            Class<?> menu = XposedHelpers.findClass(
                    "com.oplus.pscanvas.canvasmode.canvas.t", loader);
            Class<?> strings = XposedHelpers.findClass("t1.j", loader);
            final int enlargeId = XposedHelpers.getStaticIntField(strings, "W");
            final int narrowId = XposedHelpers.getStaticIntField(strings, "X");
            XposedBridge.hookAllMethods(menu, "e0", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object self = param.thisObject;
                        Object container = XposedHelpers.getObjectField(self, "n");
                        Object adapter = fieldOfType(container, "u1.c");
                        if (adapter == null || count(adapter) != 3) return;
                        Object items = XposedHelpers.getObjectField(self, "W");
                        if (!(items instanceof java.util.List)) return;
                        android.content.Context context =
                                (android.content.Context) XposedHelpers.getObjectField(self, "j");
                        String enlarge = context.getResources().getString(enlargeId);
                        String narrow = context.getResources().getString(narrowId);
                        ((java.util.List<?>) items).removeIf(item -> {
                            try {
                                Object title = XposedHelpers.callMethod(item, "t");
                                return enlarge.equals(title) || narrow.equals(title);
                            } catch (Throwable ignored) {
                                return false;
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("window menu hooked");
        } catch (Throwable error) {
            log("window menu hook failed: " + error);
        }
    }

    /** 四窗当前是不是"放大"状态。默认 false = 四等分。 */
    private static final java.util.WeakHashMap<Object, Boolean> fourZoomed =
            new java.util.WeakHashMap<>();

    private static boolean isFourZoomed(Object container) {
        return Boolean.TRUE.equals(fourZoomed.get(container));
    }

    /**
     * 四窗的"放大窗口/缩小窗口"。
     *
     * 默认四等分（画布按 2×2 总尺寸缩放到屏幕），点放大把焦点窗口铺满屏幕，
     * 点缩小回到四等分。都走画布自己的 A2() 缩放，不改窗口矩形。
     */
    private static void hookFourZoom(ClassLoader loader) {
        try {
            Class<?> replacer = XposedHelpers.findClass(
                    "com.oplus.pscanvas.canvasmode.canvas.c", loader);
            XposedBridge.hookAllMethods(replacer, "m", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object container = param.args[0];
                        Object adapter = fieldOfType(container, "u1.c");
                        if (adapter == null || count(adapter) != 4) return;
                        boolean enlarge = (Boolean) param.args[2];
                        if (enlarge) {
                            fourZoomed.put(container, Boolean.TRUE);
                            XposedHelpers.callMethod(param.args[1], "p0", true);
                            zoomFourFocus(container, adapter, param.args[1]);
                        } else {
                            fourZoomed.put(container, Boolean.FALSE);
                            XposedHelpers.callMethod(param.args[1], "p0", false);
                            fitFour(container, 450);
                        }
                        param.setResult(null);
                        log("four zoom " + (enlarge ? "in" : "out"));
                    } catch (Throwable error) {
                        log("four zoom failed: " + error);
                    }
                }
            });
            log("four zoom hooked");
        } catch (Throwable error) {
            log("four zoom hook failed: " + error);
        }
    }

    /** 把焦点窗口缩放到铺满屏幕。 */
    private static void zoomFourFocus(Object container, Object adapter, Object task) {
        try {
            int focus = 0;
            Object position = XposedHelpers.callMethod(adapter, "getPosition", task);
            if (position instanceof Integer && (Integer) position >= 0) {
                focus = (Integer) position;
            }
            Object rects = XposedHelpers.getObjectField(container, "l");
            android.content.Context context = ((android.view.View) container).getContext();
            Object list = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("B1.l", container.getClass().getClassLoader()),
                    "a0", context, rects,
                    XposedHelpers.callMethod(adapter, "n"),
                    XposedHelpers.callMethod(adapter, "v"),
                    focus);
            if (!(list instanceof java.util.List) || ((java.util.List<?>) list).isEmpty()) {
                log("four zoom: a0 empty");
                return;
            }
            java.util.List<?> points = (java.util.List<?>) list;
            Object last = points.get(points.size() - 1);
            float tx = XposedHelpers.getFloatField(last, "left");
            float ty = XposedHelpers.getFloatField(last, "top");
            float scale = XposedHelpers.getFloatField(last, "right");
            XposedHelpers.callMethod(adapter, "I", focus);
            XposedHelpers.callMethod(container, "A2", tx, ty, scale, 450, 0);
        } catch (Throwable error) {
            log("four zoom focus failed: " + error);
        }
    }

    /** 四等分：画布缩放到 2×2 总尺寸正好放进屏幕。 */
    private static void fitFour(Object container, int duration) {
        try {
            Object adapter = fieldOfType(container, "u1.c");
            if (adapter == null || count(adapter) != 4) return;
            Rect cell = phoneSize();
            int gap = Math.round(10f * android.content.res.Resources
                    .getSystem().getDisplayMetrics().density);
            android.view.View view = (android.view.View) container;
            float fit = Math.min(
                    (float) view.getWidth() / (2 * cell.width() + gap),
                    (float) view.getHeight() / (2 * cell.height() + gap));
            if (fit <= 0f || fit > 1f) return;
            XposedHelpers.callMethod(container, "A2", 0f, 0f, fit, duration, 4);
        } catch (Throwable error) {
            log("four fit failed: " + error);
        }
    }

    /**
     * 拖拽条上的"交换"走 ContainerView.k0(E.c)，里面对 getRectList() 的每个下标
     * 取 q0(i) 再 B2()。矩形数比子 view 多的时候 q0 返回 null，直接 NPE 把画布
     * 干掉（表现是当前应用突然全屏）。这里先补一次子 view 再让它继续。
     */
    private static void hookExchange(ClassLoader loader) {
        try {
            Class<?> container = XposedHelpers.findClass(CONTAINER_VIEW, loader);
            Class<?> barItem = XposedHelpers.findClass(
                    "com.oplus.pscanvas.canvasmode.canvas.E$c", loader);
            XposedHelpers.findAndHookMethod(container, "k0", barItem, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object self = param.thisObject;
                        Object rects = XposedHelpers.getObjectField(self, "l");
                        int want = rects instanceof java.util.List
                                ? ((java.util.List<?>) rects).size() : 0;
                        int have = embeddedCount(self);
                        if (have < want) {
                            log("exchange fill views " + have + "/" + want);
                            XposedHelpers.callMethod(self, "T2");
                            have = embeddedCount(self);
                        }
                        if (have < want) {
                            param.setResult(Boolean.FALSE);
                            log("exchange skipped " + have + "/" + want);
                        }
                    } catch (Throwable error) {
                        log("exchange guard failed: " + error);
                    }
                }
            });
            log("exchange hooked");
        } catch (Throwable error) {
            log("exchange hook failed: " + error);
        }
    }

    private static int embeddedCount(Object container) {
        try {
            Object list = XposedHelpers.callMethod(container, "getChildEmbeddedViewList");
            return list instanceof java.util.List ? ((java.util.List<?>) list).size() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 快捷方式/广播带 4 个 intent 启动画布时，ContainerActivity 会把整个 list
     * 交给 FlexibleWindowManager.calculateFlexibleWindowBounds(List, int, Bundle)，
     * 服务端 calculateFlexibleWindowBoundList 里写死 if (size > 3) 返回空 Bundle，
     * 画布就当成"应用不支持"把自己关掉（闪一下就没了）。
     * 四窗时绕开那条链路，逐个拿单个 intent 的 bounds 再拼回去。
     */
    private static void hookFourIntents(ClassLoader loader) {
        try {
            Class<?> manager = XposedHelpers.findClass(
                    "com.oplus.flexiblewindow.FlexibleWindowManager", loader);
            XposedHelpers.findAndHookMethod(manager, "calculateFlexibleWindowBounds",
                    java.util.List.class, int.class, android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object input = param.args[0];
                                if (!(input instanceof java.util.List)) return;
                                java.util.List<?> intents = (java.util.List<?>) input;
                                if (intents.size() <= 3) return;
                                java.util.ArrayList<android.os.Bundle> bundles =
                                        new java.util.ArrayList<>();
                                for (Object item : intents) {
                                    if (!(item instanceof Intent)) return;
                                    Intent mirror = new Intent((Intent) item);
                                    mirror.putExtra("androidx.activity.LaunchScenario", 2);
                                    Object single = XposedHelpers.callMethod(param.thisObject,
                                            "calculateFlexibleWindowBounds", mirror, -2, 0);
                                    if (!(single instanceof android.os.Bundle)) return;
                                    bundles.add((android.os.Bundle) single);
                                }
                                android.os.Bundle out = new android.os.Bundle();
                                out.putParcelableArrayList(
                                        "androidx.flexible.layout.info.list", bundles);
                                param.setResult(out);
                                log("four intents bounds " + bundles.size());
                            } catch (Throwable error) {
                                log("four intents bounds failed: " + error);
                            }
                        }
                    });
            log("four intents hooked");
        } catch (Throwable error) {
            log("four intents hook failed: " + error);
        }
    }

    /**
     * 浮窗（switchToZoom）只写了 2/3 窗：
     * I.k0() 里 if (i4 != 3) return false，I.i0() 也只认 2/3，
     * I.n0() 还要求 adapter count == 传入的窗数。
     * 四窗补一条等价分支：其余三个窗口缩到三窗布局，焦点窗口转浮窗。
     */
    private static void hookZoomFour(ClassLoader loader) {
        try {
            final Class<?> zoom = XposedHelpers.findClass(
                    "com.oplus.pscanvas.canvasmode.canvas.I", loader);
            final Class<?> taskData = XposedHelpers.findClass("u1.d", loader);
            final Class<?> taskView = XposedHelpers.findClass(
                    "com.oplus.flexiblewindow.FlexibleTaskView", loader);
            final Class<?> animClass = XposedHelpers.findClass(
                    "com.oplus.pscanvas.canvasmode.canvas.I$j", loader);
            java.lang.reflect.Constructor<?> ctor = null;
            try {
                ctor = animClass.getDeclaredConstructor(
                        zoom, Rect.class, Rect.class, taskView, taskData, boolean.class);
                ctor.setAccessible(true);
            } catch (Throwable error) {
                log("zoom anim ctor failed: " + error);
            }
            final java.lang.reflect.Constructor<?> animCtor = ctor;

            XposedHelpers.findAndHookMethod(zoom, "n0", int.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                int index = (Integer) param.args[0];
                                Object container = XposedHelpers.getObjectField(
                                        param.thisObject, "e");
                                Object adapter = XposedHelpers.callMethod(container, "getAdapter");
                                int total = (Integer) XposedHelpers.callMethod(adapter, "getCount");
                                log("n0 " + index + "/" + param.args[1] + " total=" + total);
                                if (total == 4 && index >= 0 && index < 4) {
                                    param.setResult(Boolean.TRUE);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });

            // i0/k0 在同类里会被 ART 内联，hook 不到，改在最外层 y0 改参数：
            // 四窗按三窗动画流程走，焦点在 3 时正好对上。
            XposedHelpers.findAndHookMethod(zoom, "y0", int.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object self = param.thisObject;
                                Object container = XposedHelpers.getObjectField(
                                        self, "e");
                                Object adapter = XposedHelpers.callMethod(container, "getAdapter");
                                int total = (Integer) XposedHelpers.callMethod(adapter, "getCount");
                                if (total < 4) return;
                                log("y0 four focus=" + param.args[0] + " total=" + total);
                                param.args[1] = 3;
                            } catch (Throwable ignored) {
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(zoom, "i0", int.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if ((Integer) param.args[1] != 4) return;
                                Object result = param.getResult();
                                log("i0 four focus=" + param.args[0] + " result=" + result);
                                if (result instanceof java.util.List
                                        && !((java.util.List<?>) result).isEmpty()) return;
                                int focus = (Integer) param.args[0];
                                Object self = param.thisObject;
                                Object container = XposedHelpers.getObjectField(
                                        self, "e");
                                java.util.ArrayList<Rect> rects = new java.util.ArrayList<>();
                                for (int i = 0; i < 4; i++) {
                                    if (i == focus) {
                                        rects.add(new Rect());
                                        continue;
                                    }
                                    Object decor = XposedHelpers.callMethod(container, "q0", i);
                                    Object rect = XposedHelpers.callMethod(self, "h0", decor);
                                    log("i0 rect[" + i + "]=" + rect);
                                    if (!(rect instanceof Rect)
                                            || ((Rect) rect).width() <= 0
                                            || ((Rect) rect).height() <= 0) return;
                                    rects.add(new Rect((Rect) rect));
                                }
                                param.setResult(rects);
                            } catch (Throwable error) {
                                log("i0 four failed: " + error);
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(zoom, "k0", java.util.List.class,
                    int.class, int.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                int total = (Integer) param.args[2];
                                if (total != 4) return;
                                if (animCtor == null) return;
                                int focus = (Integer) param.args[1];
                                java.util.List<?> starts = (java.util.List<?>) param.args[0];
                                Object self = param.thisObject;
                                Object container = XposedHelpers.getObjectField(
                                        self, "e");
                                Object adapter = XposedHelpers.callMethod(container, "getAdapter");
                                Object controller = XposedHelpers.callMethod(
                                        container, "getContainerController");
                                Object context = XposedHelpers.getObjectField(self, "c");
                                ClassLoader appLoader = container.getClass().getClassLoader();
                                java.util.ArrayList<Object> rest = new java.util.ArrayList<>();
                                for (int i = 0; i < 4; i++) {
                                    if (i != focus) {
                                        rest.add(XposedHelpers.callMethod(controller, "l", i));
                                    }
                                }
                                XposedHelpers.callMethod(container, "e3", context, rest, 0);
                                Object computed = XposedHelpers.callStaticMethod(
                                        XposedHelpers.findClass("B1.l", appLoader),
                                        "c1", context, rest, 0, false);
                                if (!(computed instanceof java.util.List)
                                        || ((java.util.List<?>) computed).size() != 3) {
                                    param.setResult(Boolean.FALSE);
                                    return;
                                }
                                java.util.List<Object> targets =
                                        new java.util.ArrayList<Object>((java.util.List<?>) computed);
                                targets.add(focus, new Rect());
                                Object sources = XposedHelpers.getObjectField(self, "O");
                                for (int i = 0; i < 4; i++) {
                                    if (!(Boolean) XposedHelpers.callMethod(self, "n0", i, 4)) {
                                        param.setResult(Boolean.FALSE);
                                        return;
                                    }
                                    if (i == focus) continue;
                                    Object decor = XposedHelpers.callMethod(container, "q0", i);
                                    Object view = XposedHelpers.callMethod(
                                            decor, "getFlexibleTaskView");
                                    Object anim = animCtor.newInstance(
                                            self,
                                            new Rect((Rect) starts.get(i)),
                                            new Rect((Rect) targets.get(i)),
                                            view,
                                            XposedHelpers.callMethod(adapter, "getItem", i),
                                            false);
                                    synchronized (sources) {
                                        ((java.util.List<Object>) sources).add(anim);
                                    }
                                }
                                param.setResult(Boolean.TRUE);
                                log("zoom four patched");
                            } catch (Throwable error) {
                                log("zoom four k0 failed: " + error);
                            }
                        }
                    });
            log("zoom four hooked");
        } catch (Throwable error) {
            log("zoom four hook failed: " + error);
        }
    }

    /**
     * 关闭窗口时 B1.l.a1 会调 M() 重算布局，四窗时 M 返回空列表，
     * 紧接着 M3.get(0) 就 IndexOutOfBounds。这里给四窗补一份结果。
     */
    private static void hookLayoutCalc(ClassLoader loader) {
        try {
            Class<?> util = XposedHelpers.findClass("B1.l", loader);
            XposedHelpers.findAndHookMethod(util, "M",
                    android.content.Context.class, java.util.List.class,
                    int.class, int.class, int.class, boolean.class,
                    android.os.Bundle.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object result = param.getResult();
                                Object input = param.args[1];
                                if (!(input instanceof java.util.List)) return;
                                java.util.List<?> list = (java.util.List<?>) input;
                                if (list.size() < 4) return;
                                if (result instanceof java.util.List
                                        && !((java.util.List<?>) result).isEmpty()) return;
                                Rect cell = phoneSize();
                                int gap = Math.round(10f * android.content.res.Resources
                                        .getSystem().getDisplayMetrics().density);
                                int w = cell.width();
                                int h = cell.height();
                                java.util.List<Rect> rects = new java.util.ArrayList<>();
                                for (int i = 0; i < list.size(); i++) {
                                    int left = (i % 2) * (w + gap);
                                    int top = (i / 2) * (h + gap);
                                    rects.add(new Rect(left, top, left + w, top + h));
                                }
                                param.setResult(rects);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            log("layout calc patched");
        } catch (Throwable error) {
            log("layout calc hook failed: " + error);
        }
    }

    /**
     * 四窗时任务矩形方向回调会崩：EmbeddedViewDecor.j0 取一个长度 0 的列表，
     * 抛 IndexOutOfBounds 直接把画布进程干掉。四窗先跳过这条链路。
     */
    private static void hookRectOrientation(ClassLoader loader) {
        try {
            Class<?> activity = XposedHelpers.findClass(
                    "com.oplus.pscanvas.canvasmode.canvas.ContainerActivity", loader);
            XposedBridge.hookAllMethods(activity, "x1", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object view = XposedHelpers.callMethod(param.thisObject, "x0");
                        Object adapter = view == null ? null : fieldOfType(view, "u1.c");
                        if (adapter != null && count(adapter) >= 4) {
                            param.setResult(null);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("rect orientation guarded");
        } catch (Throwable error) {
            log("rect orientation hook failed: " + error);
        }
    }

    /**
     * 四窗不自己排版，接进官方三窗那套链路：
     * 1. w1() 里只对 count==3 调 N2()，四窗补一次一样的调用；
     * 2. N2() 拿 B1.l.M1() 的结果按个数摆窗口，M1() 原本只算 3 个，四窗补 4 个矩形；
     * 3. 之后 layoutChildren 末尾的 E.V()/E.H() 自然出调节条，onLayout 的 T() 自然处理点击放大；
     * 4. u1() 原本只处理 2/3 窗，四窗照三窗走 X1()。
     */
    private static void hookNativeFour(ClassLoader loader) {
        try {
            Class<?> view = XposedHelpers.findClass(CONTAINER_VIEW, loader);
            XposedHelpers.findAndHookMethod(view, "w1", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object adapter = fieldOfType(param.thisObject, "u1.c");
                        if (adapter == null || count(adapter) != 4) return;
                        XposedHelpers.callMethod(param.thisObject, "N2");
                    } catch (Throwable error) {
                        log("native four layout failed: " + error);
                    }
                }
            });
            log("native four layout hooked");
        } catch (Throwable error) {
            log("native four layout hook failed: " + error);
        }

        // N2() 会往 getRectList() 里 addAll，但只有 w1() 会先清。resume、
        // 直接调 N2 的路径没清，矩形会累加到 6、9 个，交换和调节条全错位。
        try {
            Class<?> view = XposedHelpers.findClass(CONTAINER_VIEW, loader);
            XposedHelpers.findAndHookMethod(view, "N2", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object adapter = fieldOfType(param.thisObject, "u1.c");
                        // 坑：这个清空是为三四窗防矩形累加的，两窗绝不能清。
                        // 两窗 rectFList 由系统 O2() 维护，点小窗 autoScale 要读它；
                        // 清了就报 "rectFList is null"，点小窗无法翻转。
                        if (adapter != null && count(adapter) < 3) return;
                        Object rects = XposedHelpers.getObjectField(param.thisObject, "l");
                        if (rects instanceof java.util.List) {
                            ((java.util.List<?>) rects).clear();
                        }
                        Object union = XposedHelpers.getObjectField(param.thisObject, "m");
                        if (union instanceof Rect) {
                            ((Rect) union).setEmpty();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("rect list reset hooked");
        } catch (Throwable error) {
            log("rect list reset hook failed: " + error);
        }

        try {
            Class<?> util = XposedHelpers.findClass("B1.l", loader);
            XposedBridge.hookAllMethods(util, "M1", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object input = param.args[0];
                        if (!(input instanceof java.util.List)) return;
                        if (((java.util.List<?>) input).size() != 4) return;
                        Object result = param.getResult();
                        if (result instanceof java.util.List
                                && !((java.util.List<?>) result).isEmpty()) return;
                        Rect cell = phoneSize();
                        int gap = Math.round(10f * android.content.res.Resources
                                .getSystem().getDisplayMetrics().density);
                        int w = cell.width();
                        int h = cell.height();
                        java.util.ArrayList<Rect> rects = new java.util.ArrayList<>();
                        for (int i = 0; i < 4; i++) {
                            int left = (i % 2) * (w + gap);
                            int top = (i / 2) * (h + gap);
                            rects.add(new Rect(left, top, left + w, top + h));
                        }
                        param.setResult(rects);
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("four rect layout hooked");
        } catch (Throwable error) {
            log("four rect layout hook failed: " + error);
        }

        try {
            Class<?> view = XposedHelpers.findClass(CONTAINER_VIEW, loader);
            Class<?> taskData = XposedHelpers.findClass("u1.d", loader);
            XposedHelpers.findAndHookMethod(view, "S1", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object adapter = fieldOfType(param.thisObject, "u1.c");
                        Object focus = adapter == null ? "?" : XposedHelpers.callMethod(adapter, "h");
                        log("S1 focus=" + focus + " count="
                                + (adapter == null ? -1 : count(adapter)));
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedHelpers.findAndHookMethod(view, "T1", taskData, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object adapter = fieldOfType(param.thisObject, "u1.c");
                        int total = adapter == null ? -1 : count(adapter);
                        // u1() 会被 ART 内联进 T1，hook 不到，直接在这里补四窗的关闭
                        if (adapter == null || total != 4) return;
                        XposedHelpers.callMethod(param.thisObject, "X1", param.args[0]);
                        log("close window four");
                    } catch (Throwable error) {
                        log("close four failed: " + error);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(view, "u1", taskData, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    log("u1 reached");
                }
            });
            log("four close hooked");
        } catch (Throwable error) {
            log("four close hook failed: " + error);
        }
    }

    /** o2() 往 A1.b.l() 的 Rect[] 里按下标写，数组按三窗分配，四窗会越界。 */
    private static void hookFlexibleUiArray(ClassLoader loader) {
        try {
            Class<?> ui = XposedHelpers.findClass("A1.b", loader);
            XposedBridge.hookAllMethods(ui, "l", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (!(result instanceof Rect[])) return;
                    Rect[] rects = (Rect[]) result;
                    if (rects.length >= 4) return;
                    Rect[] bigger = new Rect[4];
                    System.arraycopy(rects, 0, bigger, 0, rects.length);
                    param.setResult(bigger);
                }
            });
            log("flexible ui array hooked");
        } catch (Throwable error) {
            log("flexible ui array hook failed: " + error);
        }
    }

    /** s0.d() 里写死 count >= 3 就拒绝，四窗要放行一次。 */
    private static final ThreadLocal<Boolean> addingTask = new ThreadLocal<>();

    private static void hookAddLimit(ClassLoader loader) {
        try {
            Class<?> controller = XposedHelpers.findClass(
                    "com.oplus.pscanvas.canvasmode.canvas.s0", loader);
            Class<?> taskData = XposedHelpers.findClass("u1.d", loader);
            XposedHelpers.findAndHookMethod(controller, "d", taskData, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    addingTask.set(Boolean.TRUE);
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    addingTask.remove();
                }
            });
            Class<?> adapter = XposedHelpers.findClass("u1.c", loader);
            XposedHelpers.findAndHookMethod(adapter, "getCount", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (addingTask.get() == null) return;
                    // 只放行上限判断这一次，后面的 getCount 保持真实值
                    addingTask.remove();
                    param.setResult(2);
                }
            });
            log("add limit lifted");
        } catch (Throwable error) {
            log("add limit hook failed: " + error);
        }
    }

    /** 三分屏布局方向可控：N2 会拿自己的字段覆盖方向，这里直接改传给 M1 的参数。 */
    private static void hookOrientation(ClassLoader loader) {
        try {
            Class<?> util = XposedHelpers.findClass("B1.l", loader);
            XposedHelpers.findAndHookMethod(util, "M1",
                    java.util.List.class, int.class, float.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            int forced = forcedOrientation;
                            if (forced == 0) return;
                            Object list = param.args[0];
                            if (!(list instanceof java.util.List)) return;
                            if (((java.util.List<?>) list).size() != 3) return;
                            param.args[1] = forced;
                            log("force orientation " + forced);
                        }
                    });
            log("orientation hooked");
        } catch (Throwable error) {
            log("orientation hook failed: " + error);
        }
    }

    /** 切换布局方向并立刻重排。 */
    static void applyOrientation(int orientation) {
        forcedOrientation = orientation;
        log("orientation -> " + orientation);
        for (Object container : containers.keySet()) {
            try {
                layoutKeys.remove(container);
                XposedHelpers.callMethod(container, "N2");
            } catch (Throwable error) {
                log("apply orientation failed: " + error);
            }
        }
    }

    /**
     * 按指定方向重新摆三个窗口。
     *
     * 矩形仍然交给官方的 B1.l.M1 算，这里只是把方向换掉，
     * 排布规则和原版平板完全一致。
     */
    private static void relayoutDirected(Object container, ClassLoader loader) {
        int orientation = forcedOrientation;
        if (orientation == 0) return;
        try {
            Object adapter = fieldOfType(container, "u1.c");
            if (adapter == null) return;
            int total = count(adapter);
            if (total < 3) return;
            if (total == 4) {
                // 四窗交给官方 N2()/M1() 链路，这里不插手
                return;
            }
            java.util.List<Object> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < total; i++) {
                Object task = XposedHelpers.callMethod(adapter, "getItem", i);
                if (task != null) tasks.add(task);
            }
            if (tasks.size() != 3) return;
            float density = android.content.res.Resources.getSystem()
                    .getDisplayMetrics().density;
            Object rects = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("B1.l", loader), "M1", tasks, orientation, density);
            if (!(rects instanceof java.util.List)) return;
            java.util.List<?> list = (java.util.List<?>) rects;
            if (list.size() != 3) {
                log("directed layout empty for " + orientation);
                return;
            }
            Object scale = XposedHelpers.getFloatField(container, "t");
            for (int i = 0; i < 3; i++) {
                Object decor = XposedHelpers.callMethod(container, "q0", i);
                if (!(decor instanceof android.view.View)) continue;
                Object rect = list.get(i);
                if (!(rect instanceof Rect)) continue;
                Rect r = new Rect((Rect) rect);
                Object task = tasks.get(i);
                try {
                    XposedHelpers.callMethod(decor, "k0", task,
                            XposedHelpers.callMethod(task, "o"), scale);
                } catch (Throwable ignored) {
                }
                ((android.view.View) decor).layout(r.left, r.top, r.right, r.bottom);
            }
            log("directed layout " + orientation + " -> " + list);
        } catch (Throwable error) {
            log("directed layout failed: " + error);
        }
    }

    /**
     * 三窗/四窗的调节条只允许点击，不允许拖动改比例。
     *
     * 点击走 E.p0()（弹菜单），拖动走 E.r0()（滚动回调里调），这里只掐 r0。
     */
    private static void hookDragLock(ClassLoader loader) {
        try {
            Class<?> bar = XposedHelpers.findClass(
                    "com.oplus.pscanvas.canvasmode.canvas.E", loader);
            Class<?> item = XposedHelpers.findClass(
                    "com.oplus.pscanvas.canvasmode.canvas.E$c", loader);
            XposedHelpers.findAndHookMethod(bar, "r0", item,
                    android.view.MotionEvent.class, android.view.MotionEvent.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object container = XposedHelpers.getObjectField(
                                        param.thisObject, "h");
                                Object adapter = fieldOfType(container, "u1.c");
                                if (adapter != null && count(adapter) >= 3) {
                                    param.setResult(null);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            // r0/s0 最后都落到 q0 去算新位置，这里再兜一层
            XposedHelpers.findAndHookMethod(bar, "q0", item,
                    float.class, float.class, float.class, float.class, boolean.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object container = XposedHelpers.getObjectField(
                                        param.thisObject, "h");
                                Object adapter = fieldOfType(container, "u1.c");
                                if (adapter != null && count(adapter) >= 3) {
                                    param.setResult(null);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            log("drag lock hooked");
        } catch (Throwable error) {
            log("drag lock hook failed: " + error);
        }
    }


    /**
     * 让 u1/d.o() 永远返回目标尺寸。
     *
     * 任务数据重建时 a 字段会被改回方形，M1 跟着算出方形窗口，
     * 就是那个重排。这里直接锁死返回值，布局指纹才不会变。
     */
    private static void hookTaskDataBounds(ClassLoader loader) {
        try {
            Class<?> taskData = XposedHelpers.findClass("u1.d", loader);
            XposedHelpers.findAndHookMethod(taskData, "o", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!multiWindowMode) return;
                    param.setResult(currentTarget());
                }
            });
            log("taskData bounds locked");
        } catch (Throwable error) {
            log("taskData bounds hook failed: " + error);
        }
    }

    // ---------------------------------------------------------------- 尺寸

    private static void hookFullscreen(ClassLoader loader) {
        try {
            Class<?> view = XposedHelpers.findClass(CONTAINER_VIEW, loader);
            XposedHelpers.findAndHookMethod(view, "N2", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    syncTaskData(param.thisObject);
                    oLock = multiWindowMode;
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    forcePhoneSize(param.thisObject);
                    broadcastState(true);
                    oLock = false;
                    if (forcedOrientation != 0) {
                        relayoutDirected(param.thisObject,
                                param.thisObject.getClass().getClassLoader());
                    }
                    if (multiWindowMode) dumpLayout(param.thisObject);
                    try {
                        Object adapter = fieldOfType(param.thisObject, "u1.c");
                        if (adapter != null && count(adapter) == 4) {
                            final Object canvas = param.thisObject;
                            if (!isFourZoomed(canvas)) {
                                new android.os.Handler(android.os.Looper.getMainLooper())
                                        .post(() -> fitFour(canvas, 0));
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedHelpers.findAndHookMethod(view, "layoutChildren", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        // 先同步缓存，任务数据重建时 bounds 被改回也能拉回来
                        syncTaskData(param.thisObject);
                        if (!multiWindowMode) return;
                        String key = layoutKey(param.thisObject);
                        String last = layoutKeys.get(param.thisObject);
                        if (!key.isEmpty() && key.equals(last)) {
                            // 布局参数没变，跳过这次重新摆放，画面不动
                            param.setResult(null);
                            skipped++;
                            if (skipped % 20 == 1) {
                                log("layoutChildren skipped " + skipped);
                            }
                            return;
                        }
                        layoutKeys.put(param.thisObject, key);
                    } catch (Throwable ignored) {
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (forcedOrientation == 0) return;
                    try {
                        Object adapter = fieldOfType(param.thisObject, "u1.c");
                        if (adapter == null) return;
                        if (count(adapter) == 4) return; // 四窗由 w1() 里的 N2() 处理
                        relayoutDirected(param.thisObject,
                                param.thisObject.getClass().getClassLoader());
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("fullscreen hook ready");
        } catch (Throwable error) {
            log("fullscreen hook failed: " + error);
        }
    }

    /**
     * 两窗自动放大：进入画布二分屏后，自动把焦点窗口放大到极限。
     *
     * 放大走系统自己的 c.m(container, task, true)（菜单"放大窗口"点的就是它），
     * c 是实例方法，构造只要 Context，m 内部会把 container 写进自己的 b 字段，
     * 因此 new 一个干净的实例调用即可，不碰菜单、不模拟点击、不改 bounds。
     *
     * 为什么 hook N2() 的 after：N2() 是画布两窗布局摆完的固定入口，比 hook
     * 菜单或坐标可靠。刚进来时 task 的 subview 可能还没齐，所以延迟 600ms 再放大，
     * 并只在"count 从非 2 变成 2"这一次触发；之后点小窗翻转、缩小都是系统原生，
     * 靠 twoAutoZoomed 记账保证绝不重复插手。
     */
    private static void hookAutoEnlargeTwo(ClassLoader loader) {
        try {
            Class<?> view = XposedHelpers.findClass(CONTAINER_VIEW, loader);
            // 坑：两窗布局走 O2()，三窗才走 N2()。原实现 hook N2 根本抓不到
            // "刚进入两窗"，所以自动放大从没触发过。改 hook O2()。
            XposedHelpers.findAndHookMethod(view, "O2", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        final Object container = param.thisObject;
                        Object adapter = fieldOfType(container, "u1.c");
                        if (adapter == null || count(adapter) != 2) return;
                        if (Boolean.TRUE.equals(twoAutoZoomed.get(container))) return;
                        if (Boolean.TRUE.equals(twoZoomPending.get(container))) return;
                        twoZoomPending.put(container, Boolean.TRUE);
                        log("auto enlarge two: O2 fired, scheduling");
                        // 等布局和 rectFList 就绪再放大，太早会报 rectFList is null。
                        // 原 900ms 体感偏慢，缩到 450ms；再短 O2 可能还没摆完。
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed(() -> {
                                    try {
                                        // 坑（已修）：三窗启动会先经过 O2 摆好前 2 个窗口，
                                        // 那时 count==2，如果直接放大就打断了第 3 个窗口加入，
                                        // 导致"一步起三窗"只起来 2 个。这里延迟后二次确认：
                                        // 窗口数必须仍然是 2 才是真二分屏，变 3+ 就跳过。
                                        Object adapterNow = fieldOfType(container, "u1.c");
                                        if (adapterNow == null || count(adapterNow) != 2) {
                                            log("auto enlarge two skipped: not 2 anymore (count="
                                                    + (adapterNow == null ? -1 : count(adapterNow))
                                                    + ")");
                                            return;
                                        }
                                        if (Boolean.TRUE.equals(
                                                twoAutoZoomed.get(container))) return;
                                        doAutoEnlargeTwo(container);
                                        twoAutoZoomed.put(container, Boolean.TRUE);
                                        log("auto enlarge two: done");
                                    } catch (Throwable error) {
                                        log("auto enlarge two failed: " + error);
                                    } finally {
                                        twoZoomPending.remove(container);
                                    }
                                }, 450);
                    } catch (Throwable error) {
                        log("auto enlarge two O2 failed: " + error);
                    }
                }
            });
            log("auto-enlarge-two hooked (O2)");
        } catch (Throwable error) {
            log("auto-enlarge-two hook failed: " + error);
        }
    }

    /** 调系统的 c.m(container, focusTask, true) 把焦点窗口放大到极限。 */
    private static void doAutoEnlargeTwo(Object container) {
        try {
            Object adapter = fieldOfType(container, "u1.c");
            if (adapter == null || count(adapter) != 2) return;
            Object task = null;
            int focus = 0;
            // 焦点位置：controller.n() 是系统 m() 里用的当前焦点下标
            try {
                Object controller = XposedHelpers.callMethod(
                        container, "getContainerController");
                Object n = XposedHelpers.callMethod(controller, "n");
                if (n instanceof Integer) focus = (Integer) n;
                if (focus >= 0 && focus < count(adapter)) {
                    task = XposedHelpers.callMethod(controller, "l", focus);
                }
            } catch (Throwable ignored) {
            }
            if (task == null) task = XposedHelpers.callMethod(adapter, "getItem", focus);
            if (task == null) task = XposedHelpers.callMethod(adapter, "getItem", 0);
            if (task == null) return;

            // 统一按 task 记账：这个窗口放大过就不再放大，无论触发来源是自动还是点击。
            // 这样"进两窗自动放大虎扑 → 焦点去酷安 → 回虎扑点击"不会再放大一次。
            if (Boolean.TRUE.equals(enlargedTasks.get(task))) {
                log("skip enlarge: task already enlarged");
                return;
            }
            enlargedTasks.put(task, Boolean.TRUE);

            android.content.Context ctx = ((android.view.View) container).getContext();
            ClassLoader loader = container.getClass().getClassLoader();
            Class<?> replacer = XposedHelpers.findClass(
                    "com.oplus.pscanvas.canvasmode.canvas.c", loader);
            Object instance = XposedHelpers.newInstance(replacer, ctx);
            XposedHelpers.callMethod(instance, "m", container, task, true);
            log("enlarged two-window task (focus=" + focus + ")");
        } catch (Throwable error) {
            log("do auto enlarge two failed: " + error);
        }
    }

    /** 应用内换页时任务信息会变，一有变化立刻重新套尺寸。 */
    private static void hookTaskInfo(ClassLoader loader) {
        try {
            Class<?> view = XposedHelpers.findClass(FLEXIBLE_TASK_VIEW, loader);
            XposedBridge.hookAllMethods(view, "onTaskInfoChanged", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object info = param.args[0];
                        Object bounds = XposedHelpers.callMethod(info, "getBounds");
                        Object launch = launchBoundsOf(param.thisObject);
                        Object activity = null;
                        try {
                            activity = XposedHelpers.callMethod(info, "getTopActivity");
                        } catch (Throwable ignored) {
                        }
                        log("taskChanged " + activity + " bounds=" + bounds
                                + " launch=" + launch);
                        Object container = containerOf(param.thisObject);
                        if (container == null) return;
                        Object finalContainer = container;
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .post(() -> {
                                    syncTaskData(finalContainer);
                                    forcePhoneSize(finalContainer);
                                });
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.hookAllMethods(view, "onTaskVanished", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object info = param.args[0];
                        log("taskVanished " + XposedHelpers.getIntField(info, "taskId"));
                    } catch (Throwable ignored) {
                    }
                    layoutKeys.clear();
                }
            });
            XposedBridge.hookAllMethods(view, "onTaskAppeared", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    layoutKeys.clear();
                    try {
                        Object info = param.args[0];
                        int id = XposedHelpers.getIntField(info, "taskId");
                        Object bounds = XposedHelpers.callMethod(info, "getBounds");
                        log("taskAppeared " + id + " bounds=" + bounds);
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("task info hooked");
        } catch (Throwable error) {
            log("task info hook failed: " + error);
        }
    }

    /** 画布可见性广播给侧边栏，侧边栏据此决定点应用是加画布还是开小窗。 */
    /** 当前画布里有多少个窗口，没有画布返回 -1。 */
    private static int currentCanvasCount() {
        for (Object container : containers.keySet()) {
            try {
                Object adapter = fieldOfType(container, "u1.c");
                if (adapter != null) return count(adapter);
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    /** 画布当前是否在前台，watchdog 心跳用它，别再固定报 true。 */
    private static volatile boolean canvasVisibleState = false;

    private static void broadcastState(boolean visible) {
        canvasVisibleState = visible;
        try {
            Object ctx = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication");
            if (!(ctx instanceof android.content.Context)) return;
            int count = currentCanvasCount();
            Intent state = new Intent("com.canvasplus.CANVAS_STATE");
            state.setPackage("com.coloros.smartsidebar");
            state.putExtra("visible", visible);
            state.putExtra("count", count);
            ((android.content.Context) ctx).sendBroadcast(state);
            // 坑（2026-11-19）：system_server 里的 Trigger 也要拿窗口数：
            // 两窗时让 FeatureUnlock 保持系统原生的手机布局策略，否则平板
            // 策略会把窗口压窄并留黑边。显式发 "android" 包，不能只靠
            // 智能侧边栏那条定向广播。
            Intent systemState = new Intent("com.canvasplus.CANVAS_STATE");
            systemState.setPackage("android");
            systemState.putExtra("visible", visible);
            systemState.putExtra("count", count);
            ((android.content.Context) ctx).sendBroadcast(systemState);
            log("canvas state " + visible + " count=" + count);
        } catch (Throwable error) {
            log("canvas state broadcast failed: " + error);
        }
    }

    /** 画布被切走再回来时任务已经恢复成全屏，重新套一遍。 */
    private static void hookResume(ClassLoader loader) {
        try {
            Class<?> activity = XposedHelpers.findClass(
                    "com.oplus.pscanvas.canvasmode.canvas.ContainerActivity", loader);
            XposedHelpers.findAndHookMethod(activity, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    broadcastState(true);
                    try {
                        Object view = XposedHelpers.callMethod(param.thisObject, "x0");
                        if (view == null) return;
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed(() -> {
                                    try {
                                        attempts = 0;
                                        syncTaskData(view);
                                        forcePhoneSize(view);
                                        XposedHelpers.callMethod(view, "N2");
                                        startWatchdog(view);
                                    } catch (Throwable error) {
                                        log("resume reapply failed: " + error);
                                    }
                                }, 800);
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedHelpers.findAndHookMethod(activity, "onPause", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    broadcastState(false);
                }
            });
            log("resume hooked");
        } catch (Throwable error) {
            log("resume hook failed: " + error);
        }
    }

    /** 点击窗口前刷新一次布局，保证 T() 拿到的矩形是最新的。 */
    private static void hookClick(ClassLoader loader) {
        try {
            Class<?> view = XposedHelpers.findClass(CONTAINER_VIEW, loader);
            XposedHelpers.findAndHookMethod(view, "T",
                    int.class, int.class, int.class, boolean.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            int index = (Integer) param.args[0];
                            int duration = (Integer) param.args[1];
                            boolean animate = (Boolean) param.args[3];
                            try {
                                Object adapter = fieldOfType(param.thisObject, "u1.c");
                                // onLayout 每次都自动聚焦缩放，四窗默认要保持四等分
                                if (adapter != null && count(adapter) == 4
                                        && duration == 0 && !isFourZoomed(param.thisObject)) {
                                    param.setResult(Boolean.FALSE);
                                    return;
                                }
                            } catch (Throwable ignored) {
                            }
                            if (index < 0 || duration != 450 || !animate) return;
                            try {
                                Object adapter = fieldOfType(param.thisObject, "u1.c");
                                int total = adapter == null ? 0 : count(adapter);
                                if (total == 4) {
                                    // 四窗点窗口就是放大：同步状态，菜单才会变成"缩小窗口"
                                    fourZoomed.put(param.thisObject, Boolean.TRUE);
                                    for (int i = 0; i < total; i++) {
                                        Object task = XposedHelpers.callMethod(
                                                adapter, "getItem", i);
                                        if (task != null) {
                                            XposedHelpers.callMethod(task, "p0", i == index);
                                        }
                                    }
                                    return;
                                }
                                // 坑：两窗点窗口绝不能调 N2()。N2 是三窗布局方法，
                                // 它会重建 rectFList；两窗的 rectFList 由系统 O2() 维护，
                                // 点小窗翻转走的 autoScale 要读它。这里调 N2 会把列表
                                // 清空/换成三窗矩形，导致 autoScale 报 "rectFList is null"、
                                // 点小窗无法翻转。
                                if (total == 2) {
                                    // 需求：点窗口就放大该窗口（含 50:50 时也放大），
                                    // 且每个窗口只在第一次放大——自动和点击共用 enlargedTasks，
                                    // 记账统一在 doAutoEnlargeTwo 内部按实际放大的 task 做，
                                    // 这里不再单独记账，避免"自动放大虎扑没记 task → 点击又放大"。
                                    // T() 会把焦点切到被点窗口，很短延迟等焦点切完即可。
                                    Object clickedTask = XposedHelpers.callMethod(
                                            adapter, "getItem", index);
                                    if (clickedTask == null) return;
                                    final Object clicked = param.thisObject;
                                    new android.os.Handler(android.os.Looper.getMainLooper())
                                            .postDelayed(() -> {
                                                try {
                                                    doAutoEnlargeTwo(clicked);
                                                    log("click-to-enlarge two done (index=" + index + ")");
                                                } catch (Throwable error) {
                                                    log("click-to-enlarge failed: " + error);
                                                }
                                            }, 120);
                                    return;
                                }
                                if (total < 3) return;
                                layoutKeys.remove(param.thisObject);
                                XposedHelpers.callMethod(param.thisObject, "N2");
                            } catch (Throwable error) {
                                log("click refresh failed: " + error);
                            }
                        }
                    });
            log("click hooked");
        } catch (Throwable error) {
            log("click hook failed: " + error);
        }
    }

    private static final java.util.WeakHashMap<Object, Boolean> watchdogs =
            new java.util.WeakHashMap<>();

    /** 定期检查任务尺寸，同时给侧边栏发心跳：只在画布可见时发。 */
    private static void startWatchdog(Object view) {
        if (watchdogs.containsKey(view)) return;
        watchdogs.put(view, Boolean.TRUE);
        final android.os.Handler handler =
                new android.os.Handler(android.os.Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    syncTaskData(view);
                    forcePhoneSize(view);
                    broadcastState(canvasShown(view));
                } catch (Throwable ignored) {
                }
                handler.postDelayed(this, 1000);
            }
        }, 500);
        log("watchdog started");
    }

    /**
     * 画布窗口当前是不是可见，决定侧边栏点应用是"加进画布"还是"开小窗"。
     *
     * 坑：原实现用 context.isVisible()，在本机（ColorOS 16 / PKR110）恒为 false，
     * 导致心跳永远 false、侧边栏永远当成"画布不在前台"，加第三个程序失效。
     * 改用 View.isShown()：它综合自身/父级 VISIBLE 状态和是否 attach 到 window，
     * 侧边栏悬浮窗盖上来时画布容器仍然 shown，切到别的应用才变 false。
     */
    private static boolean canvasShown(Object view) {
        try {
            if (!(view instanceof android.view.View)) return false;
            android.view.View v = (android.view.View) view;
            boolean shown = v.isShown();
            if (shown) return true;
            // 兜底：某些时机 isShown 还没跟上，用 windowVisibility + attach 状态补
            try {
                boolean attached = XposedHelpers.callMethod(v, "isAttachedToWindow")
                        instanceof Boolean
                        && (Boolean) XposedHelpers.callMethod(v, "isAttachedToWindow");
                Object wv = XposedHelpers.callMethod(v, "getWindowVisibility");
                if (attached && wv instanceof Integer && (Integer) wv == android.view.View.VISIBLE) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            return false;
        } catch (Throwable error) {
            return false;
        }
    }

    /** 三窗四窗都用整屏比例的任务，窗口大小由布局决定。 */
    private static Rect currentTarget() {
        return phoneSize();
    }

    private static volatile int windowCount = 0;

    /** 任务尺寸直接用屏幕可用区域，不乘任何系数。 */
    private static Rect phoneSize() {
        android.util.DisplayMetrics dm = android.content.res.Resources
                .getSystem().getDisplayMetrics();
        int availH = dm.heightPixels - statusBarHeight();
        return new Rect(0, 0, dm.widthPixels, availH);
    }


    private static int statusBarHeight() {
        try {
            android.content.res.Resources res = android.content.res.Resources.getSystem();
            int id = res.getIdentifier("status_bar_height", "dimen", "android");
            return id > 0 ? res.getDimensionPixelSize(id) : 0;
        } catch (Throwable error) {
            return 0;
        }
    }

    /** 布局前把 u1/d 缓存统一成目标尺寸。 */
    private static void syncTaskData(Object container) {
        try {
            Object adapter = fieldOfType(container, "u1.c");
            if (adapter == null) return;
            containers.put(container, Boolean.TRUE);
            int count = count(adapter);
            Integer last = taskCounts.get(container);
            boolean justLeftThree = last != null && last >= 3 && count < 3;
            if (last != null && last != count) {
                layoutKeys.remove(container);
            }
            taskCounts.put(container, count);
            windowCount = count;
            multiWindowMode = count == 3;
            if (count < 3) {
                // 只在从三窗退下来的那一次恢复，之后交给系统
                if (justLeftThree) restoreTwoSplit(container, adapter, count);
                return;
            }
            Rect target = currentTarget();
            for (int i = 0; i < count; i++) {
                updateTaskData(adapter, i, target);
            }
        } catch (Throwable error) {
            log("sync taskData failed: " + error);
        }
    }

    /** 两窗尺寸：和系统原版一致。 */
    private static Rect splitSize() {
        android.util.DisplayMetrics dm = android.content.res.Resources
                .getSystem().getDisplayMetrics();
        return new Rect(0, 0, dm.widthPixels, Math.round(dm.heightPixels * 0.4698f));
    }

    /** 退回两窗时把任务尺寸还原，避免继续按长条缩放。 */
    private static void restoreTwoSplit(Object container, Object adapter, int count) {
        try {
            if (count < 2) return;
            Rect target = splitSize();
            ClassLoader loader = container.getClass().getClassLoader();
            for (int i = 0; i < count; i++) {
                Object decor = XposedHelpers.callMethod(container, "q0", i);
                if (decor == null) continue;
                Object taskView = fieldOfTypeDeep(decor, FLEXIBLE_TASK_VIEW);
                if (taskView == null) continue;
                Object launch = launchBoundsOf(taskView);
                if (launch instanceof Rect
                        && ((Rect) launch).width() == target.width()
                        && ((Rect) launch).height() == target.height()) {
                    continue;
                }
                Object token = XposedHelpers.getObjectField(taskView, "mTaskToken");
                if (token == null) continue;
                try {
                    Object wct = XposedHelpers.newInstance(XposedHelpers.findClass(
                            "android.window.WindowContainerTransaction", loader));
                    XposedHelpers.callMethod(wct, "setBounds", token, target);
                    try {
                        XposedHelpers.callMethod(wct, "setAppBounds", token, target);
                    } catch (Throwable ignored) {
                    }
                    Object organizer = XposedHelpers.callMethod(
                            taskView, "getTaskOrganizer");
                    if (organizer != null) {
                        XposedHelpers.callMethod(organizer, "applyTransaction", wct);
                    }
                    setLaunchBounds(taskView, target);
                    updateTaskData(adapter, i, target);
                    log("restore two split " + target.width() + "x" + target.height());
                } catch (Throwable error) {
                    log("restore two split failed: " + error);
                }
            }
        } catch (Throwable error) {
            log("restore pass failed: " + error);
        }
    }

    private static void forcePhoneSize(Object container) {
        startWatchdog(container);
        boolean pending = false;
        boolean changed = false;
        try {
            Object adapter = fieldOfType(container, "u1.c");
            if (adapter == null) return;
            int count = count(adapter);
            windowCount = count;
            multiWindowMode = count == 3;
            if (count < 3) return;

            Rect target = currentTarget();
            ClassLoader loader = container.getClass().getClassLoader();

            for (int i = 0; i < count; i++) {
                Object decor = XposedHelpers.callMethod(container, "q0", i);
                if (decor == null) { pending = true; continue; }
                Object taskView = fieldOfTypeDeep(decor, FLEXIBLE_TASK_VIEW);
                if (taskView == null) { pending = true; continue; }

                int taskId = -1;
                try {
                    Object info = XposedHelpers.getObjectField(taskView, "mTaskInfo");
                    if (info != null) taskId = XposedHelpers.getIntField(info, "taskId");
                } catch (Throwable ignored) {
                }
                if (taskId <= 0) { pending = true; continue; }

                Object token = XposedHelpers.getObjectField(taskView, "mTaskToken");
                if (token == null) { pending = true; continue; }

                Object launch = launchBoundsOf(taskView);
                if (launch instanceof Rect
                        && ((Rect) launch).width() == target.width()
                        && ((Rect) launch).height() == target.height()) {
                    continue;
                }

                changed = true;
                try {
                    Object wct = XposedHelpers.newInstance(XposedHelpers.findClass(
                            "android.window.WindowContainerTransaction", loader));
                    XposedHelpers.callMethod(wct, "setBounds", token, target);
                    try {
                        XposedHelpers.callMethod(wct, "setAppBounds", token, target);
                    } catch (Throwable ignored) {
                    }
                    Object organizer = XposedHelpers.callMethod(
                            taskView, "getTaskOrganizer");
                    if (organizer != null) {
                        XposedHelpers.callMethod(organizer, "applyTransaction", wct);
                    }
                    setLaunchBounds(taskView, target);
                    updateTaskData(adapter, i, target);
                    log("phone size " + taskId + " -> " + target.width() + "x" + target.height());
                } catch (Throwable error) {
                    log("phone size " + taskId + " failed: " + error);
                }
            }
        } catch (Throwable error) {
            log("phone size pass failed: " + error);
        }

        if (changed && !relayoutScheduled) {
            relayoutScheduled = true;
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> {
                        try {
                            XposedHelpers.callMethod(container, "N2");
                        } catch (Throwable error) {
                            log("relayout failed: " + error);
                        } finally {
                            relayoutScheduled = false;
                        }
                    }, 900);
        }

        if (pending && attempts < 8) {
            attempts++;
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> forcePhoneSize(container), 700);
        } else if (!pending) {
            attempts = 0;
        }
    }

    private static Object containerOf(Object view) {
        try {
            Object context = XposedHelpers.callMethod(view, "getContext");
            Object activity = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("B1.l",
                            view.getClass().getClassLoader()), "O1", context);
            if (activity == null) return null;
            return XposedHelpers.callMethod(activity, "x0");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object launchBoundsOf(Object taskView) {
        try {
            java.lang.reflect.Field field = taskView.getClass()
                    .getDeclaredField("mLaunchBounds");
            field.setAccessible(true);
            return field.get(taskView);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void setLaunchBounds(Object taskView, Rect target) {
        try {
            java.lang.reflect.Field field = taskView.getClass()
                    .getDeclaredField("mLaunchBounds");
            field.setAccessible(true);
            field.set(taskView, new Rect(target));
        } catch (Throwable ignored) {
        }
    }

    /** M1 用的是 u1/d 里缓存的 bounds，改完任务要同步它。 */
    private static void updateTaskData(Object adapter, int index, Rect target) {
        try {
            Object taskData = XposedHelpers.callMethod(adapter, "getItem", index);
            if (taskData == null) return;
            java.lang.reflect.Field field = taskData.getClass().getDeclaredField("a");
            field.setAccessible(true);
            field.set(taskData, new Rect(target));
        } catch (Throwable error) {
            log("update taskData failed: " + error);
        }
    }

    // ---------------------------------------------------------------- 原版策略

    private static void useTabletPolicy(ClassLoader loader) {
        try {
            Class<?> selector = XposedHelpers.findClass(POLICY_SELECTOR, loader);
            XposedHelpers.findAndHookMethod(selector, "b", android.content.Context.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                // 两窗保持原版 PhonePolicy，不能动原版二分屏
                                if (!multiWindowMode) return;
                                Object tablet = fieldOfType(param.thisObject, TABLET_POLICY);
                                if (tablet != null) {
                                    param.setResult(tablet);
                                    log("layout policy -> tablet");
                                }
                            } catch (Throwable error) {
                                log("tablet policy switch failed: " + error);
                            }
                        }
                    });
            log("policy selector hooked");
        } catch (Throwable error) {
            log("policy selector unavailable: " + error);
        }
    }

    private static void forceSplitMode(ClassLoader loader) {
        try {
            Class<?> policy = XposedHelpers.findClass(TABLET_POLICY, loader);
            XposedBridge.hookAllMethods(policy, "c", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object adapter = param.args[0];
                        int count = count(adapter);
                        if (count != 3) return;
                        int mode;
                        if (forcedMode >= 3 && forcedMode <= 7) {
                            mode = forcedMode;
                        } else {
                            Object context = XposedHelpers.callMethod(adapter, "getContext");
                            int orientation = ((android.content.Context) context).getResources()
                                    .getConfiguration().orientation;
                            mode = orientation
                                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                                    ? 6 : 4;
                        }
                        param.setResult(mode);
                        log("split mode for " + count + " windows -> " + mode);
                    } catch (Throwable error) {
                        log("split mode failed: " + error);
                    }
                }
            });
            log("split mode hooked");
        } catch (Throwable error) {
            log("split mode unavailable: " + error);
        }
    }

    private static void allowMoreThanThree(ClassLoader loader) {
        try {
            Class<?> view = XposedHelpers.findClass(CONTAINER_VIEW, loader);
            XposedHelpers.findAndHookMethod(view, "i1", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object adapter = fieldOfType(param.thisObject, "u1.c");
                        if (adapter == null) return;
                        int count = count(adapter);
                        if (count > 3) {
                            param.setResult(Boolean.TRUE);
                            log("canvas accepts " + count + " windows");
                        }
                    } catch (Throwable error) {
                        log("canvas count check failed: " + error);
                    }
                }
            });
            log("container view hooked");
        } catch (Throwable error) {
            log("container view unavailable: " + error);
        }
    }

    private static void ignoreLineLayout(ClassLoader loader) {
        try {
            Class<?> adapter = XposedHelpers.findClass("u1.c", loader);
            XposedBridge.hookAllMethods(adapter, "j", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(Boolean.FALSE);
                }
            });
            log("line layout disabled");
        } catch (Throwable error) {
            log("line layout unavailable: " + error);
        }
    }

    // ---------------------------------------------------------------- 工具

    private static int count(Object adapter) {
        try {
            Object value = XposedHelpers.callMethod(adapter, "getCount");
            return value instanceof Integer ? (Integer) value : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static Object fieldOfType(Object owner, String className) {
        for (java.lang.reflect.Field field : owner.getClass().getDeclaredFields()) {
            if (!field.getType().getName().equals(className)) continue;
            try {
                field.setAccessible(true);
                return field.get(owner);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object fieldOfTypeDeep(Object owner, String className) {
        Class<?> type = owner.getClass();
        while (type != null && type != Object.class) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (!field.getType().getName().equals(className)) continue;
                try {
                    field.setAccessible(true);
                    return field.get(owner);
                } catch (Throwable ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    /** 只打日志：多个窗口在屏幕上到底落在哪，圆角是不是被裁。 */
    /** 只打关键信息：窗口数、每个窗口的布局矩形。 */
    private static void dumpLayout(Object view) {
        try {
            Object adapter = fieldOfType(view, "u1.c");
            int total = adapter == null ? 0 : count(adapter);
            if (total == lastDumpedCount) return;
            lastDumpedCount = total;
            StringBuilder sb = new StringBuilder("layout windows=").append(total);
            for (int i = 0; i < total; i++) {
                Object decor = XposedHelpers.callMethod(view, "q0", i);
                if (!(decor instanceof android.view.View)) continue;
                android.view.View d = (android.view.View) decor;
                sb.append(" [").append(d.getLeft()).append(",").append(d.getTop())
                        .append(",").append(d.getRight()).append(",")
                        .append(d.getBottom()).append("]");
            }
            log(sb.toString());
        } catch (Throwable error) {
            log("dump failed: " + error);
        }
    }

    private static volatile int lastDumpedCount = -1;
}
