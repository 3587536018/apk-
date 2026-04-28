package com.qukan.hook;

import java.util.Random;
import java.util.UUID;

/**
 * 广告奖励 Bot — 反射调用 App 原生方法
 * 调用 C8619w.m40496b() (实际: w.b()) 将广告数据注入 App 内部列表
 * 所有金币计算、reward_rate、exchange_rate 由 App 原生代码处理
 */
public class AdRewardBot {

    // 控制开关和状态
    public static volatile boolean botRunning = false;
    private static volatile boolean stopRequested = false;
    private static Thread botThread = null;

    // 统计
    public static volatile int botSuccessCount = 0;
    public static volatile int botFailCount = 0;
    public static volatile int botTotalCoins = 0;    // 刷币成功后才计入
    public static volatile int botPendingCoins = 0;  // 当前轮次待刷金币

    // 反射缓存
    private static Object wInstance = null;         // C8619w 单例 (w.a)
    private static java.lang.reflect.Method methodB = null; // w.b(String,int,String,String,String,String,String)

    private static final Random random = new Random();
    private static long lastSubmitTime = 0;      // 上次提交时间
    private static int nextSubmitInterval = 0;   // 下次提交间隔(ms)

    /**
     * 启动循环注入（后台线程）
     */
    public static void start() {
        if (botRunning) {
            LogServer.botLog("[Bot] ⚠ 已在运行中，忽略重复启动");
            return;
        }
        stopRequested = false;
        botThread = new Thread(() -> {
            botRunning = true;
            LogServer.botLog("[Bot] ▶ 广告注入 Bot 已启动（反射调用模式）");
            try {
                // 等待 App ClassLoader 就绪
                while (MainHook.appClassLoader == null && !stopRequested) {
                    LogServer.botLog("[Bot] 等待 App ClassLoader...");
                    sleep(3000);
                }
                if (stopRequested) return;

                // 延迟让 App 完成广告配置初始化
                sleep(8000);

                // 初始化反射
                if (!initReflection()) {
                    LogServer.botLog("[Bot] ✗ 反射初始化失败，Bot 退出");
                    return;
                }

                // 初始化提交计时
                lastSubmitTime = System.currentTimeMillis();
                nextSubmitInterval = randomInt(20, 30) * 1000;

                while (!stopRequested) {
                    try {
                        int coins = injectOneAd();
                        if (coins >= 0) {
                            botSuccessCount++;
                            botPendingCoins += coins;
                            LogServer.botLog("[Bot] ★ 注入成功 gold=" + coins + " (累计: " + botPendingCoins + ", 第 " + botSuccessCount + " 条)");

                            // 检查是否到提交时间
                            long elapsed = System.currentTimeMillis() - lastSubmitTime;
                            if (elapsed >= nextSubmitInterval && botPendingCoins > 0) {
                                submitRewards();
                            }

                            // 每条广告间隔 8~18 秒
                            int delay = randomInt(8, 18) * 1000;
                            sleep(delay);
                        } else {
                            botFailCount++;
                            LogServer.botLog("[Bot] ✗ 注入失败 (失败: " + botFailCount + ")");
                            sleep(30000);
                        }
                    } catch (Exception e) {
                        botFailCount++;
                        LogServer.botLog("[Bot] ✗ 注入异常: " + e.getMessage());
                        sleep(30000);
                    }
                }
            } catch (Exception e) {
                LogServer.botLog("[Bot] ✗ 异常退出: " + e.getMessage());
            } finally {
                botRunning = false;
                LogServer.botLog("[Bot] ■ 广告注入 Bot 已停止");
            }
        }, "AdRewardBot");
        botThread.setDaemon(true);
        botThread.start();
    }

    /**
     * 停止循环
     */
    public static void stop() {
        if (!botRunning) {
            LogServer.botLog("[Bot] ⚠ Bot 未在运行");
            return;
        }
        stopRequested = true;
        LogServer.botLog("[Bot] ■ 正在停止...");
        if (botThread != null) {
            botThread.interrupt();
        }
    }

