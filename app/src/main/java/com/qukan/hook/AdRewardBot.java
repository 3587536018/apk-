package com.qukan.hook;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Random;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 广告奖励 API 请求器 — 移植自 趣玩.py
 * 直接向服务器提交虚拟广告观看记录获取金币奖励
 * 使用设备中已注入的 Token，无需代理
 */
public class AdRewardBot {

    private static final String API_URL = "https://hd.dffhq.top/g/GetAdrewardCoins.ashx";
    private static final byte[] AES_KEY = hexToBytes("44ce8abb00ab82421c6f59594af37fe1");
    private static final byte[] AES_IV  = hexToBytes("0dbeddeefe601ac142bf01ac790d7833");

    // 控制开关和状态
    public static volatile boolean botRunning = false;
    private static volatile boolean stopRequested = false;
    private static Thread botThread = null;

    // 统计
    public static volatile int botSuccessCount = 0;
    public static volatile int botFailCount = 0;
    public static volatile int botTotalCoins = 0;    // 刷币成功后才计入
    public static volatile int botPendingCoins = 0;  // 服务端累积的待刷金币

    // eCPM 范围配置
    private static final int FEED_ECPM_MIN = 500;
    private static final int FEED_ECPM_MAX = 2000;
    private static final int INTER_ECPM_MIN = 5000;
    private static final int INTER_ECPM_MAX = 15000;

    private static final Random random = new Random();

