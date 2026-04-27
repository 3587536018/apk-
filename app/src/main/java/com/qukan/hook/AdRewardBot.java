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
    public static volatile int botTotalCoins = 0;

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
                            botTotalCoins += coins;
                        }
                        LogServer.botLog("[Bot] ★ 成功! 获得 " + coins + " 金币 (累计: " + botTotalCoins + ", 成功: " + botSuccessCount + ")");

                        // 如果获得 6000 金币，额外等待
                        int delay = randomInt(8, 18) * 1000;
                        if (coins == 6000) {
                            delay = 20000;
                            LogServer.botLog("[Bot] ⏳ 高额奖励，冷却 20 秒");
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
            conn.setRequestProperty("Accept-Encoding", "gzip");
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

            // 读取响应
            int responseCode = conn.getResponseCode();
            InputStream is = responseCode < 400 ? conn.getInputStream() : conn.getErrorStream();
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

        // 生成信息流 eCPM
        int ecpmFeed = randomInt(FEED_ECPM_MIN, FEED_ECPM_MAX);
        if (random.nextDouble() > 0.6) {
            ecpmFeed = Math.round(ecpmFeed / 100f) * 100;
        }
        double moneyFeed = ecpmFeed / 10000.0;

        // 生成插屏 eCPM
        int ecpmInter = randomInt(INTER_ECPM_MIN, INTER_ECPM_MAX);
        if (random.nextDouble() > 0.6) {
            ecpmInter = Math.round(ecpmInter / 100f) * 100;
        }
        double moneyInter = ecpmInter / 10000.0;
        int interAmount = (int) (moneyInter * 10000);

        LogServer.botLog("[Bot] 📊 参数: 信息流 ecpm=" + ecpmFeed + " 插屏 ecpm=" + ecpmInter);

        // 构建广告详情 JSON 数组
        JSONArray details = new JSONArray();

        // 信息流广告
        JSONObject feed = new JSONObject();
        feed.put("admodel_id", "64");
        feed.put("admodel_value", "8983638867376500");
        feed.put("adplatform_name", "gdt");
        feed.put("adtype_id", "64");
        feed.put("adtype_name", "信息流广告");
        feed.put("amount", "0");
        feed.put("appUserId", "");
        feed.put("displayed_at", String.valueOf(nowMs));
        feed.put("ecpm", String.valueOf(ecpmFeed));
        feed.put("exchange_rate", "10000");
        feed.put("extraInfo", "");
        feed.put("loadId", UUID.randomUUID().toString());
        feed.put("network_placement_id", "9248159160678469");
        feed.put("real_money", String.format("%.10f", moneyFeed));
        feed.put("reward_rate", "0.60");
        details.put(feed);

        // 插屏广告
        JSONObject inter = new JSONObject();
        inter.put("admodel_id", "65");
        inter.put("admodel_value", "5491239561962900");
        inter.put("adplatform_name", "kuaishou");
        inter.put("adtype_id", "65");
        inter.put("adtype_name", "插屏广告");
        inter.put("amount", String.valueOf(interAmount));
        inter.put("appUserId", "");
        inter.put("displayed_at", String.valueOf(nowMs + randomInt(300, 800)));
        inter.put("ecpm", String.valueOf(ecpmInter));
        inter.put("exchange_rate", "10000");
        inter.put("extraInfo", "");
        inter.put("loadId", UUID.randomUUID().toString());
        inter.put("network_placement_id", "32194000066");
        inter.put("real_money", String.format("%.10f", moneyInter));
        inter.put("reward_rate", "0.60");
        details.put(inter);

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