    /**
     * 初始化反射：获取 w 类单例和 b 方法
     * 实际类: com.example.advertisinglibrary.util.w
     * 单例字段: a (static final)
     * 方法: b(String loadId, int ecpm, String position, String networkName,
     *        String networkPlacementId, String appUserId, String extraInfo)
     */
    private static boolean initReflection() {
        try {
            ClassLoader cl = MainHook.appClassLoader;

            // 加载混淆后的类: com.example.advertisinglibrary.util.w
            Class<?> wCls = cl.loadClass("com.example.advertisinglibrary.util.w");

            // 获取单例字段 a
            java.lang.reflect.Field fieldA = wCls.getDeclaredField("a");
            fieldA.setAccessible(true);
            wInstance = fieldA.get(null);

            // 获取方法 b(String, int, String, String, String, String, String) → float
            methodB = wCls.getDeclaredMethod("b",
                    String.class, int.class, String.class, String.class,
                    String.class, String.class, String.class);
            methodB.setAccessible(true);

            LogServer.botLog("[Bot] ✓ 反射初始化成功: w.a=" + wInstance + ", w.b=" + methodB);
            return true;
        } catch (Throwable t) {
            LogServer.botLog("[Bot] ✗ 反射初始化异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    // 插屏广告配置池: {networkName, placementId, ecpmMin, ecpmMax}
    private static final String[][] AD_POOL = {
        {"gromore", "103956914", "2500", "9000"},
        {"gdt", "6268450130179263", "2500", "9000"},
        {"gdt", "5278953120871194", "2500", "9000"},
        {"gromore", "103955254", "2500", "9000"},
        {"baidu", "19233026", "2500", "9000"},
        {"kuaishou", "32194000061", "2500", "9000"},
        {"gdt", "7208457110579137", "2500", "9000"},
        {"gdt", "7238452140874155", "2500", "9000"},
        {"gdt", "7238757190673126", "2500", "9000"},
        {"kuaishou", "32194000066", "2500", "9000"},
        {"kuaishou", "32194000064", "2500", "9000"},
    };

    /**
     * 注入 1 条插屏广告（通过 w.b()）
     * @return 本次获得的金币数，-1 表示失败
     */
    private static int injectOneAd() throws Exception {
        String[] ad = AD_POOL[random.nextInt(AD_POOL.length)];
        int ecpm = randomInt(Integer.parseInt(ad[2]), Integer.parseInt(ad[3]));
        String loadId = UUID.randomUUID().toString();

        Object result = methodB.invoke(wInstance,
                loadId, ecpm, "interrupt", ad[0], ad[1], "", "");

        float goldNumber = (result instanceof Float) ? (Float) result : 0f;
        int amount = (int) goldNumber;
        LogServer.botLog("[Bot] 📊 插屏 " + ad[0] + " ecpm=" + ecpm + " gold=" + amount);
        return amount;
    }

    /**
     * 提交累计奖励到服务器：调用 getMVM().postAdreWards()
     */
    private static void submitRewards() {
        try {
            // 通过 MainHook 保存的 fragmentRef 获取 RedPacketFragment
            java.lang.ref.WeakReference<?> fRef = MainHook.getInstance() != null
                    ? MainHook.getInstance().getFragmentRef() : null;
            Object fragment = fRef != null ? fRef.get() : null;
            if (fragment == null) {
                LogServer.botLog("[Bot] ⚠ Fragment 未就绪，跳过提交");
                return;
            }

            java.lang.reflect.Method getMVM = fragment.getClass().getMethod("getMVM");
            getMVM.setAccessible(true);
            Object viewModel = getMVM.invoke(fragment);
            if (viewModel != null) {
                java.lang.reflect.Method post = viewModel.getClass().getDeclaredMethod("postAdreWards");
                post.setAccessible(true);
                post.invoke(viewModel);
                int submitted = botPendingCoins;
                botTotalCoins += submitted;
                botPendingCoins = 0;
                LogServer.botLog("[Bot] ✅ 提交成功! 本次: " + submitted + " 总计: " + botTotalCoins);
            }
        } catch (Throwable t) {
            LogServer.botLog("[Bot] ✗ 提交异常: " + t.getMessage());
        }
        // 重置计时
        lastSubmitTime = System.currentTimeMillis();
        nextSubmitInterval = randomInt(20, 30) * 1000;
        LogServer.botLog("[Bot] ⏱ 下次提交: " + (nextSubmitInterval / 1000) + "秒后");
    }

    // === 工具方法 ===

    private static int randomInt(int min, int max) {
        return min + random.nextInt(max - min + 1);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
