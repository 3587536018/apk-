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

    private static final String TARGET_PKG = "top.dffhq.qwsh";

    private static final long LOOP_INTERVAL_MS  = 30_000;
    private static final long SHOW_DELAY_MS     = 2_000;
    private static final long RETRY_DELAY_MS    = 10_000;
    private static final int  MAX_ERRORS        = 5;

    // Lazy Handler - DO NOT initialize at field level, Looper may be null at class-load time
    private Handler handler;
    private Handler h() {
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        return handler;
    }

    private WeakReference<Activity> activityRef;
    private WeakReference<Object>   adManagerRef;
    private WeakReference<Object>   fragmentRef;   // RedPacketFragment instance
    private Object                  adListener;    // strong ref — prevents GC of new g() instance
    private Object                  lastAdInfo;    // AdInfo captured from onVideoAdLoadSuccess (has real loadId)
    private int     roundCount = 0;
    private int     errorCount = 0;
    private boolean running    = false;
    private volatile Object lastWaterfall;  // Captured Waterfall object from V6 response

    // Toggle: skip ad video (true=skip, false=play normally)
    public static volatile boolean skipAdEnabled = true;

    // Toggle: block non-reward ads (true=block banner/interstitial/splash/feed, false=allow all)
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
        XposedBridge.log("QukanHook >>> loaded for: " + lpparam.packageName);

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
                            LogServer.log("[Config] Loaded persisted creds: OAID=" + customOaid);
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
        hookDeviceFingerprint(cl); // Fake Android ID, IMEI, MAC based on OAID
        hookBlockNonRewardAds(cl); // 拦截非激励广告（插屏/Banner/信息流/开屏）
        hookAdManager(cl);
        hookShowLoadRewardVideo(cl);
        hookRewardAdLoadSuccess(cl);
        hookRandomizeAdInfo(cl);    // Random loadId per submission
        hookSigmobAdActivity(cl);
        hookAdWindowTransparent(cl);  // make ad Activity transparent + muted
        hookRewardAdPlayStart(cl);
        hookRewardAdReward(cl);
        hookRewardAdPlayEnd(cl);
        hookRewardAdClosed(cl);
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
                                        "QukanHook v1.12 Active - http://" + ip + ":" + port,
                                        Toast.LENGTH_LONG).show();
                            }
                        }
                    });
            XposedBridge.log("QukanHook [Hook] Activity.onCreate OK");
        } catch (Throwable t) {
            XposedBridge.log("QukanHook [Hook] Activity.onCreate FAIL: " + t);
        }
    }

    // ======================================================================
    // Hook 0b: Block all non-reward ads (interstitial, banner, native feed, splash)
    // Only reward video ads (WMRewardAd) are allowed through.
    // ======================================================================
    private void hookBlockNonRewardAds(ClassLoader cl) {
        int hooked = 0;

        // --- 插屏广告：正常展示，几秒后自动关闭 ---
        try {
            Class<?> interstitialCls = XposedHelpers.findClass("com.windmill.sdk.interstitial.WMInterstitialAd", cl);
            Class<?> adInfoCls = XposedHelpers.findClass("com.windmill.sdk.models.AdInfo", cl);

            // Hook onVideoAdPlayStart：插屏开始展示后延迟几秒自动关闭
            XposedHelpers.findAndHookMethod(interstitialCls, "onVideoAdPlayStart",
                    adInfoCls, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!blockNonRewardAds) return;
                            final Object interstitialAd = param.thisObject;
                            final Object adInfo = param.args[0];
                            LogServer.log("[AdAuto] 插屏广告已展示，5 秒后自动关闭");

                            h().postDelayed(() -> {
                                try {
                                    // 插屏广告是 Dialog/View 叠加，不是独立 Activity
                                    // 直接调用 onVideoAdClosed 触发完整关闭回调链
                                    java.lang.reflect.Method closedMethod = interstitialAd.getClass()
                                            .getMethod("onVideoAdClosed", adInfo.getClass());
                                    closedMethod.invoke(interstitialAd, adInfo);
                                    LogServer.log("[AdAuto] ✓ 已调用 onVideoAdClosed 关闭插屏");
                                } catch (Throwable t) {
                                    LogServer.log("[AdAuto] 插屏自动关闭异常: " + t.getMessage());
                                }
                            }, 5000);
                        }
                    });
            hooked++;
            LogServer.log("[AdAuto] 插屏广告自动关闭 Hook 注册成功");
        } catch (Throwable t) {
            LogServer.log("[AdAuto] 插屏广告 Hook 失败: " + t.getMessage());
        }

        // --- 开屏广告：正常展示，几秒后自动关闭 ---
        try {
            Class<?> splashCls = XposedHelpers.findClass("com.windmill.sdk.splash.WMSplashAd", cl);
            XposedHelpers.findAndHookMethod(splashCls, "loadAdAndShow", android.view.ViewGroup.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!blockNonRewardAds) return;
                            LogServer.log("[AdAuto] 开屏广告加载中，3 秒后自动关闭");
                            h().postDelayed(() -> {
                                try {
                                    closeAdActivities();
                                    LogServer.log("[AdAuto] ✓ 开屏广告已自动关闭");
                                } catch (Throwable t) {
                                    LogServer.log("[AdAuto] 开屏自动关闭异常: " + t.getMessage());
                                }
                            }, 3000);
                        }
                    });
            hooked++;
            LogServer.log("[AdAuto] 开屏广告自动关闭 Hook 注册成功");
        } catch (Throwable t) {
            LogServer.log("[AdAuto] 开屏广告 Hook 失败: " + t.getMessage());
        }

        LogServer.log("[AdAuto] ★ 广告自动关闭初始化完成，共注册 " + hooked + " 个 Hook");
        XposedBridge.log("QukanHook [AdAuto] Registered " + hooked + " auto-close hooks");
    }

    // ======================================================================
    // Hook 1: l.u(Activity, WMRewardAdListener) — capture Activity + AdManager + Listener
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
                            LogServer.log("[AdMgr] Captured Activity + l + WMRewardAdListener");
                        }
                    });
            XposedBridge.log("QukanHook [Hook] l.u OK");
        } catch (Throwable t) {
            XposedBridge.log("QukanHook [Hook] l.u FAIL: " + t);
        }
    }

    // ======================================================================
    // Hook 1b: RedPacketFragment.showLoadRewardVideo() & onResume — capture fragment instance
    // This is the real entry point for triggering a reward ad in RedPacketFragment.
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
                            LogServer.log("[Fragment] RedPacketFragment captured in onResume");
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("QukanHook [Hook] RedPacketFragment.onResume FAIL: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.example.advertisinglibrary.fragment.RedPacketFragment", cl,
                    "showLoadRewardVideo",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            fragmentRef = new WeakReference<>(param.thisObject);
                            LogServer.log("[Fragment] RedPacketFragment captured");
                        }
                    });
            XposedBridge.log("QukanHook [Hook] showLoadRewardVideo OK");
        } catch (Throwable t) {
            XposedBridge.log("QukanHook [Hook] showLoadRewardVideo FAIL: " + t);
        }
    }

    // ======================================================================
    // Hook 2: WMRewardAd.onVideoAdLoadSuccess
    // ======================================================================
    private void hookRewardAdLoadSuccess(ClassLoader cl) {
        try {
            Class<?> cls       = XposedHelpers.findClass("com.windmill.sdk.reward.WMRewardAd", cl);
            XposedHelpers.findAndHookMethod(cls, "onVideoAdLoadSuccess", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            LogServer.log("[Load] Ad loaded successfully");
                            // Manual only - no auto show
                        }
                    });
            XposedBridge.log("QukanHook [Hook] onVideoAdLoadSuccess OK");
        } catch (Throwable t) {
            XposedBridge.log("QukanHook [Hook] onVideoAdLoadSuccess FAIL: " + t);
        }
    }

    // ======================================================================
    // Hook 2b: Randomize loadId and extraInfo on every ad reward submission
    // Prevents server from detecting duplicate/replayed requests
    // ======================================================================
    private void hookRandomizeAdInfo(ClassLoader cl) {
        try {
            Class<?> adInfoCls = XposedHelpers.findClass("com.windmill.sdk.models.AdInfo", cl);

            // Randomize extraInfo inside options map (avoid duplicate detection)
            // NOTE: loadId is NOT randomized — must stay consistent with aVar.aB() used in rtbcallback
            XposedHelpers.findAndHookMethod(adInfoCls, "getOptions", new XC_MethodHook() {
                @Override
                @SuppressWarnings("unchecked")
                protected void afterHookedMethod(MethodHookParam param) {
                    java.util.Map<String, Object> options = (java.util.Map<String, Object>) param.getResult();
                    if (options != null && options.containsKey("extraInfo")) {
                        String newEinfo = java.util.UUID.randomUUID().toString().replace("-", "")
                                + "_" + System.currentTimeMillis();
                        options.put("extraInfo", newEinfo);
                    }
                }
            });

            LogServer.log("[Hook] hookRandomizeAdInfo (einfo only, loadId unchanged) OK");
            XposedBridge.log("QukanHook [Hook] hookRandomizeAdInfo OK");
        } catch (Throwable t) {
            LogServer.log("[Hook] hookRandomizeAdInfo FAIL: " + t.getMessage());
            XposedBridge.log("QukanHook [Hook] hookRandomizeAdInfo FAIL: " + t);
        }
    }
    // ======================================================================
    // Hook 3: Let WMRewardAd.show() proceed (ad loads fully), then when
    // onVideoAdPlayStart fires, immediately trigger reward callbacks + close
    // ======================================================================
    private void hookSigmobAdActivity(ClassLoader cl) {
        try {
            Class<?> wmRewardAdCls = XposedHelpers.findClass("com.windmill.sdk.reward.WMRewardAd", cl);
            Class<?> adInfoCls = XposedHelpers.findClass("com.windmill.sdk.models.AdInfo", cl);

            // Hook show(): 让广告正常加载，但记录 wmRewardAd 实例，透明化窗口
            XposedHelpers.findAndHookMethod(wmRewardAdCls, "show",
                    Activity.class, java.util.HashMap.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!skipAdEnabled) {
                                LogServer.log("[Skip] 跳过广告已关闭，正常播放视频");
                                return;
                            }
                            LogServer.log("[Skip] ★ WMRewardAd.show() → 允许加载，准备快速关闭");
                            // 不阻止 show()，让广告正常加载
                        }
                    });

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
                                                if (rvUrl != null && !rvUrl.isEmpty()) {
                                                    String queryStr = "";
                                                    try {
                                                        Class<?> sdkCfgCls = XposedHelpers.findClass("com.windmill.sdk.strategy.WMSdkConfig", cl);
                                                        queryStr = (String) XposedHelpers.callStaticMethod(sdkCfgCls, "getServerQueryString");
                                                    } catch (Throwable ignored) {
                                                        queryStr = "appId=72850&sdkVersion=4.7.0";
                                                    }
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
                                                                    LogServer.log("[Skip] ✅ rtbcallback reward SUCCESS");
                                                                } else if (method.getName().equals("a") && args != null && args.length > 0) {
                                                                    LogServer.log("[Skip] ✗ rtbcallback reward ERROR: " + args[0]);
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

            LogServer.log("[Hook] hookWMRewardAdShow (加载+快速关闭) registered OK");
            XposedBridge.log("QukanHook [Hook] hookWMRewardAdShow OK");
        } catch (Throwable t) {
            LogServer.log("[Hook] hookWMRewardAdShow FAIL: " + t.getMessage());
            XposedBridge.log("QukanHook [Hook] hookWMRewardAdShow FAIL: " + t);
        }
    }

    /**
     * 查找当前最顶层的非 App Activity（即广告 Activity）
     * 排除 App 自身的 Activity，返回最后一个（最顶层）
     */
    private Activity findForegroundNonAppActivity() {
        try {
            Object activityThread = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentActivityThread");
            java.util.Map<?, ?> activities = (java.util.Map<?, ?>)
                    XposedHelpers.getObjectField(activityThread, "mActivities");
            if (activities == null) return null;

            Activity candidate = null;
            StringBuilder debugList = new StringBuilder("[AdAuto] 当前 Activity 列表:");
            for (Object record : activities.values()) {
                Activity act = (Activity) XposedHelpers.getObjectField(record, "activity");
                if (act == null || act.isFinishing()) continue;
                String name = act.getClass().getName();
                debugList.append(" [").append(name).append("]");
                // 排除 App 自身的 Activity
                if (!name.startsWith("com.example.advertisinglibrary")) {
                    candidate = act;
                }
            }
            LogServer.log(debugList.toString());
            return candidate;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 查找当前最顶层的广告 SDK Activity
     * 匹配所有已知广告 SDK 包名
     */
    private Activity findTopAdActivity() {
        try {
            Object activityThread = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentActivityThread");
            java.util.Map<?, ?> activities = (java.util.Map<?, ?>)
                    XposedHelpers.getObjectField(activityThread, "mActivities");
            if (activities == null) return null;
            Activity topAd = null;
            for (Object record : activities.values()) {
                Activity act = (Activity) XposedHelpers.getObjectField(record, "activity");
                if (act != null && !act.isFinishing()) {
                    String name = act.getClass().getName();
                    if (isAdActivity(name)) {
                        topAd = act;
                    }
                }
            }
            return topAd;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 判断是否为广告 SDK 的 Activity
     */
    private boolean isAdActivity(String className) {
        return className.startsWith("com.windmill")
            || className.startsWith("com.czhj")
            || className.startsWith("com.sigmob")
            || className.startsWith("com.bytedance")
            || className.startsWith("com.byted")
            || className.startsWith("com.pangle")
            || className.startsWith("com.kuaishou")
            || className.startsWith("com.kwad")
            || className.startsWith("com.kwai")
            || className.startsWith("com.baidu")
            || className.startsWith("com.tencent")
            || className.startsWith("com.qq.e.")
            || className.startsWith("com.volcengine")
            || className.startsWith("com.hihonor")
            || className.startsWith("com.huawei");
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
                            || name.startsWith("com.sigmob")) {
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
    // Hook 3b: Make Windmill/Sigmob reward video Activity transparent + silent
    // The video still PLAYS (server verifies) but user sees through it
    // ======================================================================
    private void hookAdWindowTransparent(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onWindowFocusChanged", boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            boolean hasFocus = (boolean) param.args[0];
                            if (!hasFocus || !running) return;
                            Activity act = (Activity) param.thisObject;
                            String pkg = act.getClass().getName();
                            // Only target Windmill/Sigmob/czhj SDK activities
                            if (!pkg.startsWith("com.windmill") && !pkg.startsWith("com.czhj")
                                    && !pkg.startsWith("com.sigmob")) return;
                            LogServer.log("[AdWindow] Windmill activity detected: " + pkg + " — making transparent");
                            try {
                                // Set window alpha to 0 (invisible but still foreground → video plays)
                                android.view.Window w = act.getWindow();
                                w.setDimAmount(0f);
                                android.view.WindowManager.LayoutParams lp = w.getAttributes();
                                lp.alpha = 0f;          // fully transparent
                                lp.screenBrightness = 0.01f; // dim screen
                                w.setAttributes(lp);
                                // Mute audio via AudioManager
                                android.media.AudioManager am = (android.media.AudioManager)
                                        act.getSystemService(android.content.Context.AUDIO_SERVICE);
                                if (am != null) am.adjustStreamVolume(
                                        android.media.AudioManager.STREAM_MUSIC,
                                        android.media.AudioManager.ADJUST_MUTE, 0);
                                LogServer.log("[AdWindow] Transparent + muted ✅");
                            } catch (Throwable t) {
                                LogServer.log("[AdWindow] FAIL: " + t.getMessage());
                            }
                        }
                    });
            XposedBridge.log("QukanHook [Hook] hookAdWindowTransparent OK");
        } catch (Throwable t) {
            XposedBridge.log("QukanHook [Hook] hookAdWindowTransparent FAIL: " + t);
        }
    }

    // ======================================================================
    // Hook 4: WMRewardAd.onVideoAdPlayStart
    // ======================================================================
    private void hookRewardAdPlayStart(ClassLoader cl) {
        try {
            Class<?> cls       = XposedHelpers.findClass("com.windmill.sdk.reward.WMRewardAd", cl);
            Class<?> adInfoCls = XposedHelpers.findClass("com.windmill.sdk.models.AdInfo", cl);
            XposedHelpers.findAndHookMethod(cls, "onVideoAdPlayStart", adInfoCls, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            LogServer.log("[Play] Video playing, waiting for reward...");
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("QukanHook [Hook] onVideoAdPlayStart FAIL: " + t);
        }
    }

    // ======================================================================
    // Hook 5: WMRewardAd.onVideoAdReward — reward arrived, schedule next round
    // ======================================================================
    private void hookRewardAdReward(ClassLoader cl) {
        try {
            Class<?> cls          = XposedHelpers.findClass("com.windmill.sdk.reward.WMRewardAd", cl);
            Class<?> adInfoCls    = XposedHelpers.findClass("com.windmill.sdk.models.AdInfo", cl);
            Class<?> rewardInfoCls= XposedHelpers.findClass("com.windmill.sdk.reward.WMRewardInfo", cl);
            XposedHelpers.findAndHookMethod(cls, "onVideoAdReward",
                    adInfoCls, rewardInfoCls,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            // roundCount tracked by hookRedPacketReward — just log here
                            LogServer.log("[Reward] WMRewardAd.onVideoAdReward fired (SDK level)");
                        }
                    });
            XposedBridge.log("QukanHook [Hook] onVideoAdReward OK");
        } catch (Throwable t) {
            XposedBridge.log("QukanHook [Hook] onVideoAdReward FAIL: " + t);
        }
    }

    // ======================================================================
    // Hook 6: WMRewardAd.onVideoAdPlayEnd
    // ======================================================================
    private void hookRewardAdPlayEnd(ClassLoader cl) {
        try {
            Class<?> cls       = XposedHelpers.findClass("com.windmill.sdk.reward.WMRewardAd", cl);
            Class<?> adInfoCls = XposedHelpers.findClass("com.windmill.sdk.models.AdInfo", cl);
            XposedHelpers.findAndHookMethod(cls, "onVideoAdPlayEnd", adInfoCls,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            LogServer.log("[PlayEnd] Video ended");
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("QukanHook [Hook] onVideoAdPlayEnd FAIL: " + t);
        }
    }

    // ======================================================================
    // Hook 7: WMRewardAd.onVideoAdClosed
    // ======================================================================
    private void hookRewardAdClosed(ClassLoader cl) {
        try {
            Class<?> cls       = XposedHelpers.findClass("com.windmill.sdk.reward.WMRewardAd", cl);
            Class<?> adInfoCls = XposedHelpers.findClass("com.windmill.sdk.models.AdInfo", cl);
            XposedHelpers.findAndHookMethod(cls, "onVideoAdClosed", adInfoCls,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            LogServer.log("[Closed] Ad closed");
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("QukanHook [Hook] onVideoAdClosed FAIL: " + t);
        }
    }

    // ======================================================================
    // Hook: Custom Credentials (OAID, Token, and UserDataEntity)
    // ======================================================================
    private void hookCustomCredentials(ClassLoader cl) {
        // Hook OAID
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
            XposedBridge.log("QukanHook [Hook] hookCustomCredentials (z.n) FAIL: " + t);
        }

        // Hook z.x(UserDataEntity) — when App refreshes user info from server,
        // update our customUserJson so we don't keep returning stale data
        try {
            Class<?> zClass = XposedHelpers.findClass("com.example.advertisinglibrary.util.z", cl);
            Class<?> userClass = XposedHelpers.findClass("com.example.advertisinglibrary.bean.UserDataEntity", cl);
            XposedHelpers.findAndHookMethod(zClass, "x", userClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object userEntity = param.args[0];
                    if (userEntity == null) return;
                    try {
                        // Only update if refresh was truly successful (has token + user)
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
                        LogServer.log("[Refresh] Failed to serialize UserDataEntity: " + t.getMessage());
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
                        LogServer.log("[STOP] Too many errors, auto-loop stopped.");
                        return;
                    }
                    h().postDelayed(MainHook.this::scheduleNextRound, RETRY_DELAY_MS);
                }
            };

            Class<?> adInfoCls = XposedHelpers.findClass("com.windmill.sdk.models.AdInfo", cl);
            XposedHelpers.findAndHookMethod(cls, "onVideoAdLoadFail",  adInfoCls, errHook);
            XposedHelpers.findAndHookMethod(cls, "onVideoAdPlayError", adInfoCls, errHook);
            XposedBridge.log("QukanHook [Hook] errors OK");
        } catch (Throwable t) {
            XposedBridge.log("QukanHook [Hook] errors FAIL: " + t);
        }
    }

    // ======================================================================
    // Schedule next round: call fragment.showLoadRewardVideo() via reflection
    // This is the exact same code path as user manually clicking the red packet
    // ======================================================================
    private void scheduleNextRound() {
        if (!running) return;
        Object fragment = fragmentRef != null ? fragmentRef.get() : null;
        if (fragment == null) {
            // Fallback: call l.u() directly if fragment ref is gone
            Activity act     = activityRef  != null ? activityRef.get()  : null;
            Object adManager = adManagerRef != null ? adManagerRef.get() : null;
            Object listener  = adListener;
            if (act == null || act.isFinishing() || adManager == null || listener == null) {
                LogServer.log("[Next] all refs gone, stopping loop");
                running = false;
                return;
            }
            try {
                Method u = adManager.getClass().getMethod("u", Activity.class,
                        listener.getClass().getInterfaces()[0]);
                u.invoke(adManager, act, listener);
                LogServer.log("[Next] fallback l.u() called");
            } catch (Throwable t) {
                LogServer.log("[Next] FAIL: " + t.getMessage());
                running = false;
            }
            return;
        }
        try {
            // Step 1: Call getMVM().postAdreWards() — submits accumulated AdPostTypeBean list
            // to GetAdrewardCoins.ashx. This is what the app does when user clicks a red packet
            // message in the chat list (RedPacketFragment$c.a), BEFORE loading the ad.
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
                LogServer.log("[Next] postAdreWards skipped: " + t.getMessage());
            }

            // Step 2: Load + show reward video (same as manual click dialog callback)
            Method m = fragment.getClass().getDeclaredMethod("showLoadRewardVideo");
            m.setAccessible(true);
            m.invoke(fragment);
            LogServer.log("[Next] showLoadRewardVideo() called — full App chain");
        } catch (Throwable t) {
            LogServer.log("[Next] showLoadRewardVideo FAIL: " + t.getMessage());
            running = false;
        }
    }

    // ======================================================================
    // Trigger one round via HTTP /trigger endpoint
    // ======================================================================
    private String triggerOnce() {
        Object fragment = fragmentRef != null ? fragmentRef.get() : null;
        Activity act    = activityRef != null ? activityRef.get() : null;

        // Fallback 1: recover Activity from Fragment if current one is finishing/null
        if (act == null || act.isFinishing()) {
            if (fragment != null) {
                try {
                    act = (Activity) XposedHelpers.callMethod(fragment, "getActivity");
                    if (act != null && !act.isFinishing()) {
                        activityRef = new WeakReference<>(act);
                        LogServer.log("[Trigger] Recovered valid Activity from RedPacketFragment");
                    }
                } catch (Throwable ignored) {}
            }
        }

        // Fallback 2: dynamically find RedPacketFragment if user never opened the tab
        if (fragment == null && act != null && !act.isFinishing()) {
            try {
                Object fragmentManager = XposedHelpers.callMethod(act, "getSupportFragmentManager");
                java.util.List<?> fragments = (java.util.List<?>) XposedHelpers.callMethod(fragmentManager, "getFragments");
                for (Object f : fragments) {
                    if (f != null && f.getClass().getName().contains("RedPacketFragment")) {
                        fragment = f;
                        fragmentRef = new WeakReference<>(fragment);
                        LogServer.log("[Trigger] Dynamically found RedPacketFragment in Activity");
                        break;
                    }
                }
            } catch (Throwable t) {
                LogServer.log("[Trigger] Failed to search fragments: " + t.getMessage());
            }
        }


        if (act == null || act.isFinishing())
            return "FAIL: Activity not ready — open 红包 tab first";
        if (fragment == null)
            return "FAIL: RedPacketFragment not ready — open 红包 tab first";
            
        if (!running) { running = true; roundCount = 0; errorCount = 0; }
        h().post(this::scheduleNextRound);
        return "Triggered round " + (roundCount + 1) + " via showLoadRewardVideo()";
    }

    // ======================================================================
    // Activate auto-loop on first manual ad trigger
    // ======================================================================
    private void activateAutoLoop() {
        running    = true;
        roundCount = 0;
        errorCount = 0;
        Activity act = activityRef != null ? activityRef.get() : null;
        if (act != null) {
            h().post(() -> Toast.makeText(act.getApplicationContext(),
                    "Auto-loop activated! Every " + LOOP_INTERVAL_MS/1000 + "s",
                    Toast.LENGTH_SHORT).show());
        }
        LogServer.log("=== Auto-loop activated, interval=" + LOOP_INTERVAL_MS/1000 + "s ===");
    }

    // ======================================================================
    // Hook: Device Fingerprint Spoofing
    // Randomizes ANDROID_ID, IMEI, MAC deterministically based on customOaid
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

            XposedBridge.log("QukanHook [Hook] hookDeviceFingerprint OK");
        } catch (Throwable t) {
            XposedBridge.log("QukanHook [Hook] hookDeviceFingerprint FAIL: " + t);
        }
    }

    // ======================================================================
    // Hook: RedPacketFragment$g.onVideoRewarded
    // This is where postAdreWardsReceive() + GetAdrewardCoins.ashx are called.
    // After this fires we know reward was delivered → schedule next round.
    // ======================================================================
    private void hookRedPacketReward(ClassLoader cl) {
        try {
            // Inner class name after R8: RedPacketFragment$g
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
                            // Manual only - no auto scheduling
                        }
                    });
            XposedBridge.log("QukanHook [Hook] RedPacketFragment$g.onVideoRewarded OK");
        } catch (Throwable t) {
            XposedBridge.log("QukanHook [Hook] RedPacketFragment$g.onVideoRewarded FAIL: " + t);
        }
    }
}
