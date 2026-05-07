package com.qukan.hook;

/*
 * ╔══════════════════════════════════════════════════════════════════╗
 * ║                    趣玩生活 Xposed Hook 模块                      ║
 * ╠══════════════════════════════════════════════════════════════════╣
 * ║ 功能概述:                                                        ║
 * ║   1. 【广告管控】 拦截插屏广告展示、拦截快应用外跳、激励视频透明化       ║
 * ║   2. 【广告加速】 激励视频快速跳过 + rtbcallback 奖励验证           ║
 * ║   3. 【凭证管理】 OAID/Token/用户数据 注入与自动捕获               ║
 * ║   4. 【设备伪装】 基于 OAID 的设备指纹伪造                         ║
 * ║   5. 【自动循环】 通过 /trigger 端点触发刷币自动化循环              ║
 * ║   6. 【调试监控】 加密参数日志、API请求记录、配置信息捕获           ║
 * ╠══════════════════════════════════════════════════════════════════╣
 * ║ 代码结构:                                                        ║
 * ║   区域A: 字段声明与常量配置                                       ║
 * ║   区域B: 主入口与 Hook 注册                                       ║
 * ║   区域C: 广告管控（插屏拦截/外跳拦截/窗口透明化）               ║
 * ║   区域D: 激励视频生命周期 Hook（加载/播放/奖励/关闭/错误）         ║
 * ║   区域E: 凭证注入与用户信息同步                                   ║
 * ║   区域F: 设备指纹伪造                                             ║
 * ║   区域G: 自动循环控制（调度/触发/激活）                            ║
 * ║   区域H: 调试与监控（加密日志/API记录/配置捕获）                   ║
 * ╚══════════════════════════════════════════════════════════════════╝
 */

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


    // ╔══════════════════════════════════════════════════════════════╗
    // ║                   区域A: 字段声明与常量配置                    ║
    // ╚══════════════════════════════════════════════════════════════╝

    /** 全局单例引用，供 LogServer 等外部类回调使用 */
    private static MainHook instance;
    public static MainHook getInstance() { return instance; }

    /** 目标应用包名 */
    private static final String TARGET_PKG = "top.dffhq.qwsh";

    // ---------- 自动循环时间参数 ----------
    private static final long LOOP_INTERVAL_MS  = 30_000;  // 每轮间隔 30 秒
    private static final long SHOW_DELAY_MS     = 2_000;   // 展示延迟 2 秒
    private static final long RETRY_DELAY_MS    = 10_000;  // 错误重试延迟 10 秒
    private static final int  MAX_ERRORS        = 5;       // 最大连续错误次数，超过则停止循环

    // ---------- 主线程 Handler（延迟初始化，类加载时 Looper 可能为空）----------
    private Handler handler;
    private Handler h() {
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        return handler;
    }

    // ---------- 运行时引用（弱引用防止内存泄漏）----------
    private WeakReference<Activity> activityRef;   // 当前 App 的前台 Activity
    private WeakReference<Object>   adManagerRef;  // 广告管理器 (l 类) 实例
    private WeakReference<Object>   fragmentRef;   // RedPacketFragment 实例（红包页面）
    public WeakReference<Object> getFragmentRef() { return fragmentRef; }
    private Object                  adListener;    // 广告监听器（强引用，防止 GC 回收）
    private Object                  lastAdInfo;    // 最近一次广告加载成功时捕获的 AdInfo（含真实 loadId）

    // ---------- 自动循环状态 ----------
    private int     roundCount = 0;     // 当前已完成的轮次计数
    private int     errorCount = 0;     // 连续错误计数（达到 MAX_ERRORS 时自动停止）
    private boolean running    = false;  // 自动循环是否正在运行
    private volatile Object lastWaterfall;  // 从 Windmill V6 响应中捕获的 Waterfall 策略对象

    // ---------- 功能开关（可通过 Web 端动态切换）----------
    /** 是否启用广告快速跳过（true=跳过视频直接领奖，false=正常播放完整视频） */
    public static volatile boolean skipAdEnabled = true;
    /** 是否拦截插屏广告展示（true=阻止插屏show()+伪造回调，false=放行插屏正常展示） */
    public static volatile boolean blockNonRewardAds = true;

    // ---------- 自定义凭证（用于多账号切换/注入）----------
    public static volatile String customOaid = null;        // 自定义 OAID（广告追踪标识）
    public static volatile String customToken = null;       // 自定义登录 Token
    public static volatile String customUserJson = null;    // 自定义用户数据 JSON（完整 UserDataEntity）
    public static volatile String lastPackageInfo = null;   // 从用户信息中捕获的提现套餐信息
    public static volatile String lastWeixinAppId = null;   // 从 GetSetting 接口捕获的微信 AppId

    // ---------- 全局 Context（用于 SharedPreferences 持久化存储）----------
    public static volatile android.content.Context appContext;
    public static volatile ClassLoader appClassLoader;

    /**
     * 保存凭证到内存 + 持久化到 SharedPreferences
     * 同时写入 App 原生的登录缓存，实现「免登录注入」
     *
     * @param oaid     广告追踪标识（OAID）
     * @param token    登录授权 Token
     * @param userJson 完整的用户数据 JSON
     */
    public static void saveCreds(String oaid, String token, String userJson) {
        // 清理输入：去除多余引号和 XML 转义字符
        if (oaid != null) oaid = oaid.trim().replace("\"", "");
        if (token != null) token = token.trim().replace("\"", "");
        if (userJson != null) userJson = userJson.replace("&quot;", "\"");

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

    private volatile boolean toastShown      = false;  // Toast 提示是否已显示（仅首次显示）
    private volatile boolean serverInitDone  = false;  // 日志服务器是否已初始化

    // ╔══════════════════════════════════════════════════════════════╗
    // ║                   区域B: 主入口与 Hook 注册                    ║
    // ╠══════════════════════════════════════════════════════════════╣
    // ║ handleLoadPackage: Xposed 框架入口，当目标 App 加载时触发      ║
    // ║   1. 启动日志服务器 (LogServer)                                ║
    // ║   2. 捕获 Application Context                                 ║
    // ║   3. 按顺序注册所有 Hook                                      ║
    // ╚══════════════════════════════════════════════════════════════╝
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PKG.equals(lpparam.packageName)) return;
        instance = this;
        XposedBridge.log("趣看Hook >>> 已加载目标包: " + lpparam.packageName);

        ClassLoader cl = lpparam.classLoader;

        // 立即启动日志服务器
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
            XposedBridge.log("趣看Hook 日志服务器已启动，端口: " + LogServer.actualPort);
        }

        // 尽早捕获 Application Context
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

        hookToast(cl);                // [区域C] Toast 注入确认提示
        hookCustomCredentials(cl);    // [区域E] 自定义凭证注入（OAID/Token/用户数据）
        hookDeviceFingerprint(cl);    // [区域F] 设备指纹伪造（Android ID/IMEI/MAC）
        hookBlockNonRewardAds(cl);    // [区域C] 插屏广告拦截（阻止展示+伪造回调）
        hookBlockQuickApp(cl);        // [区域C] 拦截快应用/外部应用跳转
        hookAdManager(cl);            // [区域D] 捕获广告管理器实例引用
        hookShowLoadRewardVideo(cl);  // [区域D] 捕获红包页面 Fragment 引用
        hookRewardAdLoadSuccess(cl);  // [区域D] 广告加载成功回调日志
        hookRandomizeAdInfo(cl);      // [区域D] 广告信息随机化（已禁用）
        hookSigmobAdActivity(cl);     // [区域D] 激励视频快速跳过 + rtbcallback
        hookAdWindowTransparent(cl);  // [区域C] 广告窗口透明化 + 静音
        hookRewardAdPlayStart(cl);    // [区域D] 广告播放开始日志
        hookRewardAdReward(cl);       // [区域D] SDK层奖励回调日志
        hookRewardAdPlayEnd(cl);      // [区域D] 广告播放结束日志
        hookRewardAdClosed(cl);       // [区域D] 广告关闭 + 缓存清除 + 重新加载
        hookRewardAdErrors(cl);       // [区域D] 广告错误处理 + 自动重试
        hookRedPacketReward(cl);      // [区域D] 红包奖励确认（App层）
        hookLogEncryptParams(cl);     // [区域H] 加密参数明文日志
        hookGetSettingInfo(cl);       // [区域H] 微信AppId等配置捕获
    }

    // ======================================================================
    // Hook 0: Activity.onCreate/onResume — 弹 Toast 确认注入成功
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
            XposedBridge.log("趣看Hook [Hook] Activity.onCreate 注册成功");
        } catch (Throwable t) {
            XposedBridge.log("趣看Hook [Hook] Activity.onCreate 注册失败: " + t);
        }
    }

    // ╔══════════════════════════════════════════════════════════════╗
    // ║                 区域C: 广告管控（拦截/外跳拦截）               ║
    // ╠══════════════════════════════════════════════════════════════╣
    // ║ hookBlockNonRewardAds: 插屏广告拦截（阻止展示+伪造回调）       ║
    // ║ hookBlockQuickApp:     拦截广告SDK发起的快应用/外部应用跳转     ║
    // ║ hookAdWindowTransparent: 广告Activity窗口透明化+静音           ║
    // ║ hookToast:             首次打开App时弹Toast确认注入成功        ║
    // ╚══════════════════════════════════════════════════════════════╝

    // ======================================================================
    // 插屏广告拦截
    // 策略：阻止 WMInterstitialAd.show() 执行，但伪造完整回调链
    //       (onInterstitialAdPlayStart → onInterstitialAdClosed)
    //       使 App 认为广告已正常展示完毕，不会卡死
    // 开关：blockNonRewardAds（可在控制面板中切换）
    // 注意：仅拦截插屏广告，不干涉激励视频和其他广告类型
    // ======================================================================
    private void hookBlockNonRewardAds(ClassLoader cl) {
        try {
            Class<?> interstitialCls = XposedHelpers.findClass(
                    "com.windmill.sdk.interstitial.WMInterstitialAd", cl);

            XposedHelpers.findAndHookMethod(interstitialCls, "show",
                    Activity.class, java.util.HashMap.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // 开关未开启时放行，让插屏正常展示
                            if (!blockNonRewardAds) return;

                            try {
                                // 获取广告信息用于日志
                                Object adInfo = XposedHelpers.callMethod(
                                        param.thisObject, "getAdInfo");
                                String networkName = "unknown";
                                try {
                                    if (adInfo != null) {
                                        networkName = (String) XposedHelpers.callMethod(
                                                adInfo, "getNetworkName");
                                    }
                                } catch (Throwable ignored) {}

                                LogServer.log("[插屏拦截] ⛔ 已拦截插屏广告: network=" + networkName);

                                // ① 阻止 show() 实际执行
                                param.setResult(false);

                                // ② 伪造回调链，让 App 认为广告已正常展示完毕
                                Object listener = XposedHelpers.getObjectField(
                                        param.thisObject, "wmInterstitialAdListener");
                                if (listener != null && adInfo != null) {
                                    // 立即触发 PlayStart
                                    try {
                                        XposedHelpers.callMethod(listener,
                                                "onInterstitialAdPlayStart", adInfo);
                                    } catch (Throwable ignored) {}

                                    // 300ms 后触发 Closed（模拟自然关闭间隔）
                                    final Object fListener = listener;
                                    final Object fAdInfo = adInfo;
                                    h().postDelayed(() -> {
                                        try {
                                            XposedHelpers.callMethod(fListener,
                                                    "onInterstitialAdClosed", fAdInfo);
                                            LogServer.log("[插屏拦截] ✓ 回调链完成 " +
                                                    "(PlayStart → Closed)");
                                        } catch (Throwable ignored) {}
                                    }, 300);
                                }
                            } catch (Throwable t) {
                                LogServer.log("[插屏拦截] 拦截异常: " + t.getMessage());
                            }
                        }
                    });

            LogServer.log("[插屏拦截] ★ 插屏广告拦截 Hook 注册成功");
            XposedBridge.log("趣看Hook [Hook] 插屏广告拦截注册成功");
        } catch (Throwable t) {
            LogServer.log("[插屏拦截] 插屏广告 Hook 失败: " + t.getMessage());
            XposedBridge.log("趣看Hook [Hook] 插屏广告拦截注册失败: " + t);
        }
    }


    // ╔══════════════════════════════════════════════════════════════╗
    // ║       区域D: 激励视频生命周期 Hook（加载/播放/奖励/关闭）       ║
    // ╠══════════════════════════════════════════════════════════════╣
    // ║ hookAdManager:          捕获广告管理器 + Activity + 监听器引用 ║
    // ║ hookShowLoadRewardVideo: 捕获 RedPacketFragment 实例          ║
    // ║ hookRandomizeAdInfo:    广告信息随机化（已禁用）               ║
    // ║ hookSigmobAdActivity:   激励视频快速跳过 + rtbcallback 验证   ║
    // ║ hookRewardAdLoadSuccess: 广告加载成功日志                     ║
    // ║ hookRewardAdPlayStart:  广告播放开始日志                      ║
    // ║ hookRewardAdReward:     SDK层奖励回调日志                     ║
    // ║ hookRewardAdPlayEnd:    广告播放结束日志                      ║
    // ║ hookRewardAdClosed:     广告关闭+缓存清除+重新加载             ║
    // ║ hookRewardAdErrors:     广告错误处理+自动重试                  ║
    // ║ hookRedPacketReward:    红包奖励确认（App层回调）              ║
    // ╚══════════════════════════════════════════════════════════════╝

    // ======================================================================
    // 捕获广告管理器实例
    // Hook l.u(Activity, WMRewardAdListener) 获取 Activity + AdManager + 监听器
    // 这些引用是后续自动循环触发广告的基础
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
                            adListener    = param.args[1]; // 强引用 — 防止 g() 实例被 GC
                            LogServer.log("[AdMgr] 已捕获 Activity + AdManager + 广告监听器");
                        }
                    });
            XposedBridge.log("趣看Hook [Hook] l.u 注册成功");
        } catch (Throwable t) {
            XposedBridge.log("趣看Hook [Hook] l.u 注册失败: " + t);
        }
    }

    // ======================================================================
    // 捕获 RedPacketFragment 实例
    // 红包页面是触发广告的真实入口
    // 通过 Hook onResume + showLoadRewardVideo 双重捕获，确保引用可靠
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
            XposedBridge.log("趣看Hook [Hook] showLoadRewardVideo 注册成功");
        } catch (Throwable t) {
            XposedBridge.log("趣看Hook [Hook] showLoadRewardVideo 注册失败: " + t);
        }
    }


    // ======================================================================
    // 广告信息随机化（已禁用）
    // 原本用于每次请求生成不同的 extraInfo 防止服务端重放检测
    // 但会导致 extraInfo 与 rtbcallback 中的 custom.extraInfo 不一致
    // 因此已禁用，保持 App 原始生成的 extraInfo 值
    // ======================================================================
    private void hookRandomizeAdInfo(ClassLoader cl) {
        // 不再修改 extraInfo，避免与 rtbcallback 的 custom.extraInfo 不一致
        LogServer.log("[Hook] hookRandomizeAdInfo 已禁用（保持 extraInfo 原始值）");
    }

    // ======================================================================
    // 激励视频快速跳过（核心功能）
    // 工作原理：
    //   1. Hook WMRewardAd.show() → 允许广告正常加载（不阻止）
    //   2. Hook onVideoAdPlayStart() → 广告开始播放后延迟1秒触发：
    //      a. 通过反射获取 controller 内的策略对象 (strategy.a)
    //      b. 构造 rtbcallback 奖励验证请求发送给 Sigmob 服务器
    //      c. 依次触发 onVideoAdReward → onVideoAdPlayEnd → onVideoAdClosed
    //      d. 关闭所有广告 SDK 的 Activity
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
                                LogServer.log("[跳过] 跳过广告已关闭，正常播放视频");
                                return;
                            }
                            LogServer.log("[跳过] ★ WMRewardAd.show() → 允许加载，准备快速关闭");
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
                            LogServer.log("[跳过] ★ 广告已加载播放，开始快速完成奖励...");

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
                                                                    LogServer.log("[跳过] ✅ rtbcallback 奖励验证成功");
                                                                } else if (method.getName().equals("a") && args != null && args.length > 0) {
                                                                    LogServer.log("[跳过] ✗ rtbcallback 奖励验证失败: " + args[0]);
                                                                }
                                                                return null;
                                                            });
                                                    String rewardBody = rewardJson.toString();
                                                    LogServer.log("[rtbcallback] 📡 请求URL: " + fullUrl);
                                                    LogServer.log("[rtbcallback] 📦 请求体: " + rewardBody);
                                                    XposedHelpers.callStaticMethod(jCls, "a", fullUrl, rewardBody, 2, "", windMillAdReq, callback);
                                                    LogServer.log("[跳过] rtbcallback 已发送");
                                                }
                                            }
                                        } catch (Throwable t) {
                                            LogServer.log("[跳过] rtbcallback 异常: " + t.getMessage());
                                        }
                                    }

                                    // ---- 触发 WM 层奖励回调 ----
                                    Class<?> riCls = XposedHelpers.findClass("com.windmill.sdk.reward.WMRewardInfo", cl);
                                    Object ri = riCls.getConstructor(boolean.class, String.class, String.class, String.class)
                                            .newInstance(true, java.util.UUID.randomUUID().toString(), "", "");
                                    XposedHelpers.callMethod(wmRewardAd, "onVideoAdReward", adInfo, ri);
                                    LogServer.log("[跳过] ✓ onVideoAdReward 已触发");

                                    // ---- 300ms 后触发播放结束 ----
                                    h().postDelayed(() -> {
                                        try {
                                            XposedHelpers.callMethod(wmRewardAd, "onVideoAdPlayEnd", adInfo);
                                            LogServer.log("[跳过] ✓ onVideoAdPlayEnd 已触发");

                                            // ---- 300ms 后关闭广告并触发 close 回调 ----
                                            h().postDelayed(() -> {
                                                try {
                                                    XposedHelpers.callMethod(wmRewardAd, "onVideoAdClosed", adInfo);
                                                    LogServer.log("[跳过] ✓ onVideoAdClosed 已触发");

                                                    // 关闭所有 SDK 广告 Activity
                                                    closeAdActivities();
                                                } catch (Throwable ignored) {}
                                            }, 300);
                                        } catch (Throwable ignored) {}
                                    }, 300);
                                } catch (Throwable t) {
                                    LogServer.log("[跳过] 快速完成异常: " + t.getMessage());
                                }
                            }, 1000);
                        }
                    });

            LogServer.log("[Hook] hookWMRewardAdShow（加载+快速关闭）注册成功");
            XposedBridge.log("趣看Hook [Hook] hookWMRewardAdShow 注册成功");
        } catch (Throwable t) {
            LogServer.log("[Hook] hookWMRewardAdShow 注册失败: " + t.getMessage());
            XposedBridge.log("趣看Hook [Hook] hookWMRewardAdShow 注册失败: " + t);
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
                            || name.startsWith("com.sigmob")
                            || name.startsWith("com.qq.e.ads")
                            || name.startsWith("com.baidu.mobads")
                            || name.startsWith("com.bytedance.sdk")
                            || name.startsWith("com.ksad")
                            || name.contains("AdActivity") || name.contains("adactivity")) {
                        LogServer.log("[跳过] 关闭广告 Activity: " + name);
                        act.finish();
                    }
                }
            }
        } catch (Throwable t) {
            LogServer.log("[跳过] 关闭广告 Activity 异常: " + t.getMessage());
        }
    }

    // ╔══════════════════════════════════════════════════════════════╗
    // ║               区域E: 凭证注入与用户信息同步                   ║
    // ╠══════════════════════════════════════════════════════════════╣
    // ║ 凭证处理分为 4 个子功能：                                   ║
    // ║   1. OAID 注入： Hook x2.b.i() 替换返回值                     ║
    // ║   2. Token 捕获+替换：                                          ║
    // ║      - TokenManager.getAccessToken() 双向捕获/替换            ║
    // ║      - i4.b.a() HTTP Header 中的 Authorization 捕获/注入     ║
    // ║   3. 用户数据注入： Hook z.n() 绕过登录屏幕                     ║
    // ║   4. 用户信息同步： Hook z.x() 实时同步服务器刷新的用户数据    ║
    // ╚══════════════════════════════════════════════════════════════╝

    // ======================================================================
    // 凭证注入总入口（OAID、Token、UserDataEntity）
    // 支持自定义凭证替换 + 自动捕获 App 运行时的真实凭证
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
            XposedBridge.log("趣看Hook [Hook] 凭证注入 (OAID) 注册成功");
        } catch (Throwable t) {
            XposedBridge.log("趣看Hook [Hook] 凭证注入 (OAID) 注册失败: " + t);
        }

        // --- 子功能2: Token 捕获+替换 ---
        // 双向逻辑：如果有自定义Token则替换，否则自动捕获App生成的Token
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
            XposedBridge.log("趣看Hook [Hook] 凭证注入 (Token 捕获+替换) 注册成功");
        } catch (Throwable t) {
            XposedBridge.log("趣看Hook [Hook] 凭证注入 (Token) 注册失败: " + t);
        }

        // --- 子功能2b: HTTP Header 中的 Token 捕获+注入 ---
        // 拦截 Retrofit 请求头设置器，捕获/替换 Authorization Header
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
            XposedBridge.log("趣看Hook [Hook] 凭证注入 (HTTP Header 捕获+广播) 注册成功");
        } catch (Throwable t) {
            XposedBridge.log("趣看Hook [Hook] 凭证注入 (HTTP Header) 注册失败: " + t);
        }

        // --- 子功能3: 用户数据注入 ---
        // Hook z.n() 绕过登录页面，直接返回自定义的 UserDataEntity
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
            XposedBridge.log("趣看Hook [Hook] 凭证注入 (z.n) 注册成功");
        } catch (Throwable t) {
            XposedBridge.log("趣看Hook [Hook] 凭证注入 (z.n) 注册失败: " + t);
        }

        // --- 子功能4: 用户信息实时同步 ---
        // Hook z.x(UserDataEntity) —— 当 App 从服务器刷新用户信息时
        // 自动同步更新本地缓存，避免使用过期数据
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
                            // 捕获 package_info（提现套餐信息）
                            try {
                                Object pkgInfo = XposedHelpers.callMethod(user, "getPackage_info");
                                if (pkgInfo != null && !pkgInfo.toString().equals("null") && !pkgInfo.toString().isEmpty()) {
                                    lastPackageInfo = pkgInfo.toString();
                                    LogServer.log("[Refresh] 💰 package_info 已捕获: " + lastPackageInfo.substring(0, Math.min(40, lastPackageInfo.length())) + "...");
                                }
                            } catch (Throwable ignored) {
                                // getPackage_info 方法可能不存在，尝试通过 JSON 解析获取
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
                            // 同时持久化到磁盘
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
            XposedBridge.log("趣看Hook [Hook] z.x (用户信息刷新同步) 注册成功");
        } catch (Throwable t) {
            XposedBridge.log("趣看Hook [Hook] z.x 注册失败: " + t);
        }
    }

    // ======================================================================
    // 广告加载/播放错误处理
    // 当连续错误达到 MAX_ERRORS 次时自动停止循环
    // 否则延迟 RETRY_DELAY_MS 后自动重试下一轮
    // ======================================================================
    private void hookRewardAdErrors(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass("com.windmill.sdk.reward.WMRewardAd", cl);
            XC_MethodHook errHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    errorCount++;
                    LogServer.log("[错误] " + param.method.getName()
                            + " 错误次数=" + errorCount + "/" + MAX_ERRORS);
                    if (errorCount >= MAX_ERRORS) {
                        running = false;
                        LogServer.log("[停止] 错误次数过多，自动循环已停止");
                        return;
                    }
                    h().postDelayed(MainHook.this::scheduleNextRound, RETRY_DELAY_MS);
                }
            };

            Class<?> adInfoCls = XposedHelpers.findClass("com.windmill.sdk.models.AdInfo", cl);
            XposedHelpers.findAndHookMethod(cls, "onVideoAdLoadFail",  adInfoCls, errHook);
            XposedHelpers.findAndHookMethod(cls, "onVideoAdPlayError", adInfoCls, errHook);
            XposedBridge.log("趣看Hook [Hook] 错误处理注册成功");
        } catch (Throwable t) {
            XposedBridge.log("趣看Hook [Hook] 错误处理注册失败: " + t);
        }
    }

    // ======================================================================
    // 安排下一轮：通过反射调用 fragment.showLoadRewardVideo()
    // 与用户手动点击红包的代码路径完全一致
    // ======================================================================
    private void scheduleNextRound() {
        if (!running) return;
        Object fragment = fragmentRef != null ? fragmentRef.get() : null;
        if (fragment == null) {
            // 备用降级： Fragment 引用已丢失，直接调用 l.u()
            Activity act     = activityRef  != null ? activityRef.get()  : null;
            Object adManager = adManagerRef != null ? adManagerRef.get() : null;
            Object listener  = adListener;
            if (act == null || act.isFinishing() || adManager == null || listener == null) {
                LogServer.log("[下一轮] 所有引用已失效，停止循环");
                running = false;
                return;
            }
            try {
                Method u = adManager.getClass().getMethod("u", Activity.class,
                        listener.getClass().getInterfaces()[0]);
                u.invoke(adManager, act, listener);
                LogServer.log("[下一轮] 降级调用 l.u() 成功");
            } catch (Throwable t) {
                LogServer.log("[下一轮] 失败: " + t.getMessage());
                running = false;
            }
            return;
        }
        try {
            // 步骤1: 调用 getMVM().postAdreWards()
            // 提交累积的广告奖励列表到 GetAdrewardCoins.ashx
            // （与用户手动点击红包时的行为一致）
            
            try {
                Method getMVM = fragment.getClass().getMethod("getMVM");
                getMVM.setAccessible(true);
                Object viewModel = getMVM.invoke(fragment);
                if (viewModel != null) {
                    Method postAdreWards = viewModel.getClass().getDeclaredMethod("postAdreWards");
                    postAdreWards.setAccessible(true);
                    postAdreWards.invoke(viewModel);
                    LogServer.log("[下一轮] ✅ postAdreWards() 已调用 → GetAdrewardCoins.ashx");
                }
            } catch (Throwable t) {
                LogServer.log("[下一轮] postAdreWards 已跳过: " + t.getMessage());
            }

            // 步骤2: 加载并展示奖励视频
            Method m = fragment.getClass().getDeclaredMethod("showLoadRewardVideo");
            m.setAccessible(true);
            m.invoke(fragment);
            LogServer.log("[下一轮] showLoadRewardVideo() 已调用");
        } catch (Throwable t) {
            LogServer.log("[下一轮] showLoadRewardVideo 失败: " + t.getMessage());
            running = false;
        }
    }

    // ======================================================================
    // 通过 HTTP /trigger 端点触发一轮广告
    // 降级策略：
    //   1. Activity 已结束 → 从 Fragment 中恢复
    //   2. Fragment 未捕获 → 动态搜索 FragmentManager
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
                        LogServer.log("[触发] 已从 RedPacketFragment 恢复有效 Activity");
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
                        LogServer.log("[触发] 动态找到 RedPacketFragment");
                        break;
                    }
                }
            } catch (Throwable t) {
                LogServer.log("[触发] 搜索 Fragment 失败: " + t.getMessage());
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
        LogServer.log("=== 自动循环已激活, 间隔=" + LOOP_INTERVAL_MS/1000 + "秒 ===");
    }

    // ╔══════════════════════════════════════════════════════════════╗
    // ║                   区域F: 设备指纹伪造                        ║
    // ╠══════════════════════════════════════════════════════════════╣
    // ║ 基于 customOaid + 前缀 确定性生成伪造的设备标识:         ║
    // ║   - android_id: MD5(OAID+"android_id") 取前16位            ║
    // ║   - imei:       MD5(OAID+"imei") 转纯数字取15位             ║
    // ║   - mac:        MD5(OAID+"mac") 格式化为 XX:XX:XX:XX:XX:XX ║
    // ║ 同一个 OAID 始终生成相同的伪造指纹，确保一致性        ║
    // ╚══════════════════════════════════════════════════════════════╝

    // ======================================================================
    // 伪造指纹生成器
    // 基于 customOaid 确定性地生成 ANDROID_ID/IMEI/MAC
    // 同一个 OAID 每次运行都会生成相同的结果，确保设备指纹一致性
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
                                // 不每次都打印日志，避免刷屏
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

            XposedBridge.log("趣看Hook [Hook] 设备指纹伪造注册成功");
        } catch (Throwable t) {
            XposedBridge.log("趣看Hook [Hook] 设备指纹伪造注册失败: " + t);
        }
    }

    // ======================================================================
    // Hook: 红包奖励回调 RedPacketFragment$g.onVideoRewarded
    // postAdreWardsReceive() + GetAdrewardCoins.ashx 在此被触发
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
                            LogServer.log("★ [红包.g] onVideoRewarded #" + roundCount
                                    + " — postAdreWardsReceive + GetAdrewardCoins.ashx 已由 App 触发");
                            // 仅手动触发，不自动调度
                        }
                    });
            XposedBridge.log("趣看Hook [Hook] 红包奖励回调注册成功");
        } catch (Throwable t) {
            XposedBridge.log("趣看Hook [Hook] 红包奖励回调注册失败: " + t);
        }
    }

    // ======================================================================
    // Hook: 拦截快应用/外部应用跳转
    // 拦截 startActivity 以防止广告跳出 App
    // ======================================================================
    private void hookBlockQuickApp(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", cl, "startActivity",
                    android.content.Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!blockNonRewardAds) return;
                            android.content.Intent intent = (android.content.Intent) param.args[0];
                            if (intent == null) return;
                            if (shouldBlockIntent(intent)) {
                                LogServer.log("[拦截] 🚫 拦截外跳: " + describeIntent(intent));
                                param.setResult(null);
                            }
                        }
                    });

            // 也 hook startActivity(Intent, Bundle) 重载
            XposedHelpers.findAndHookMethod("android.app.Activity", cl, "startActivity",
                    android.content.Intent.class, android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!blockNonRewardAds) return;
                            android.content.Intent intent = (android.content.Intent) param.args[0];
                            if (intent == null) return;
                            if (shouldBlockIntent(intent)) {
                                LogServer.log("[拦截] 🚫 拦截外跳: " + describeIntent(intent));
                                param.setResult(null);
                            }
                        }
                    });

            // 也 Hook Context.startActivity 兜底
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", cl, "startActivity",
                    android.content.Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!blockNonRewardAds) return;
                            android.content.Intent intent = (android.content.Intent) param.args[0];
                            if (intent == null) return;
                            if (shouldBlockIntent(intent)) {
                                LogServer.log("[拦截] 🚫 拦截外跳(Context): " + describeIntent(intent));
                                param.setResult(null);
                            }
                        }
                    });

            LogServer.log("[Hook] 🚫 快应用/外跳拦截已注册");
            XposedBridge.log("趣看Hook [Hook] 快应用拦截注册成功");
        } catch (Throwable t) {
            LogServer.log("[Hook] 快应用拦截注册失败: " + t.getMessage());
            XposedBridge.log("趣看Hook [Hook] 快应用拦截注册失败: " + t);
        }
    }

    private static boolean shouldBlockIntent(android.content.Intent intent) {
        android.net.Uri data = intent.getData();
        if (data != null) {
            String scheme = data.getScheme();
            if (scheme != null) {
                scheme = scheme.toLowerCase();
                if (scheme.equals("hap") || scheme.equals("hwfastapp") || scheme.equals("hapjs")
                        || scheme.equals("quickapp") || scheme.equals("qkapp")
                        || scheme.equals("vivofastapp") || scheme.equals("oppominiapp")) {
                    return true;
                }
                if (scheme.equals("market") || scheme.equals("taobao") || scheme.equals("tbopen")
                        || scheme.equals("pinduoduo") || scheme.equals("snssdk1128")) {
                    return true;
                }
            }
            String host = data.getHost();
            if (host != null) {
                host = host.toLowerCase();
                if (host.contains("fastapp") || host.contains("quickapp") || host.contains("miniapp")) {
                    return true;
                }
            }
        }
        String pkg = intent.getPackage();
        if (pkg == null) {
            android.content.ComponentName comp = intent.getComponent();
            if (comp != null) pkg = comp.getPackageName();
        }
        if (pkg != null) {
            pkg = pkg.toLowerCase();
            if (pkg.contains("fastapp") || pkg.contains("quickapp") || pkg.contains("hybrid")
                    || pkg.contains("miniapp")) {
                return true;
            }
            if (pkg.equals("com.huawei.fastapp") || pkg.equals("com.vivo.hybrid")
                    || pkg.equals("com.oppo.miniapp") || pkg.equals("com.xiaomi.hybrid")) {
                return true;
            }
        }
        String action = intent.getAction();
        if (android.content.Intent.ACTION_VIEW.equals(action) && data != null) {
            String scheme = data.getScheme();
            if (scheme != null && (scheme.equals("http") || scheme.equals("https"))) {
                try {
                    String caller = new Throwable().getStackTrace()[4].getClassName();
                    if (caller.contains("windmill") || caller.contains("sigmob")
                            || caller.contains("bytedance") || caller.contains("qq.e")
                            || caller.contains("kwad") || caller.contains("baidu")) {
                        return true;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private static String describeIntent(android.content.Intent intent) {
        StringBuilder sb = new StringBuilder();
        if (intent.getData() != null) sb.append("data=").append(intent.getData().toString().substring(0, Math.min(80, intent.getData().toString().length())));
        if (intent.getPackage() != null) sb.append(" pkg=").append(intent.getPackage());
        if (intent.getComponent() != null) sb.append(" comp=").append(intent.getComponent().getShortClassName());
        if (sb.length() == 0) sb.append(intent.toString().substring(0, Math.min(80, intent.toString().length())));
        return sb.toString();
    }

    // ╔══════════════════════════════════════════════════════════════╗
    // ║         区域H: 调试与监控（加密日志/API记录/配置捕获）          ║
    // ╠══════════════════════════════════════════════════════════════╣
    // ║ hookLogEncryptParams: 加密前明文日志 + API请求记录             ║
    // ║ hookGetSettingInfo:   捕获微信AppId/汇率/App版本等配置         ║
    // ╚══════════════════════════════════════════════════════════════╝

    // ======================================================================
    // 记录加密前明文参数
    // 拦截 e.a(String) 捕获 GetAdrewardCoins 请求体
    // 以及 z2.a.u() 拦截 API 请求 URL 和 RequestBody
    // ======================================================================
    private void hookLogEncryptParams(ClassLoader cl) {
        try {
            Class<?> encryptCls = XposedHelpers.findClass("com.example.advertisinglibrary.util.e", cl);
            XposedHelpers.findAndHookMethod(encryptCls, "a", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String plaintext = (String) param.args[0];
                            LogServer.log("[Encrypt] 🔓 加密前明文: " + plaintext);
                        }
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String encrypted = (String) param.getResult();
                            if (encrypted != null && encrypted.length() > 60) {
                                encrypted = encrypted.substring(0, 60) + "...";
                            }
                            LogServer.log("[Encrypt] 🔒 加密后: " + encrypted);
                        }
                    });
            LogServer.log("[Hook] 🔓 加密参数日志 Hook 已注册");
        } catch (Throwable t) {
            LogServer.log("[Hook] 加密参数 Hook 失败: " + t.getMessage());
        }

        // z2.a 是 Retrofit 接口（Kotlin suspend），抽象方法无法被 Xposed Hook
        // 改为在 OkHttp 层拦截所有请求：Hook RealCall.execute() 和 RealCall.enqueue()
        try {
            Class<?> realCallCls = XposedHelpers.findClass("okhttp3.RealCall", cl);
            // 同步请求
            XposedHelpers.findAndHookMethod(realCallCls, "execute",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Object request = XposedHelpers.getObjectField(param.thisObject, "originalRequest");
                            if (request != null) {
                                Object urlObj = XposedHelpers.callMethod(request, "url");
                                LogServer.log("[API] 📡 请求URL(Sync): " + urlObj);
                                Object body = XposedHelpers.callMethod(request, "body");
                                if (body != null) {
                                    LogServer.log("[API] 📦 RequestBody: " + body.getClass().getName());
                                }
                            }
                        }
                    });
            // 异步请求
            XposedHelpers.findAndHookMethod(realCallCls, "enqueue",
                    "okhttp3.Callback", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Object request = XposedHelpers.getObjectField(param.thisObject, "originalRequest");
                            if (request != null) {
                                Object urlObj = XposedHelpers.callMethod(request, "url");
                                LogServer.log("[API] 📡 请求URL(Async): " + urlObj);
                                Object body = XposedHelpers.callMethod(request, "body");
                                if (body != null) {
                                    LogServer.log("[API] 📦 RequestBody: " + body.getClass().getName());
                                }
                            }
                        }
                    });
            LogServer.log("[Hook] 📡 OkHttp 请求日志 Hook 已注册");
        } catch (Throwable t) {
            LogServer.log("[Hook] OkHttp Hook 失败: " + t.getMessage());
        }
    }

    // ======================================================================
    // Hook: GetSetting.ashx — 捕获微信 AppId 及其他配置信息
    // ======================================================================
    private void hookGetSettingInfo(ClassLoader cl) {
        try {
            Class<?> settingCls = XposedHelpers.findClass("com.example.advertisinglibrary.bean.SettingEntity", cl);

            XposedHelpers.findAndHookMethod(settingCls, "setWeixinappid", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String wxAppId = (String) param.args[0];
                            if (wxAppId != null && !wxAppId.isEmpty()) {
                                lastWeixinAppId = wxAppId;
                                LogServer.log("[Setting] 📱 微信AppId: " + wxAppId);
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(settingCls, "toString",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object entity = param.thisObject;
                            try {
                                int exchangeRate = (int) XposedHelpers.callMethod(entity, "getExchange_rate");
                                String loginType = (String) XposedHelpers.callMethod(entity, "getLoginType");
                                String appCode = (String) XposedHelpers.callMethod(entity, "getApp_code");
                                String appVer = (String) XposedHelpers.callMethod(entity, "getApp_version");
                                String wxId = (String) XposedHelpers.callMethod(entity, "getWeixinappid");
                                if (wxId != null && !wxId.isEmpty()) {
                                    lastWeixinAppId = wxId;
                                }
                                LogServer.log("[Setting] 📋 exchange_rate=" + exchangeRate
                                        + " loginType=" + loginType
                                        + " app_code=" + appCode
                                        + " app_version=" + appVer
                                        + " weixinappid=" + wxId);
                            } catch (Throwable ignored) {}
                        }
                    });

            LogServer.log("[Hook] 📱 GetSetting（微信AppId）Hook 已注册");
            XposedBridge.log("趣看Hook [Hook] GetSetting配置信息 Hook 注册成功");
        } catch (Throwable t) {
            LogServer.log("[Hook] GetSetting Hook 失败: " + t.getMessage());
            XposedBridge.log("趣看Hook [Hook] GetSetting配置信息 Hook 注册失败: " + t);
        }
    }

    // ======================================================================
    // Hook 2: WMRewardAd.onVideoAdLoadSuccess — 广告加载成功
    // ======================================================================
    private void hookRewardAdLoadSuccess(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass("com.windmill.sdk.reward.WMRewardAd", cl);
            XposedHelpers.findAndHookMethod(cls, "onVideoAdLoadSuccess", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            LogServer.log("[加载] 广告加载成功");
                        }
                    });
            XposedBridge.log("趣看Hook [Hook] onVideoAdLoadSuccess 注册成功");
        } catch (Throwable t) {
            XposedBridge.log("趣看Hook [Hook] onVideoAdLoadSuccess 注册失败: " + t);
        }
    }

    // ======================================================================
    // Hook 3b: 将 Windmill/Sigmob 激励视频 Activity 透明化 + 静音
    // 视频仍然播放（服务器会验证）但用户可以透视
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
                            if (!pkg.startsWith("com.windmill") && !pkg.startsWith("com.czhj")
                                    && !pkg.startsWith("com.sigmob")) return;
                            LogServer.log("[广告窗口] 检测到 Windmill 广告 Activity: " + pkg + " — 正在透明化");
                            try {
                                android.view.Window w = act.getWindow();
                                w.setDimAmount(0f);
                                android.view.WindowManager.LayoutParams lp = w.getAttributes();
                                lp.alpha = 0f;
                                lp.screenBrightness = 0.01f;
                                w.setAttributes(lp);
                                android.media.AudioManager am = (android.media.AudioManager)
                                        act.getSystemService(android.content.Context.AUDIO_SERVICE);
                                if (am != null) am.adjustStreamVolume(
                                        android.media.AudioManager.STREAM_MUSIC,
                                        android.media.AudioManager.ADJUST_MUTE, 0);
                                LogServer.log("[广告窗口] 透明化 + 静音 ✅");
                            } catch (Throwable t) {
                                LogServer.log("[广告窗口] 处理失败: " + t.getMessage());
                            }
                        }
                    });
            XposedBridge.log("趣看Hook [Hook] 广告窗口透明化注册成功");
        } catch (Throwable t) {
            XposedBridge.log("趣看Hook [Hook] 广告窗口透明化注册失败: " + t);
        }
    }

    // ======================================================================
    // Hook 4: WMRewardAd.onVideoAdPlayStart — 广告播放开始（日志记录）
    // ======================================================================
    private void hookRewardAdPlayStart(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass("com.windmill.sdk.reward.WMRewardAd", cl);
            Class<?> adInfoCls = XposedHelpers.findClass("com.windmill.sdk.models.AdInfo", cl);
            XposedHelpers.findAndHookMethod(cls, "onVideoAdPlayStart", adInfoCls, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            LogServer.log("[播放] 视频播放中，等待奖励...");
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("趣看Hook [Hook] onVideoAdPlayStart 注册失败: " + t);
        }
    }

    // ======================================================================
    // Hook 5: WMRewardAd.onVideoAdReward — 奖励已到达
    // ======================================================================
    private void hookRewardAdReward(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass("com.windmill.sdk.reward.WMRewardAd", cl);
            Class<?> adInfoCls = XposedHelpers.findClass("com.windmill.sdk.models.AdInfo", cl);
            Class<?> rewardInfoCls = XposedHelpers.findClass("com.windmill.sdk.reward.WMRewardInfo", cl);
            XposedHelpers.findAndHookMethod(cls, "onVideoAdReward",
                    adInfoCls, rewardInfoCls,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            LogServer.log("[奖励] WMRewardAd.onVideoAdReward 已触发（SDK 层）");
                        }
                    });
            XposedBridge.log("趣看Hook [Hook] onVideoAdReward 注册成功");
        } catch (Throwable t) {
            XposedBridge.log("趣看Hook [Hook] onVideoAdReward 注册失败: " + t);
        }
    }

    // ======================================================================
    // Hook 6: WMRewardAd.onVideoAdPlayEnd — 广告播放结束
    // ======================================================================
    private void hookRewardAdPlayEnd(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass("com.windmill.sdk.reward.WMRewardAd", cl);
            Class<?> adInfoCls = XposedHelpers.findClass("com.windmill.sdk.models.AdInfo", cl);
            XposedHelpers.findAndHookMethod(cls, "onVideoAdPlayEnd", adInfoCls,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            LogServer.log("[播放结束] 视频播放已结束");
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("趣看Hook [Hook] onVideoAdPlayEnd 注册失败: " + t);
        }
    }

    // ======================================================================
    // Hook 7: WMRewardAd.onVideoAdClosed — 广告关闭
    // ======================================================================
    private void hookRewardAdClosed(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass("com.windmill.sdk.reward.WMRewardAd", cl);
            Class<?> adInfoCls = XposedHelpers.findClass("com.windmill.sdk.models.AdInfo", cl);
            XposedHelpers.findAndHookMethod(cls, "onVideoAdClosed", adInfoCls,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            LogServer.log("[关闭] 广告已关闭 → 清除缓存，重新加载新广告");

                            try {
                                Class<?> lCls = XposedHelpers.findClass("com.example.advertisinglibrary.ad.l", param.thisObject.getClass().getClassLoader());
                                Object lInst = XposedHelpers.callStaticMethod(lCls, "l");
                                XposedHelpers.setObjectField(lInst, "a", null);
                                LogServer.log("[关闭] ✓ 已清除广告缓存 (l.a = null)");

                                h().postDelayed(() -> {
                                    try {
                                        Activity act = activityRef != null ? activityRef.get() : null;
                                        if (act != null && !act.isFinishing()) {
                                            XposedHelpers.callMethod(lInst, "q", act);
                                            LogServer.log("[关闭] ✓ 已触发重新加载激励视频");
                                        }
                                    } catch (Throwable t) {
                                        LogServer.log("[关闭] 重新加载失败: " + t.getMessage());
                                    }
                                }, 3000);
                            } catch (Throwable t) {
                                LogServer.log("[关闭] 清除缓存失败: " + t.getMessage());
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("趣看Hook [Hook] onVideoAdClosed 注册失败: " + t);
        }
    }
}
