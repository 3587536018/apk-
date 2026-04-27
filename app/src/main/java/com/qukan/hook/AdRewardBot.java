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

                while (!stopRequested) {
                    try {
                        int injected = injectAds();
                        if (injected > 0) {
                            botSuccessCount++;
                            LogServer.botLog("[Bot] ★ 注入 " + injected + " 条广告 (待刷: " + botPendingCoins + ", 总计: " + botTotalCoins + ", 轮次: " + botSuccessCount + ")");

                            // 累积达到 6000 金币时触发刷币
                            if (botPendingCoins >= 6000) {
                                LogServer.botLog("[Bot] 💰 待刷金币达 " + botPendingCoins + "，触发刷币...");
                                boolean flushOk = false;
                                try {
                                    LogServer.TriggerCallback cb = LogServer.getTriggerCallback();
                                    if (cb != null) {
                                        String trigResult = cb.trigger();
                                        LogServer.botLog("[Bot] 💰 刷币结果: " + trigResult);
                                        flushOk = trigResult != null && (trigResult.contains("Triggered") || trigResult.contains("成功"));
                                    } else {
                                        LogServer.botLog("[Bot] ⚠ 刷币回调未就绪，跳过");
                                    }
                                } catch (Exception te) {
                                    LogServer.botLog("[Bot] ⚠ 触发刷币异常: " + te.getMessage());
                                }
                                if (flushOk) {
                                    botTotalCoins += botPendingCoins;
                                    LogServer.botLog("[Bot] ✅ 刷币成功! 本轮 " + botPendingCoins + " 金币已入账 (总计: " + botTotalCoins + ")");
                                } else {
                                    LogServer.botLog("[Bot] ⚠ 刷币未成功，" + botPendingCoins + " 金币暂不计入");
                                }
                                botPendingCoins = 0;
                                LogServer.botLog("[Bot] ⏳ 冷却 20 秒");
                                sleep(20000);
                            } else {
                                int delay = randomInt(8, 18) * 1000;
                                sleep(delay);
                            }
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

    /**
     * 生成广告数据并通过 w.b() 注入 App 广告列表
     * @return 注入成功的条数
     */
    private static int injectAds() throws Exception {
        // 插屏广告配置池: {networkName, placementId, ecpmMin, ecpmMax}
        String[][] adPool = {
            {"gromore", "103956914", "2500", "9000"},
            {"gdt", "6268450130179263", "5000", "15000"},
            {"gdt", "5278953120871194", "5000", "10000"},
            {"gromore", "103955254", "5000", "10000"},
            {"baidu", "19233026", "2500", "10000"},
            {"kuaishou", "32194000061", "2500", "10000"},
            {"gdt", "7208457110579137", "2500", "10000"},
            {"gdt", "7238452140874155", "2500", "10000"},
            {"gdt", "7238757190673126", "2500", "10000"},
            {"kuaishou", "32194000066", "5000", "10000"},
            {"kuaishou", "32194000064", "5000", "10000"},
        };

        // 随机 2~4 条插屏
        int total = randomInt(2, 4);
        int injected = 0;
        int roundCoins = 0;
        StringBuilder logMsg = new StringBuilder("[Bot] 📊 生成 " + total + " 条插屏广告:");

        for (int i = 0; i < total; i++) {
            String[] ad = adPool[random.nextInt(adPool.length)];
            int ecpmMin = Integer.parseInt(ad[2]);
            int ecpmMax = Integer.parseInt(ad[3]);
            int ecpm = randomInt(ecpmMin, ecpmMax);
            String loadId = UUID.randomUUID().toString();

            try {
                // 调用 w.b(loadId, ecpm, "interrupt", networkName, placementId, "", "")
                Object result = methodB.invoke(wInstance,
                        loadId,         // loadId
                        ecpm,           // ecpm (int)
                        "interrupt",    // position = 插屏
                        ad[0],          // networkName
                        ad[1],          // networkPlacementId
                        "",             // appUserId
                        ""              // extraInfo
                );

                float goldNumber = (result instanceof Float) ? (Float) result : 0f;
                int amount = (int) goldNumber;
                injected++;
                roundCoins += amount;

                logMsg.append(" ").append(ad[0]).append(" ecpm=").append(ecpm).append(" gold=").append(amount);
            } catch (Throwable t) {
                LogServer.botLog("[Bot] ✗ 注入第 " + (i + 1) + " 条失败: " + t.getMessage());
            }

            // 模拟广告展示间隔（6~15秒）
            if (i < total - 1) {
                Thread.sleep(randomInt(6, 15) * 1000L);
            }
        }

        LogServer.botLog(logMsg.toString());
        botPendingCoins += roundCoins;
        return injected;
    }

    // === 工具方法 ===

    private static int randomInt(int min, int max) {
        return min + random.nextInt(max - min + 1);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