    /**
     * 启动循环请求（后台线程）
     */
    public static void start() {
        if (botRunning) {
            LogServer.botLog("[Bot] ⚠ 已在运行中，忽略重复启动");
            return;
        }
        stopRequested = false;
        botThread = new Thread(() -> {
            botRunning = true;
            LogServer.botLog("[Bot] ▶ 广告奖励 Bot 已启动");
            try {
                while (!stopRequested) {
                    String token = MainHook.customToken;
                    if (token == null || token.isEmpty()) {
                        LogServer.botLog("[Bot] ✗ Token 未设置，等待 30 秒后重试...");
                        sleep(30000);
                        continue;
                    }

                    JSONObject result = sendRequest(token);
                    int code = result.optInt("Code", 0);

                    if (code == 200) {
                        botSuccessCount++;
                        int coins = 0;
                        JSONObject data = result.optJSONObject("Data");
                        if (data != null) {
                            coins = data.optInt("coins", 0);
                            botPendingCoins = coins;
                        }
                        LogServer.botLog("[Bot] ★ 请求成功! 累积 " + coins + " 金币 (待刷: " + botPendingCoins + ", 已刷: " + botTotalCoins + ", 请求: " + botSuccessCount + ")");

                        // 服务端累积达到 6000 金币时触发刷币
                        int delay = randomInt(8, 18) * 1000;
                        if (botPendingCoins >= 6000) {
                            LogServer.botLog("[Bot] 💰 待刷金币达 " + botPendingCoins + "，触发刷币...");
                            boolean flushOk = false;
                            try {
                                LogServer.TriggerCallback cb = LogServer.getTriggerCallback();
                                if (cb != null) {
                                    String trigResult = cb.trigger();
                                    LogServer.botLog("[Bot] 💰 刷币结果: " + trigResult);
                                    // 刷币成功才计入真实收益
                                    flushOk = trigResult != null && trigResult.contains("成功");
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
                            delay = 20000;
                            LogServer.botLog("[Bot] ⏳ 冷却 20 秒");
                        }
                        sleep(delay);
                    } else {
                        botFailCount++;
                        String msg = result.optString("Message", "未知错误");
                        LogServer.botLog("[Bot] ✗ 失败 (" + code + "): " + msg + " (失败: " + botFailCount + ")");
                        sleep(40000);
                    }
                }
            } catch (Exception e) {
                LogServer.botLog("[Bot] ✗ 异常退出: " + e.getMessage());
            } finally {
                botRunning = false;
                LogServer.botLog("[Bot] ■ 广告奖励 Bot 已停止");
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
     * 发送一次广告奖励请求
     */
    private static JSONObject sendRequest(String bearer) {
        try {
            String encryptedBody = buildAndEncrypt();

            URL url = new URL(API_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "okhttp/4.9.0");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("accept-language", "zh-CN");
            conn.setRequestProperty("authorization", "Bearer " + bearer);

            // 写入加密数据
            byte[] bodyBytes = encryptedBody.getBytes("UTF-8");
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
            OutputStream os = conn.getOutputStream();
            os.write(bodyBytes);
            os.flush();
            os.close();

            // 读取响应（自动处理 gzip）
            int responseCode = conn.getResponseCode();
            InputStream is = responseCode < 400 ? conn.getInputStream() : conn.getErrorStream();
            // HttpURLConnection 会自动解压 gzip
            String resp = readString(is);
            conn.disconnect();

            return new JSONObject(resp);
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("Code", 0);
                err.put("Message", "请求异常: " + e.getMessage());
                return err;
            } catch (Exception ex) {
                return new JSONObject();
            }
        }
    }

    /**
     * 构建广告参数、签名并 AES-CBC 加密
     */
    private static String buildAndEncrypt() throws Exception {
        long nowMs = System.currentTimeMillis();

        // 广告配置池（与趣玩.py完全同步）
        // 格式: {admodel_id, admodel_value, adplatform_name, adtype_id, adtype_name, network_placement_id, ecpm_min, ecpm_max}
        String[][] adPool = {
            // 信息流广告
            {"64", "8983638867376500", "gdt", "64", "信息流广告", "9248159160678469", "600", "1500"},
            {"64", "8983638867376500", "gromore", "64", "信息流广告", "103956908", "600", "1500"},
            {"64", "8983638867376500", "kuaishou", "64", "信息流广告", "32194000050", "600", "1500"},
            {"64", "8983638867376500", "baidu", "64", "信息流广告", "19232858", "600", "1500"},
            {"64", "8983638867376500", "kuaishou", "64", "信息流广告", "32194000051", "600", "1500"},
            // Banner广告
            {"63", "5169829687932834", "gdt", "63", "Banner广告", "5278357170370989", "200", "2500"},
            {"63", "5169829687932834", "gromore", "63", "Banner广告", "103955933", "400", "2500"},
            {"63", "5169829687932834", "kuaishou", "63", "Banner广告", "32194000012", "200", "2600"},
            {"63", "5169829687932834", "kuaishou", "63", "Banner广告", "32194000014", "500", "1500"},
            // 插屏广告
            {"65", "5491239561962900", "gromore", "65", "插屏广告", "103956914", "2500", "9000"},
            {"65", "5491239561962900", "gdt", "65", "插屏广告", "6268450130179263", "5000", "15000"},
            {"65", "5491239561962900", "gdt", "65", "插屏广告", "5278953120871194", "5000", "10000"},
            {"65", "5491239561962900", "gromore", "65", "插屏广告", "103955254", "5000", "10000"},
            {"65", "5491239561962900", "baidu", "65", "插屏广告", "19233026", "2500", "10000"},
            {"65", "5491239561962900", "kuaishou", "65", "插屏广告", "32194000061", "2500", "10000"},
            {"65", "5491239561962900", "gdt", "65", "插屏广告", "7208457110579137", "2500", "10000"},
            {"65", "5491239561962900", "gdt", "65", "插屏广告", "7238452140874155", "2500", "10000"},
            {"65", "5491239561962900", "gdt", "65", "插屏广告", "7238757190673126", "2500", "10000"},
            {"65", "5491239561962900", "kuaishou", "65", "插屏广告", "32194000066", "5000", "10000"},
            {"65", "5491239561962900", "kuaishou", "65", "插屏广告", "32194000064", "5000", "10000"},
        };

        // 按类型分组
        java.util.List<String[]> interPool = new java.util.ArrayList<>();  // 插屏 65
        java.util.List<String[]> feedPool = new java.util.ArrayList<>();   // 信息流 64
        java.util.List<String[]> bannerPool = new java.util.ArrayList<>(); // Banner 63
        for (String[] ad : adPool) {
            switch (ad[3]) {
                case "65": interPool.add(ad); break;
                case "64": feedPool.add(ad); break;
                case "63": bannerPool.add(ad); break;
            }
        }

        // 先随机总条数
        int[] totalChoices = {2, 3, 4};
        int total = totalChoices[random.nextInt(totalChoices.length)];
        // 插屏条数：总数>2时随机1-2条，否则固定1条
        int interCount = total > 2 ? (1 + random.nextInt(2)) : 1;
        int extraCount = total - interCount;

        // 选插屏
        java.util.List<String[]> selected = new java.util.ArrayList<>();
        for (int e = 0; e < interCount; e++) {
            selected.add(interPool.get(random.nextInt(interPool.size())));
        }
        // 补充信息流或Banner（信息流概率65%）
        for (int e = 0; e < extraCount; e++) {
            if (random.nextDouble() < 0.65) {
                selected.add(feedPool.get(random.nextInt(feedPool.size())));
            } else {
                selected.add(bannerPool.get(random.nextInt(bannerPool.size())));
            }
        }

        java.util.Collections.shuffle(selected, random);

        JSONArray details = new JSONArray();
        StringBuilder logMsg = new StringBuilder("[Bot] 📊 生成 " + selected.size() + " 条广告:");

        for (int i = 0; i < selected.size(); i++) {
            String[] ad = selected.get(i);
            int ecpmMin = Integer.parseInt(ad[6]);
            int ecpmMax = Integer.parseInt(ad[7]);
            int ecpm = randomInt(ecpmMin, ecpmMax);
            double realMoney = ecpm / 100000.0;
            int amount = (int) (realMoney * 0.60 * 10000);

            // real_money 格式：Java Double.toString() 自动去尾零和科学计数法
            String realMoneyStr = String.valueOf(realMoney);

            JSONObject item = new JSONObject();
            item.put("admodel_id", ad[0]);
            item.put("admodel_value", ad[1]);
            item.put("adplatform_name", ad[2]);
            item.put("adtype_id", ad[3]);
            item.put("adtype_name", ad[4]);
            item.put("amount", String.valueOf(amount));
            item.put("appUserId", "");
            item.put("displayed_at", String.valueOf(nowMs + i * randomInt(1000, 5000)));
            item.put("ecpm", String.valueOf(ecpm));
            item.put("exchange_rate", "10000");
            item.put("extraInfo", "");
            item.put("loadId", UUID.randomUUID().toString());
            item.put("network_placement_id", ad[5]);
            item.put("real_money", realMoneyStr);
            item.put("reward_rate", "0.60");
            details.put(item);

            logMsg.append(" ").append(ad[4]).append("(").append(ad[2]).append(") ecpm=").append(ecpm).append(" amt=").append(amount);

            // 模拟广告展示间隔（6~15秒），与 Python 端一致
            if (i < selected.size() - 1) {
                Thread.sleep(randomInt(6, 15) * 1000L);
            }
        }
        LogServer.botLog(logMsg.toString());

        // 签名
        long timestamp = System.currentTimeMillis() / 1000;
        String detailsStr = details.toString();
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 32);
        String combo = token + detailsStr + timestamp;

        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(combo.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b & 0xff));
        }
        String signature = sb.toString();

        // 构建最终 payload
        JSONObject payload = new JSONObject();
        payload.put("details", detailsStr);
        payload.put("signature", signature);
        payload.put("timestamp", timestamp);
        payload.put("token", token);
        String plain = payload.toString();

        // AES-CBC 加密（使用 PKCS5Padding 自动处理填充）
        byte[] plainBytes = plain.getBytes("UTF-8");
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(AES_KEY, "AES"),
                new IvParameterSpec(AES_IV));
        byte[] encrypted = cipher.doFinal(plainBytes);
        return Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    // === 工具方法 ===

    private static int randomInt(int min, int max) {
        return min + random.nextInt(max - min + 1);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static String readString(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    private static byte[] hexToBytes(String hex) {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }
}
