package com.qukan.hook;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static MainHook instance;
    public static MainHook getInstance() { return instance; }

    private static final String TARGET_PKG = "top.dffhq.qwsh";

    private static final long LOOP_INTERVAL_MS  = 30_000;
    private static final long SHOW_DELAY_MS     = 2_000;
    private static final long RETRY_DELAY_MS    = 10_000;
    private static final int  MAX_ERRORS        = 5;

    // 延迟初始化 Handler — 类加载时 Looper 可能为空
    private Handler handler;
    private Handler h() {
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        return handler;
    }

    private WeakReference<Activity> activityRef;
    private WeakReference<Object>   adManagerRef;
    private WeakReference<Object>   fragmentRef;   // RedPacketFragment instance
    public WeakReference<Object> getFragmentRef() { return fragmentRef; }
    private Object                  adListener;    // 强引用 — 防止 g() 实例被 GC
    private Object                  lastAdInfo;    // 从 onVideoAdLoadSuccess 捕获的 AdInfo（含真实 loadId）
    private int     roundCount = 0;
    private int     errorCount = 0;
    private boolean running    = false;
    private volatile Object lastWaterfall;  // 从 V6 响应中捕获的 Waterfall 对象

    // 开关: 跳过广告视频
    public static volatile boolean skipAdEnabled = true;

    // 开关: 拦截非激励广告
    public static volatile boolean blockNonRewardAds = true;

    // Custom credentials for account replacement
    public static volatile String customOaid = null;
    public static volatile String customToken = null;
    public static volatile String customUserJson = null;
    public static volatile String lastPackageInfo = null;  // 提现 package_info

    // To access Context for SharedPreferences
    public static volatile android.content.Context appContext;
    public static volatile ClassLoader appClassLoader;

    public static void saveCreds(String oaid, String token, String userJson) {
        if (oaid != null) oaid = oaid.trim().replace("\"", "");
        if (token != null) token = token.trim().replace("\"", "");
        if (userJson != null) userJson = userJson.replace("&quot;", "\""); // Fix XML escaped quotes

        customOaid = oaid;
        customToken = token;
        customUserJson = userJson;
        if (appContext != null) {
            appContext.getSharedPreferences("hook_creds", 0).edit()
                    .putString("oaid", oaid)
                    .putString("token", token)
                    .putString("user_json", userJson)
                    .apply();

            // 暴力直插：直接把完整的 JSON 写入 App 自己存登录信息的 SharedPreferences 中！
            if (userJson != null && !userJson.isEmpty()) {
                appContext.getSharedPreferences("video_advert_sing", 0).edit()
                        .putString("USER_LOGIN_ENTITY", userJson)
                        .putBoolean("app_user_login", true)
                        .apply();
                LogServer.log("[Inject] 已原生注入 USER_LOGIN_ENTITY 缓存并强行置位已登录");
            }

            LogServer.log("[Config] 凭证已持久化写入本地文件！");
        } else {
            LogServer.log("[Error] 无法持久化：Context为空，请先在手机上打开一次 App 界面！");
        }
    }

    private volatile boolean toastShown      = false;
    private volatile boolean serverInitDone  = false;

    // ======================================================================
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PKG.equals(lpparam.packageName)) return;
        instance = this;
        XposedBridge.log("QukanHook >>> 已加载: " + lpparam.packageName);

        ClassLoader cl = lpparam.classLoader;

        // Start log server immediately (same pattern as working test.java)
        if (!serverInitDone) {
            serverInitDone = true;
            LogServer.start();
            LogServer.setTriggerCallback(new LogServer.TriggerCallback() {
                @Override public String trigger() { return triggerOnce(); }
                @Override public String status() {
                    Activity a = activityRef != null ? activityRef.get() : null;
                    return "{\"version\":\"1.9\",\"running\":" + running
                        + ",\"round\":"    + roundCount
                        + ",\"errors\":"   + errorCount
                        + ",\"actOk\":"    + (a != null && !a.isFinishing())
                        + ",\"adOk\":"     + (adManagerRef != null && adManagerRef.get() != null)
                        + ",\"skipAd\":"   + skipAdEnabled
                        + ",\"blockAds\":" + blockNonRewardAds + "}";
                }
            });
            XposedBridge.log("QukanHook server started on port " + LogServer.actualPort);
        }

        // Capture Application Context as early as possible
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", cl, "onCreate", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (appContext == null) {
                        appContext = (android.content.Context) param.thisObject;
                        appClassLoader = param.thisObject.getClass().getClassLoader();
                        // 1. 先从 hook_creds 恢复我们自己存的凭证
                        android.content.SharedPreferences sp = appContext.getSharedPreferences("hook_creds", 0);
                        String diskOaid = sp.getString("oaid", null);
                        if (customOaid == null && diskOaid != null) {
                            customOaid = diskOaid;
                            customToken = sp.getString("token", null);
                            customUserJson = sp.getString("user_json", null);
                            LogServer.log("[Config] 已加载持久化凭证: OAID=" + customOaid);
                        }
                        // 2. 如果仍然没有 Token，尝试从 App 的原生 SP 中读取
                        if (customToken == null || customToken.isEmpty()) {
                            try {
                                android.content.SharedPreferences appSp = appContext.getSharedPreferences("video_advert_sing", 0);
                                String userJson = appSp.getString("USER_LOGIN_ENTITY", null);
                                if (userJson != null && !userJson.isEmpty()) {
                                    org.json.JSONObject uj = new org.json.JSONObject(userJson);
                                    String appToken = uj.optString("access_token", "");
                                    if (!appToken.isEmpty()) {
                                        customToken = appToken;
                                        LogServer.log("[AutoToken] ★ 从 App SP 自动读取到 Token: " + appToken.substring(0, Math.min(20, appToken.length())) + "...");
                                    }
                                }
                            } catch (Exception e) {
                                LogServer.log("[AutoToken] 从 App SP 读取 Token 失败: " + e.getMessage());
                            }
                        }
                    }
                }
            });
        } catch (Throwable ignored) {}

        hookToast(cl);
        hookCustomCredentials(cl);
        hookDeviceFingerprint(cl);
        hookBlockNonRewardAds(cl);
        hookAdManager(cl);
        hookShowLoadRewardVideo(cl);
        hookRandomizeAdInfo(cl);
        hookSigmobAdActivity(cl);     // 跳过广告视频 + 快速触发奖励
        hookRewardAdErrors(cl);
        hookRedPacketReward(cl);
    }

    // ======================================================================
    // Hook 0: Activity.onCreate/onResume — show Toast to confirm injection
    // ======================================================================
    private void hookToast(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", cl, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            String name = activity.getClass().getName();
                            if (!name.startsWith("com.windmill") && !name.startsWith("com.czhj") 
                                    && !name.startsWith("com.sigmob") && !name.startsWith("com.qq.e") 
                                    && !name.startsWith("com.bytedance") && !name.startsWith("com.kwad")) {
                                activityRef = new WeakReference<>(activity);
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod("android.app.Activity", cl, "onCreate",
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            String name = activity.getClass().getName();
                            if (!name.startsWith("com.windmill") && !name.startsWith("com.czhj") 
                                    && !name.startsWith("com.sigmob") && !name.startsWith("com.qq.e") 
                                    && !name.startsWith("com.bytedance") && !name.startsWith("com.kwad")) {
                                activityRef = new WeakReference<>(activity);
                            }

                            if (!toastShown) {
                                toastShown = true;
                                String ip   = LogServer.getLocalIp();
                                int    port = LogServer.actualPort;
                                Toast.makeText(activity,
                                        "趣玩Hook已激活 - http://" + ip + ":" + port,
                                        Toast.LENGTH_LONG).show();
                            }
                        }
                    });
            XposedBridge.log("QukanHook [Hook] Activity.onCreate 注册成功");
        } catch (Throwable t) {
            XposedBridge.log("QukanHook [Hook] Activity.onCreate 注册失败: " + t);
        }
    }

    // ======================================================================
    // Hook 0b: 拦截所有非激励广告（插屏/Banner/信息流/开屏）
    // 仅允许奖励视频广告 (WMRewardAd) 通过
    // ======================================================================
    private void hookBlockNonRewardAds(ClassLoader cl) {
        // 拦截所有插屏广告：在 show() 阶段直接阻止展示，伪造完整回调链
        try {
            Class<?> interstitialCls = XposedHelpers.findClass("com.windmill.sdk.interstitial.WMInterstitialAd", cl);

            XposedHelpers.findAndHookMethod(interstitialCls, "show",
                    Activity.class, java.util.HashMap.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!blockNonRewardAds) return;
                            try {
                                Object adInfo = XposedHelpers.callMethod(param.thisObject, "getAdInfo");
                                String networkName = adInfo != null
                                        ? (String) XposedHelpers.callMethod(adInfo, "getNetworkName") : "unknown";
                                LogServer.log("[AdBlock] ⛔ 插屏广告被拦截: network=" + networkName);

                                // 阻止 show() 执行
                                param.setResult(false);

                                // 伪造回调链，避免 App 卡死
                                Object listener = XposedHelpers.getObjectField(param.thisObject, "wmInterstitialAdListener");
                                if (listener != null && adInfo != null) {
                                    try {
                                        XposedHelpers.callMethod(listener, "onInterstitialAdPlayStart", adInfo);
                                    } catch (Throwable ignored) {}
                                    final Object fListener = listener;
                                    final Object fAdInfo = adInfo;
                                    h().postDelayed(() -> {
                                        try {
                                            XposedHelpers.callMethod(fListener, "onInterstitialAdClosed", fAdInfo);
                                            LogServer.log("[AdBlock] ✓ 回调链完成 (PlayStart→Closed)");
                                        } catch (Throwable ignored) {}
                                    }, 300);
                                }
                            } catch (Throwable t) {
                                LogServer.log("[AdBlock] 拦截异常: " + t.getMessage());
                            }
                        }
                    });
            LogServer.log("[AdBlock] ★ 插屏广告拦截 Hook 注册成功");
        } catch (Throwable t) {
            LogServer.log("[AdBlock] 插屏广告 Hook 失败: " + t.getMessage());
        }
    }


    // ======================================================================
    // Hook 1: l.u(Activity, WMRewardAdListener) — 捕获引用 — 捕获 Activity + AdManager + 监听器
    // ======================================================================
    private void hookAdManager(ClassLoader cl) {
        try {
            Class<?> clsL = XposedHelpers.findClass("com.example.advertisinglibrary.ad.l", cl);
            XposedHelpers.findAndHookMethod(clsL, "u",
                    Activity.class,
                    XposedHelpers.findClass("com.windmill.sdk.reward.WMRewardAdListener", cl),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            activityRef   = new WeakReference<>((Activity) param.args[0]);
                            adManagerRef  = new WeakReference<>(param.thisObject);
                            adListener    = param.args[1]; // strong ref — keep g() alive
                            LogServer.log("[AdMgr] 已捕获 Activity + AdManager + 广告监听器");
                        }
                    });
            XposedBridge.log("QukanHook [Hook] l.u OK");
        } catch (Throwable t) {
            XposedBridge.log("QukanHook [Hook] l.u 注册失败: " + t);
        }
    }

    // ======================================================================
    // Hook 1b: RedPacketFragment 实例捕获 — 捕获 Fragment 实例
    // 红包页面触发广告的真实入口
    // ======================================================================
    private void hookShowLoadRewardVideo(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.example.advertisinglibrary.fragment.RedPacketFragment", cl,
                    "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            fragmentRef = new WeakReference<>(param.thisObject);
                            LogServer.log("[Fragment] 已捕获 RedPacketFragment (onResume)");
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("QukanHook [Hook] RedPacketFragment.onResume 注册失败: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.example.advertisinglibrary.fragment.RedPacketFragment", cl,
                    "showLoadRewardVideo",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            fragmentRef = new WeakReference<>(param.thisObject);
                            LogServer.log("[Fragment] 已捕获 RedPacketFragment");
                        }
                    });
            XposedBridge.log("QukanHook [Hook] showLoadRewardVideo 注册成功");
        } catch (Throwable t) {
            XposedBridge.log("QukanHook [Hook] showLoadRewardVideo 注册失败: " + t);
        }
    }


    // ======================================================================
    // Hook 2b: 每次提交时随机化 extraInfo
    // 防止服务端检测到重复/重放请求
    // ======================================================================
    private void hookRandomizeAdInfo(ClassLoader cl) {
        try {
            Class<?> adInfoCls = XposedHelpers.findClass("com.windmill.sdk.models.AdInfo", cl);

            // 仅在 fillData 时随机化 extraInfo，避免 getOptions() 被 SDK 内部多次调用时重复替换
            XposedHelpers.findAndHookMethod(adInfoCls, "fillData",
                    XposedHelpers.findClass("com.windmill.sdk.WindMillAdRequest", cl),
                    new XC_MethodHook() {
                @Override
                @SuppressWarnings("unchecked")
                protected void afterHookedMethod(MethodHookParam param) {
                    java.util.Map<String, Object> options = (java.util.Map<String, Object>)
                            XposedHelpers.callMethod(param.thisObject, "getOptions");
                    if (options != null && options.containsKey("extraInfo")) {
                        // 原始格式: UUID.randomUUID().toString().replaceAll("-", "")
                        String newEinfo = java.util.UUID.randomUUID().toString().replace("-", "");
                        options.put("extraInfo", newEinfo);
                    }
                }
            });

            LogServer.log("[Hook] extraInfo 随机化 Hook 注册成功");
            XposedBridge.log("QukanHook [Hook] extraInfo随机化 注册成功");
        } catch (Throwable t) {
            LogServer.log("[Hook] extraInfo 随机化 Hook 失败: " + t.getMessage());
            XposedBridge.log("QukanHook [Hook] extraInfo随机化 注册失败: " + t);
        }
    }
    // ======================================================================
    // Hook 3: 奖励广告加载后，在 onVideoAdPlayStart 触发时
    // 立即触发奖励回调链 + 关闭广告
    // ======================================================================
    private void hookSigmobAdActivity(ClassLoader cl) {
        try {
            Class<?> wmRewardAdCls = XposedHelpers.findClass("com.windmill.sdk.reward.WMRewardAd", cl);
            Class<?> adInfoCls = XposedHelpers.findClass("com.windmill.sdk.models.AdInfo", cl);


            // Hook onVideoAdPlayStart(): 广告真正开始播放后，立即触发奖励回调 + 关闭
            XposedHelpers.findAndHookMethod(wmRewardAdCls, "onVideoAdPlayStart",
                    adInfoCls, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!skipAdEnabled) return;

                            final Object wmRewardAd = param.thisObject;
                            final Object adInfo = param.args[0];
                            LogServer.log("[Skip] ★ 广告已加载播放，开始快速完成奖励...");

                            // 延迟 1 秒后触发奖励（让 SDK 内部初始化完成）
                            h().postDelayed(() -> {
                                try {
                                    Object controller = XposedHelpers.getObjectField(wmRewardAd, "controller");

                                    // ---- 发送 rtbcallback 给 Sigmob（与之前逻辑一致） ----
                                    if (controller != null) {
                                        try {
                                            Object aVar = null;
                                            for (java.lang.reflect.Field f : controller.getClass().getSuperclass().getDeclaredFields()) {
                                                f.setAccessible(true);
                                                Object val = f.get(controller);
                                                if (val != null && val.getClass().getName().equals("com.windmill.sdk.strategy.a")) {
                                                    aVar = val;
                                                    break;
                                                }
                                            }
                                            if (aVar == null) {
                                                for (java.lang.reflect.Field f : controller.getClass().getSuperclass().getDeclaredFields()) {
                                                    f.setAccessible(true);
                                                    Object val = f.get(controller);
                                                    if (val instanceof java.util.List) {
                                                        java.util.List<?> list = (java.util.List<?>) val;
                                                        if (!list.isEmpty() && list.get(0).getClass().getName().equals("com.windmill.sdk.strategy.a")) {
                                                            aVar = list.get(0);
                                                            break;
                                                        }
                                                    }
                                                }
                                            }
                                            if (aVar != null) {
                                                java.lang.reflect.Method iMethod = controller.getClass().getDeclaredMethod("i", aVar.getClass());
                                                iMethod.setAccessible(true);
                                                org.json.JSONObject rewardJson = (org.json.JSONObject) iMethod.invoke(controller, aVar);
                                                rewardJson.put("networkAdType", "1");
                                                rewardJson.put("thirdTransId", java.util.UUID.randomUUID().toString().replace("-", ""));
                                                rewardJson.put("rewardTimestamp", String.valueOf(System.currentTimeMillis()));

                                                String rvUrl = (String) XposedHelpers.callMethod(aVar, "ag");
                                                String queryStr = null;
                                                if (rvUrl != null && !rvUrl.isEmpty()) {
                                                    try {
                                                        Class<?> sdkCfgCls = XposedHelpers.findClass("com.windmill.sdk.strategy.WMSdkConfig", cl);
                                                        queryStr = (String) XposedHelpers.callStaticMethod(sdkCfgCls, "getServerQueryString");
                                                    } catch (Throwable t2) {
                                                        LogServer.log("[Skip] getServerQueryString 失败，跳过 rtbcallback");
                                                    }
                                                }
                                                if (rvUrl != null && !rvUrl.isEmpty() && queryStr != null) {
                                                    String fullUrl = rvUrl + (rvUrl.contains("?") ? "&" : "?") + queryStr;

                                                    Object windMillAdReq = null;
                                                    for (java.lang.reflect.Field f : controller.getClass().getSuperclass().getDeclaredFields()) {
                                                        f.setAccessible(true);
                                                        try {
                                                            Object v = f.get(controller);
                                                            if (v != null && v.getClass().getName().contains("WindMillAdRequest")) {
                                                                windMillAdReq = v;
                                                                break;
                                                            }
                                                        } catch (Throwable ig) {}
                                                    }

                                                    Class<?> jCls = XposedHelpers.findClass("com.windmill.sdk.strategy.j", cl);
                                                    Class<?> jCallbackCls = XposedHelpers.findClass("com.windmill.sdk.strategy.j$a", cl);
                                                    Object callback = java.lang.reflect.Proxy.newProxyInstance(cl,
                                                            new Class<?>[]{jCallbackCls},
                                                            (proxy, method, args) -> {
                                                                if (method.getName().equals("a") && (args == null || args.length == 0)) {
                                                                    LogServer.log("[Skip] ✅ rtbcallback 奖励回调成功");
                                                                } else if (method.getName().equals("a") && args != null && args.length > 0) {
                                                                    LogServer.log("[Skip] ✗ rtbcallback 奖励回调失败: " + args[0]);
                                                                }
                                                                return null;
                                                            });
                                                    XposedHelpers.callStaticMethod(jCls, "a", fullUrl, rewardJson.toString(), 2, "", windMillAdReq, callback);
                                                    LogServer.log("[Skip] rtbcallback 已发送");
                                                }
                                            }
                                        } catch (Throwable t) {
                                            LogServer.log("[Skip] rtbcallback 异常: " + t.getMessage());
                                        }
                                    }

                                    // ---- 触发 WM 层奖励回调 ----
                                    Class<?> riCls = XposedHelpers.findClass("com.windmill.sdk.reward.WMRewardInfo", cl);
                                    Object ri = riCls.getConstructor(boolean.class, String.class, String.class, String.class)
                                            .newInstance(true, java.util.UUID.randomUUID().toString(), "", "");
                                    XposedHelpers.callMethod(wmRewardAd, "onVideoAdReward", adInfo, ri);
                                    LogServer.log("[Skip] ✓ onVideoAdReward 已触发");

                                    // ---- 300ms 后触发播放结束 ----
                                    h().postDelayed(() -> {
                                        try {
                                            XposedHelpers.callMethod(wmRewardAd, "onVideoAdPlayEnd", adInfo);
                                            LogServer.log("[Skip] ✓ onVideoAdPlayEnd 已触发");

                                            // ---- 300ms 后关闭广告并触发 close 回调 ----
                                            h().postDelayed(() -> {
                                                try {
                                                    XposedHelpers.callMethod(wmRewardAd, "onVideoAdClosed", adInfo);
                                                    LogServer.log("[Skip] ✓ onVideoAdClosed 已触发");

                                                    // 关闭所有 SDK 广告 Activity
                                                    closeAdActivities();
                                                } catch (Throwable ignored) {}
                                            }, 300);
                                        } catch (Throwable ignored) {}
                                    }, 300);
                                } catch (Throwable t) {
                                    LogServer.log("[Skip] 快速完成异常: " + t.getMessage());
                                }
                            }, 1000);
                        }
                    });

            LogServer.log("[Hook] 奖励广告跳过 Hook 注册成功");
            XposedBridge.log("QukanHook [Hook] 奖励广告跳过 注册成功");
        } catch (Throwable t) {
            LogServer.log("[Hook] 奖励广告跳过 Hook 失败: " + t.getMessage());
            XposedBridge.log("QukanHook [Hook] 奖励广告跳过 注册失败: " + t);
        }
    }

    /**
     * 关闭所有 SDK 广告 Activity（Windmill/Sigmob/CZHJ）
     */
    private void closeAdActivities() {
        try {
            // 通过 ActivityThread 获取所有运行中的 Activity
            Object activityThread = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentActivityThread");
            java.util.Map<?, ?> activities = (java.util.Map<?, ?>)
                    XposedHelpers.getObjectField(activityThread, "mActivities");
            if (activities == null) return;
            for (Object record : activities.values()) {
                Activity act = (Activity) XposedHelpers.getObjectField(record, "activity");
                if (act != null && !act.isFinishing()) {
                    String name = act.getClass().getName();
                    if (name.startsWith("com.windmill") || name.startsWith("com.czhj")
                            || name.startsWith("com.sigmob") || name.startsWith("com.bytedance")
                            || name.startsWith("com.kwad") || name.startsWith("com.baidu")
                            || name.startsWith("com.qq.e") || name.startsWith("com.pangle")
                            || name.startsWith("com.volcengine")) {
                        LogServer.log("[Skip] 关闭广告 Activity: " + name);
                        act.finish();
                    }
                }
            }
        } catch (Throwable t) {
            LogServer.log("[Skip] 关闭广告 Activity 异常: " + t.getMessage());
        }
    }


    // ======================================================================
    // Hook: 凭证注入（OAID、Token、UserDataEntity）
    // ======================================================================
    private void hookCustomCredentials(ClassLoader cl) {
        // Hook OAID 读取
        try {
            Class<?> bClass = XposedHelpers.findClass("x2.b", cl);
            XposedHelpers.findAndHookMethod(bClass, "i", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    if (customOaid != null && !customOaid.isEmpty()) {
                        return customOaid;
                    }
                    return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                }
            });
            XposedBridge.log("QukanHook [Hook] hookCustomCredentials (OAID) OK");
        } catch (Throwable t) {
            XposedBridge.log("QukanHook [Hook] hookCustomCredentials (OAID) FAIL: " + t);
        }

        // Hook Token — 双向：捕获 + 替换
        try {
            Class<?> tokenMgrClass = XposedHelpers.findClass("com.example.advertisinglibrary.util.TokenManager", cl);
            XposedHelpers.findAndHookMethod(tokenMgrClass, "getAccessToken", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String origToken = (String) param.getResult();
                    // 如果我们有自定义 Token，使用自定义的
                    if (customToken != null && !customToken.isEmpty()) {
                        param.setResult(customToken);
                    } else if (origToken != null && !origToken.isEmpty()) {
                        // 如果没有自定义 Token 但 App 有，自动捕获
                        customToken = origToken;
                        LogServer.log("[AutoToken] ★ 从 TokenManager 捕获到 Token: " + origToken.substring(0, Math.min(20, origToken.length())) + "...");
                    }
                }
            });
            XposedBridge.log("QukanHook [Hook] hookCustomCredentials (Token Capture+Replace) OK");
        } catch (Throwable t) {
            XposedBridge.log("QukanHook [Hook] hookCustomCredentials (Token) FAIL: " + t);
        }

        // Hook i4.b.a (Global Retrofit Header Setter) — 捕获 + 替换 Token
        try {
            Class<?> i4bClass = XposedHelpers.findClass("i4.b", cl);
            XposedHelpers.findAndHookMethod(i4bClass, "a", String.class, String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    if ("Authorization".equals(key)) {
                        String headerVal = (String) param.args[1];
                        // 自动捕获：如果还没有 Token 但 Header 中有
                        if ((customToken == null || customToken.isEmpty()) && headerVal != null && headerVal.startsWith("Bearer ")) {
                            String captured = headerVal.substring(7).trim();
                            if (!captured.isEmpty()) {
                                customToken = captured;
                                LogServer.log("[AutoToken] ★ 从 HTTP Header 捕获到 Token: " + captured.substring(0, Math.min(20, captured.length())) + "...");
                            }
                        }
                        // 替换：如果有自定义 Token，强制使用
                        if (customToken != null && !customToken.isEmpty()) {
                            String cleanToken = customToken.trim().replace("\"", "");
                            param.args[1] = "Bearer " + cleanToken;
                        }
                    }
                }
            });
            XposedBridge.log("QukanHook [Hook] hookCustomCredentials (i4.b.a Header Capture+Inject) OK");
        } catch (Throwable t) {
            XposedBridge.log("QukanHook [Hook] hookCustomCredentials (i4.b.a Header Inject) FAIL: " + t);
        }

        // Hook z.n() to bypass Login Screen (Fallback)
        try {
            Class<?> zClass = XposedHelpers.findClass("com.example.advertisinglibrary.util.z", cl);
            XposedHelpers.findAndHookMethod(zClass, "n", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    if (customUserJson != null && !customUserJson.isEmpty()) {
                        try {
                            Class<?> qClass = XposedHelpers.findClass("com.example.advertisinglibrary.util.q", cl);
                            Object gson = XposedHelpers.callStaticMethod(qClass, "a");
                            Class<?> userClass = XposedHelpers.findClass("com.example.advertisinglibrary.bean.UserDataEntity", cl);
                            Object entity = XposedHelpers.callMethod(gson, "fromJson", customUserJson, userClass);
                            if (entity != null) return entity;
                        } catch (Exception e) {
                            LogServer.log("[Error] z.n 注入失败，JSON格式有误: " + e.getMessage());
                        }
                    }
                    return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                }
            });
            XposedBridge.log("QukanHook [Hook] hookCustomCredentials (z.n) OK");
        } catch (Throwable t) {
            XposedBridge.log("QukanHook [Hook] 凭证注入 (z.n) 注册失败: " + t);
        }

        // Hook z.x(UserDataEntity) — 用户信息同步 — when App refreshes user info from server,
        // 同步更新本地缓存
        try {
            Class<?> zClass = XposedHelpers.findClass("com.example.advertisinglibrary.util.z", cl);
            Class<?> userClass = XposedHelpers.findClass("com.example.advertisinglibrary.bean.UserDataEntity", cl);
            XposedHelpers.findAndHookMethod(zClass, "x", userClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object userEntity = param.args[0];
                    if (userEntity == null) return;
                    try {
                        // 仅在刷新成功时更新（token + user 非空）
                        String token = (String) XposedHelpers.callMethod(userEntity, "getAccess_token");
                        Object user = XposedHelpers.callMethod(userEntity, "getUser");
                        if (token == null || token.isEmpty() || user == null) {
                            LogServer.log("[Refresh] 跳过: token或user为空，非成功刷新");
                            return;
                        }

                        Class<?> qClass = XposedHelpers.findClass("com.example.advertisinglibrary.util.q", cl);
                        String freshJson = (String) XposedHelpers.callStaticMethod(
                                qClass, "c", userEntity, null, 1, null);
                        if (freshJson != null && !freshJson.isEmpty()) {
                            customUserJson = freshJson;
                            // 检查 package_info
                            try {
                                Object pkgInfo = XposedHelpers.callMethod(user, "getPackage_info");
                                if (pkgInfo != null && !pkgInfo.toString().equals("null") && !pkgInfo.toString().isEmpty()) {
                                    lastPackageInfo = pkgInfo.toString();
                                    LogServer.log("[Refresh] 💰 package_info 已捕获: " + lastPackageInfo.substring(0, Math.min(40, lastPackageInfo.length())) + "...");
                                }
                            } catch (Throwable ignored) {
                                // getPackage_info 可能不存在, try field access
                                try {
                                    org.json.JSONObject uj = new org.json.JSONObject(freshJson);
                                    if (uj.has("user")) {
                                        org.json.JSONObject u = uj.getJSONObject("user");
                                        if (u.has("package_info") && !u.isNull("package_info")) {
                                            lastPackageInfo = u.getString("package_info");
                                            LogServer.log("[Refresh] 💰 package_info (JSON): " + lastPackageInfo.substring(0, Math.min(40, lastPackageInfo.length())) + "...");
                                        }
                                    }
                                } catch (Throwable ig2) {}
                            }
                            LogServer.log("[Refresh] ✅ UserDataEntity 已更新! token=" + token.substring(0, Math.min(8, token.length())) + "...");
                            // Persist to disk too
                            if (appContext != null) {
                                appContext.getSharedPreferences("hook_creds", 0).edit()
                                        .putString("user_json", freshJson)
                                        .apply();
                                appContext.getSharedPreferences("video_advert_sing", 0).edit()
                                        .putString("USER_LOGIN_ENTITY", freshJson)
                                        .apply();
                            }
                        }
                    } catch (Throwable t) {
                        LogServer.log("[Refresh] UserDataEntity 序列化失败: " + t.getMessage());
                    }
                }
            });
            XposedBridge.log("QukanHook [Hook] z.x (UserEntity refresh sync) OK");
        } catch (Throwable t) {
            XposedBridge.log("QukanHook [Hook] z.x FAIL: " + t);
        }
    }

    // ======================================================================
    // Hook 8: load/play errors
    // ======================================================================
    private void hookRewardAdErrors(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass("com.windmill.sdk.reward.WMRewardAd", cl);
            XC_MethodHook errHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    errorCount++;
                    LogServer.log("[Error] " + param.method.getName()
                            + " errors=" + errorCount + "/" + MAX_ERRORS);
                    if (errorCount >= MAX_ERRORS) {
                        running = false;
                        LogServer.log("[STOP] 错误次数过多，自动循环已停止");
                        return;
                    }
                    h().postDelayed(MainHook.this::scheduleNextRound, RETRY_DELAY_MS);
                }
            };

            Class<?> windMillErrorCls = XposedHelpers.findClass("com.windmill.sdk.WindMillError", cl);
            XposedHelpers.findAndHookMethod(cls, "onVideoAdLoadFail",  windMillErrorCls, String.class, errHook);
            XposedHelpers.findAndHookMethod(cls, "onVideoAdPlayError", windMillErrorCls, String.class, errHook);
            XposedBridge.log("QukanHook [Hook] 错误监听 注册成功");
        } catch (Throwable t) {
            XposedBridge.log("QukanHook [Hook] 错误监听 注册失败: " + t);
        }
    }

    // ======================================================================
    // 调度下一轮: 反射调用 fragment.showLoadRewardVideo()
    // 与用户手动点击红包相同的代码路径
    // ======================================================================
    private void scheduleNextRound() {
        if (!running) return;
        Object fragment = fragmentRef != null ? fragmentRef.get() : null;
        if (fragment == null) {
            // 降级: Fragment 引用丢失，直接调用 l.u()
            Activity act     = activityRef  != null ? activityRef.get()  : null;
            Object adManager = adManagerRef != null ? adManagerRef.get() : null;
            Object listener  = adListener;
            if (act == null || act.isFinishing() || adManager == null || listener == null) {
                LogServer.log("[Next] 所有引用已失效，停止循环");
                running = false;
                return;
            }
            try {
                Method u = adManager.getClass().getMethod("u", Activity.class,
                        listener.getClass().getInterfaces()[0]);
                u.invoke(adManager, act, listener);
                LogServer.log("[Next] 降级调用 l.u() 成功");
            } catch (Throwable t) {
                LogServer.log("[Next] 失败: " + t.getMessage());
                running = false;
            }
            return;
        }
        try {
            // 步骤1: 调用 getMVM().postAdreWards() — submits accumulated AdPostTypeBean list
            // 提交到 GetAdrewardCoins.ashx（与用户手动点击红包相同）
            
            try {
                Method getMVM = fragment.getClass().getMethod("getMVM");
                getMVM.setAccessible(true);
                Object viewModel = getMVM.invoke(fragment);
                if (viewModel != null) {
                    Method postAdreWards = viewModel.getClass().getDeclaredMethod("postAdreWards");
                    postAdreWards.setAccessible(true);
                    postAdreWards.invoke(viewModel);
                    LogServer.log("[Next] ✅ postAdreWards() called → GetAdrewardCoins.ashx");
                }
            } catch (Throwable t) {
                LogServer.log("[Next] postAdreWards 跳过: " + t.getMessage());
            }

            // 步骤2: 加载并展示奖励视频
            Method m = fragment.getClass().getDeclaredMethod("showLoadRewardVideo");
            m.setAccessible(true);
            m.invoke(fragment);
            LogServer.log("[Next] showLoadRewardVideo() 已调用");
        } catch (Throwable t) {
            LogServer.log("[Next] showLoadRewardVideo 失败: " + t.getMessage());
            running = false;
        }
    }

    // ======================================================================
    // 通过 HTTP /trigger 端点触发一轮
    // ======================================================================
    private String triggerOnce() {
        Object fragment = fragmentRef != null ? fragmentRef.get() : null;
        Activity act    = activityRef != null ? activityRef.get() : null;

        // 降级1: 从 Fragment 恢复 Activity
        if (act == null || act.isFinishing()) {
            if (fragment != null) {
                try {
                    act = (Activity) XposedHelpers.callMethod(fragment, "getActivity");
                    if (act != null && !act.isFinishing()) {
                        activityRef = new WeakReference<>(act);
                        LogServer.log("[Trigger] 从 RedPacketFragment 恢复了 Activity");
                    }
                } catch (Throwable ignored) {}
            }
        }

        // 降级2: 动态搜索 RedPacketFragment
        if (fragment == null && act != null && !act.isFinishing()) {
            try {
                Object fragmentManager = XposedHelpers.callMethod(act, "getSupportFragmentManager");
                java.util.List<?> fragments = (java.util.List<?>) XposedHelpers.callMethod(fragmentManager, "getFragments");
                for (Object f : fragments) {
                    if (f != null && f.getClass().getName().contains("RedPacketFragment")) {
                        fragment = f;
                        fragmentRef = new WeakReference<>(fragment);
                        LogServer.log("[Trigger] 动态找到 RedPacketFragment");
                        break;
                    }
                }
            } catch (Throwable t) {
                LogServer.log("[Trigger] 搜索 Fragment 失败: " + t.getMessage());
            }
        }


        if (act == null || act.isFinishing())
            return "失败: Activity 未就绪 — 请先打开红包页面";
        if (fragment == null)
            return "失败: RedPacketFragment 未就绪 — 请先打开红包页面";
            
        if (!running) { running = true; roundCount = 0; errorCount = 0; }
        h().post(this::scheduleNextRound);
        return "已触发第 " + (roundCount + 1) + " 轮 showLoadRewardVideo()";
    }

    // ======================================================================
    // 首次手动触发广告时激活自动循环
    // ======================================================================
    private void activateAutoLoop() {
        running    = true;
        roundCount = 0;
        errorCount = 0;
        Activity act = activityRef != null ? activityRef.get() : null;
        if (act != null) {
            h().post(() -> Toast.makeText(act.getApplicationContext(),
                    "自动循环已激活! 每 " + LOOP_INTERVAL_MS/1000 + "s",
                    Toast.LENGTH_SHORT).show());
        }
        LogServer.log("=== 自动循环已激活, 间隔=" + LOOP_INTERVAL_MS/1000 + "s ===");
    }

    // ======================================================================
    // Hook: 设备指纹伪造
    // 基于 customOaid 确定性生成 ANDROID_ID/IMEI/MAC
    // ======================================================================
    private String getFakeFingerprint(String original, String prefix) {
        if (customOaid == null || customOaid.isEmpty()) return original;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] array = md.digest((customOaid + prefix).getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : array) {
                sb.append(Integer.toHexString((b & 0xFF) | 0x100).substring(1, 3));
            }
            if ("imei".equals(prefix)) {
                StringBuilder numSb = new StringBuilder();
                for (char c : sb.toString().toCharArray()) {
                    if (c >= '0' && c <= '9') numSb.append(c);
                    else numSb.append((c - 'a') % 10);
                }
                return numSb.toString().substring(0, 15);
            }
            if ("mac".equals(prefix)) {
                String hex = sb.toString();
                return String.format("%s:%s:%s:%s:%s:%s",
                        hex.substring(0, 2), hex.substring(2, 4), hex.substring(4, 6),
                        hex.substring(6, 8), hex.substring(8, 10), hex.substring(10, 12));
            }
            if ("android_id".equals(prefix)) {
                return sb.toString().substring(0, 16);
            }
            return sb.toString();
        } catch (Exception e) {
            return original;
        }
    }

    private void hookDeviceFingerprint(ClassLoader cl) {
        try {
            // 1. Settings.Secure.getString (ANDROID_ID)
            XposedHelpers.findAndHookMethod("android.provider.Settings$Secure", cl, "getString",
                    android.content.ContentResolver.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String key = (String) param.args[1];
                            if ("android_id".equals(key)) {
                                String fake = getFakeFingerprint((String) param.getResult(), "android_id");
                                param.setResult(fake);
                                // Don't log every time to avoid spamming the console
                            }
                        }
                    });

            // 2. TelephonyManager (IMEI / MEID)
            XC_MethodHook imeiHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String fake = getFakeFingerprint((String) param.getResult(), "imei");
                    param.setResult(fake);
                }
            };
            try { XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", cl, "getDeviceId", imeiHook); } catch (Throwable ignored) {}
            try { XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", cl, "getDeviceId", int.class, imeiHook); } catch (Throwable ignored) {}
            try { XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", cl, "getImei", imeiHook); } catch (Throwable ignored) {}
            try { XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", cl, "getImei", int.class, imeiHook); } catch (Throwable ignored) {}
            try { XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", cl, "getMeid", imeiHook); } catch (Throwable ignored) {}
            try { XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", cl, "getMeid", int.class, imeiHook); } catch (Throwable ignored) {}

            // 3. WifiInfo (MAC)
            try {
                XposedHelpers.findAndHookMethod("android.net.wifi.WifiInfo", cl, "getMacAddress", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String fake = getFakeFingerprint((String) param.getResult(), "mac");
                        param.setResult(fake);
                    }
                });
            } catch (Throwable ignored) {}

            XposedBridge.log("QukanHook [Hook] 设备指纹伪造 注册成功");
        } catch (Throwable t) {
            XposedBridge.log("QukanHook [Hook] 设备指纹伪造 注册失败: " + t);
        }
    }

    // ======================================================================
    // Hook: 红包奖励回调 RedPacketFragment$g.onVideoRewarded
    // postAdreWardsReceive() + GetAdrewardCoins.ashx 在此触发
    // 此回调触发后表示奖励已发放 → 调度下一轮
    // ======================================================================
    private void hookRedPacketReward(ClassLoader cl) {
        try {
            // R8 混淆后的内部类名
            Class<?> cls       = XposedHelpers.findClass("com.example.advertisinglibrary.fragment.RedPacketFragment$g", cl);
            Class<?> adInfoCls = XposedHelpers.findClass("com.windmill.sdk.models.AdInfo", cl);
            Class<?> rewardCls = XposedHelpers.findClass("com.windmill.sdk.reward.WMRewardInfo", cl);

            XposedHelpers.findAndHookMethod(cls, "onVideoRewarded", adInfoCls, rewardCls,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            roundCount++;
                            errorCount = 0;
                            LogServer.log("★ [RedPacket.g] onVideoRewarded #" + roundCount
                                    + " — postAdreWardsReceive + GetAdrewardCoins.ashx fired by App");
                            // 仅手动触发，不自动调度
                        }
                    });
            XposedBridge.log("QukanHook [Hook] 红包奖励回调 注册成功");
        } catch (Throwable t) {
            XposedBridge.log("QukanHook [Hook] 红包奖励回调 注册失败: " + t);
        }
    }
}
