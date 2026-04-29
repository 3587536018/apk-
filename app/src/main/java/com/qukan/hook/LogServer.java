package com.qukan.hook;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

/**
 * 纯 ServerSocket 实现的 HTTP 日志服务器，零外部依赖
 * 访问 http://<设备IP>:6789     — HTML 实时日志页（5s 自动刷新）
 * 访问 http://<设备IP>:6789/json — JSON 格式日志
 * 访问 http://<设备IP>:6789/clear — 清空日志
 */
public class LogServer {

    private static final int PORT = 6789;
    static volatile int actualPort = PORT;   // 实际绑定的端口（可能 fallback 到其他）
    private static final int MAX_LINES = 500;
    private static final SimpleDateFormat SDF =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA);

    private static final List<String> logs =
            Collections.synchronizedList(new LinkedList<>());

    // Bot 独立日志列表
    private static final List<String> botLogs =
            Collections.synchronizedList(new LinkedList<>());

    private static volatile boolean started = false;

    // 主动触发广告流程的回调接口
    public interface TriggerCallback {
        String trigger();   // 返回操作结果描述
        String status();    // 返回当前状态 JSON
    }

    private static volatile TriggerCallback triggerCallback = null;

    public static void setTriggerCallback(TriggerCallback cb) {
        triggerCallback = cb;
    }

    public static TriggerCallback getTriggerCallback() {
        return triggerCallback;
    }

    // ---------------------------------------------------------------
    // 公共接口：添加日志（同时写 XposedBridge）
    // ---------------------------------------------------------------
    public static void log(String msg) {
        String line = SDF.format(new Date()) + "  " + msg;
        synchronized (logs) {
            logs.add(line);
            while (logs.size() > MAX_LINES) logs.remove(0);
        }
        XposedBridge.log("QukanHook " + msg);
    }

    // Bot 专用日志（不混入主日志）
    public static void botLog(String msg) {
        String line = SDF.format(new Date()) + "  " + msg;
        synchronized (botLogs) {
            botLogs.add(line);
            while (botLogs.size() > MAX_LINES) botLogs.remove(0);
        }
    }

    // ---------------------------------------------------------------
    // 启动 HTTP 服务器（在后台线程）
    // ---------------------------------------------------------------
    public static void start() {
        if (started) return;

        Thread t = new Thread(() -> {
            // 尝试端口列表，任意一个成功即可
            int[] ports = {6789, 6790, 6791, 7000};
            ServerSocket ss = null;
            int boundPort = -1;

            for (int p : ports) {
                try {
                    ServerSocket candidate = new ServerSocket();
                    candidate.setReuseAddress(true);
                    candidate.bind(new java.net.InetSocketAddress("0.0.0.0", p));
                    ss = candidate;
                    boundPort = p;
                    break;
                } catch (IOException e) {
                    XposedBridge.log("QukanHook LogServer 端口 " + p + " 绑定失败: "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }

            if (ss == null) {
                XposedBridge.log("QukanHook LogServer 所有端口均失败，放弃");
                return;   // 不设置 started=true，允许外部重试
            }

            started = true;
            String ip = getLocalIp();
            log("========================");
            log("日志服务器已启动!");
            log("http://" + ip + ":" + boundPort);
            log("========================");
            // 把实际端口写回常量（便于 HTML 显示）
            actualPort = boundPort;

            final ServerSocket finalSs = ss;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Socket client = finalSs.accept();
                    new Thread(() -> handle(client)).start();
                } catch (IOException ignored) {}
            }
        }, "QukanLogServer");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------
    // 处理单个 HTTP 请求
    // ---------------------------------------------------------------
    private static void handle(Socket client) {
        try {
            client.setSoTimeout(3000);
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(client.getInputStream()));

            // 读请求行
            String requestLine = br.readLine();
            if (requestLine == null) { client.close(); return; }

            // 读 headers，获取 Content-Length
            String headerLine;
            int contentLength = 0;
            while ((headerLine = br.readLine()) != null && !headerLine.isEmpty()) {
                if (headerLine.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(headerLine.substring(15).trim());
                }
            }

            // 读 POST body（如有）
            String postBody = "";
            if (contentLength > 0) {
                char[] bodyChars = new char[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int r = br.read(bodyChars, read, contentLength - read);
                    if (r == -1) break;
                    read += r;
                }
                postBody = new String(bodyChars, 0, read);
            }

            String path = "/";
            String[] parts = requestLine.split(" ");
            if (parts.length >= 2) path = parts[1];

            OutputStream out = client.getOutputStream();

            if (path.startsWith("/clear")) {
                logs.clear();
                log("日志已清空");
                sendRedirect(out, "/");
            } else if (path.startsWith("/login")) {
                // 扫码登录页面
                String html = QrLoginHandler.getLoginHtml();
                writeHttp(out, "200 OK", "text/html; charset=utf-8", html.getBytes("UTF-8"));
            } else if (path.startsWith("/get_qrcode")) {
                // 获取微信二维码
                String query = path.contains("?") ? path.substring(path.indexOf("?") + 1) : "";
                String oaid = "";
                for (String param : query.split("&")) {
                    String[] kv = param.split("=");
                    if (kv.length >= 2 && kv[0].equals("oaid")) {
                        oaid = java.net.URLDecoder.decode(kv[1], "UTF-8");
                    }
                }
                String result = QrLoginHandler.handleGetQrcode(oaid);
                writeHttp(out, "200 OK", "application/json; charset=utf-8", result.getBytes("UTF-8"));
            } else if (path.startsWith("/poll")) {
                // 轮询扫码状态
                String query = path.contains("?") ? path.substring(path.indexOf("?") + 1) : "";
                String flowId = "";
                for (String param : query.split("&")) {
                    String[] kv = param.split("=");
                    if (kv.length >= 2 && kv[0].equals("flow_id")) {
                        flowId = java.net.URLDecoder.decode(kv[1], "UTF-8");
                    }
                }
                String result = QrLoginHandler.handlePoll(flowId);
                writeHttp(out, "200 OK", "application/json; charset=utf-8", result.getBytes("UTF-8"));
            } else if (path.startsWith("/set_creds")) {
                String query = "";
                if (path.contains("?")) {
                    query = path.substring(path.indexOf("?") + 1);
                }
                String oaid = null;
                String token = null;
                String userJson = null;
                for (String param : query.split("&")) {
                    String[] kv = param.split("=");
                    if (kv.length >= 2) {
                        try {
                            String key = java.net.URLDecoder.decode(kv[0], "UTF-8");
                            String val = java.net.URLDecoder.decode(param.substring(kv[0].length() + 1), "UTF-8");
                            if (key.equals("oaid")) oaid = val;
                            if (key.equals("token")) token = val;
                            if (key.equals("user_json")) userJson = val;
                        } catch (Exception e) {}
                    }
                }
                MainHook.saveCreds(oaid, token, userJson);
                log("[Inject] 已持久化更新内存凭证 (重启不丢失)");
                String body = "{\"result\":\"更新成功\"}";
                writeHttp(out, "200 OK", "application/json; charset=utf-8", body.getBytes("UTF-8"));
            } else if (path.startsWith("/toggle_skip")) {
                // 切换跳过广告开关
                MainHook.skipAdEnabled = !MainHook.skipAdEnabled;
                log("[Config] 跳过广告: " + (MainHook.skipAdEnabled ? "已开启" : "已关闭"));
                String body = "{\"skipAdEnabled\":" + MainHook.skipAdEnabled + "}";
                writeHttp(out, "200 OK", "application/json; charset=utf-8", body.getBytes("UTF-8"));
            } else if (path.startsWith("/toggle_blockads")) {
                // 切换非激励广告拦截开关
                MainHook.blockNonRewardAds = !MainHook.blockNonRewardAds;
                log("[Config] 插屏拦截: " + (MainHook.blockNonRewardAds ? "已开启" : "已关闭"));
                String body = "{\"blockNonRewardAds\":" + MainHook.blockNonRewardAds + "}";
                writeHttp(out, "200 OK", "application/json; charset=utf-8", body.getBytes("UTF-8"));
            } else if (path.startsWith("/bot_start")) {
                AdRewardBot.start();
                String body = "{\"botRunning\":" + AdRewardBot.botRunning + "}";
                writeHttp(out, "200 OK", "application/json; charset=utf-8", body.getBytes("UTF-8"));
            } else if (path.startsWith("/bot_stop")) {
                AdRewardBot.stop();
                String body = "{\"botRunning\":false}";
                writeHttp(out, "200 OK", "application/json; charset=utf-8", body.getBytes("UTF-8"));
            } else if (path.startsWith("/trigger")) {
                // 主动触发一次刷金币流程
                String result;
                if (triggerCallback != null) {
                    result = triggerCallback.trigger();
                } else {
                    result = "未就绪：请先手动触发一次广告激活模块";
                }
                log("[Trigger] 外部触发: " + result);
                String body = "{\"result\":\"" + escJson(result) + "\"}";
                writeHttp(out, "200 OK", "application/json; charset=utf-8",
                        body.getBytes("UTF-8"));
            } else if (path.startsWith("/status")) {
                String json = triggerCallback != null
                        ? triggerCallback.status()
                        : "{\"running\":false,\"ready\":false}";
                writeHttp(out, "200 OK", "application/json; charset=utf-8",
                        json.getBytes("UTF-8"));
            } else if (path.startsWith("/api/poll")) {
                // AJAX 轮询：返回日志和状态 JSON（无需整页刷新）
                sendPollJson(out);
            } else if (path.startsWith("/json")) {
                sendJson(out);
            } else {
                sendHtml(out);
            }

            client.close();
        } catch (Exception ignored) {
            try { client.close(); } catch (Exception e2) { /* ignore */ }
        }
    }

    // ---------------------------------------------------------------
    // HTML 页
    // ---------------------------------------------------------------
    private static void sendHtml(OutputStream out) throws IOException {
        int rewardCount = 0, errCount = 0, blockCount = 0;
        StringBuilder rows = new StringBuilder();

        synchronized (logs) {
            for (int idx = 0; idx < logs.size(); idx++) {
                String l = logs.get(idx);
                if (l.contains("★")) rewardCount++;
                if (l.contains("失败") || l.contains("STOP") || l.contains("异常")) errCount++;
                if (l.contains("[AdBlock]") && l.contains("已拦截")) blockCount++;
                String cls = "log-n";
                if (l.contains("★") || l.contains("Reward")) cls = "log-r";
                else if (l.contains("失败") || l.contains("STOP") || l.contains("异常")) cls = "log-e";
                else if (l.contains("[AdBlock]")) cls = "log-b";
                else if (l.contains("✓") || l.contains("激活") || l.contains("成功")) cls = "log-i";
                rows.append("<div class='log-row ").append(cls).append("'>")
                        .append("<span class='log-dot'></span>").append(escHtml(l)).append("</div>\n");
            }
        }

        // Bot 独立日志
        StringBuilder botRows = new StringBuilder();
        synchronized (botLogs) {
            for (int idx = 0; idx < botLogs.size(); idx++) {
                String l = botLogs.get(idx);
                String cls = "log-n";
                if (l.contains("★") || l.contains("成功")) cls = "log-r";
                else if (l.contains("✗") || l.contains("失败") || l.contains("异常")) cls = "log-e";
                else if (l.contains("📊") || l.contains("参数")) cls = "log-i";
                else if (l.contains("▶") || l.contains("■")) cls = "log-b";
                botRows.append("<div class='log-row ").append(cls).append("'>")
                        .append("<span class='log-dot'></span>").append(escHtml(l)).append("</div>\n");
            }
        }

        boolean skipOn = MainHook.skipAdEnabled;
        boolean blockOn = MainHook.blockNonRewardAds;

        String html = "<!DOCTYPE html><html lang='zh'><head><meta charset='utf-8'>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>QukanHook 控制台</title>" +
            "<style>" +
            "@import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap');" +
            ":root{--bg:#0b0f19;--card:#141a2a;--card2:#1a2236;--border:#1e2a42;--border2:#2a3a5c;" +
            "--text:#e2e8f0;--text2:#8892a8;--accent:#6366f1;--accent2:#818cf8;--green:#22c55e;--green2:#16a34a;" +
            "--red:#ef4444;--orange:#f59e0b;--blue:#3b82f6;--cyan:#06b6d4;--purple:#a855f7;}" +
            "*{margin:0;padding:0;box-sizing:border-box}" +
            "body{background:var(--bg);color:var(--text);font-family:'Inter',system-ui,sans-serif;font-size:13px;min-height:100vh}" +

            // 头部
            ".header{background:linear-gradient(135deg,#1a1040 0%,#0f172a 50%,#0c1829 100%);" +
            "padding:16px 20px;position:sticky;top:0;z-index:100;border-bottom:1px solid var(--border);" +
            "backdrop-filter:blur(20px);display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:10px}" +
            ".header h1{font-size:18px;font-weight:700;background:linear-gradient(135deg,#818cf8,#c084fc,#f472b6);-webkit-background-clip:text;-webkit-text-fill-color:transparent}" +
            ".header-r{display:flex;align-items:center;gap:8px;flex-wrap:wrap}" +

            // 按钮
            ".btn{border:none;padding:7px 14px;border-radius:8px;font-size:12px;font-weight:600;cursor:pointer;font-family:inherit;" +
            "transition:all .2s;display:inline-flex;align-items:center;gap:5px;text-decoration:none;color:#fff}" +
            ".btn:hover{transform:translateY(-1px);box-shadow:0 4px 15px rgba(0,0,0,.4)}" +
            ".btn:active{transform:translateY(0)}" +
            ".btn-accent{background:linear-gradient(135deg,#6366f1,#8b5cf6)}" +
            ".btn-accent:hover{background:linear-gradient(135deg,#818cf8,#a78bfa)}" +
            ".btn-green{background:linear-gradient(135deg,#16a34a,#22c55e)}" +
            ".btn-green:hover{background:linear-gradient(135deg,#22c55e,#4ade80)}" +
            ".btn-red{background:linear-gradient(135deg,#dc2626,#ef4444)}" +
            ".btn-red:hover{background:linear-gradient(135deg,#ef4444,#f87171)}" +
            ".btn-blue{background:linear-gradient(135deg,#2563eb,#3b82f6)}" +
            ".btn-ghost{background:var(--card2);border:1px solid var(--border2);color:var(--text2)}" +
            ".btn-ghost:hover{background:var(--border);color:var(--text)}" +
            "#msg{font-size:12px;color:var(--green);margin-left:4px}" +

            // 状态栏
            ".stats{display:flex;gap:0;background:var(--card);border-bottom:1px solid var(--border)}" +
            ".stat-item{flex:1;padding:12px 16px;text-align:center;border-right:1px solid var(--border)}" +
            ".stat-item:last-child{border-right:none}" +
            ".stat-val{font-size:22px;font-weight:700;font-family:'JetBrains Mono',monospace}" +
            ".stat-label{font-size:11px;color:var(--text2);margin-top:2px;text-transform:uppercase;letter-spacing:.5px}" +
            ".v-green{color:var(--green)}.v-red{color:var(--red)}.v-blue{color:var(--blue)}.v-orange{color:var(--orange)}.v-purple{color:var(--purple)}" +

            // 卡片
            ".card{margin:12px 16px;padding:16px;background:var(--card);border:1px solid var(--border);border-radius:12px;" +
            "backdrop-filter:blur(10px)}" +
            ".card-title{font-size:14px;font-weight:600;margin-bottom:12px;display:flex;align-items:center;gap:8px}" +
            ".card-title .icon{width:20px;height:20px;border-radius:6px;display:flex;align-items:center;justify-content:center;font-size:12px}" +

            // 输入框
            ".inp{width:100%;padding:9px 12px;background:var(--bg);color:var(--text);border:1px solid var(--border);border-radius:8px;" +
            "font-family:'JetBrains Mono',monospace;font-size:12px;outline:none;transition:border-color .2s}" +
            ".inp:focus{border-color:var(--accent)}" +
            ".inp-row{display:flex;gap:8px;margin-bottom:8px}" +
            "textarea.inp{height:60px;resize:vertical}" +
            ".cred-info{font-size:11px;color:var(--text2);margin-top:10px;padding:10px;background:var(--bg);border-radius:8px;border:1px solid var(--border);word-break:break-all}" +
            ".cred-info b{color:var(--accent2)}" +
            ".cred-row{display:flex;align-items:center;gap:6px;margin-top:4px}" +
            ".cred-row:first-child{margin-top:0}" +
            ".cred-val{flex:1;word-break:break-all}" +
            ".btn-copy{flex-shrink:0;padding:3px 10px;font-size:10px;border-radius:6px;cursor:pointer;border:1px solid var(--border2);background:var(--card2);color:var(--text2);font-family:inherit;transition:all .2s}" +
            ".btn-copy:hover{background:var(--accent);color:#fff;border-color:var(--accent)}" +
            ".btn-copy.copied{background:var(--green);color:#fff;border-color:var(--green)}" +

            // 开关行
            ".toggles{display:flex;gap:8px;margin:12px 16px;flex-wrap:wrap}" +
            ".toggle-card{flex:1;min-width:140px;padding:12px 16px;background:var(--card);border:1px solid var(--border);border-radius:10px;cursor:pointer;transition:all .2s;text-align:center}" +
            ".toggle-card:hover{border-color:var(--accent);transform:translateY(-2px)}" +
            ".toggle-card .t-icon{font-size:20px;margin-bottom:4px}" +
            ".toggle-card .t-label{font-size:11px;color:var(--text2)}" +
            ".toggle-card .t-status{font-size:13px;font-weight:600;margin-top:2px}" +
            ".t-on{color:var(--green)}.t-off{color:var(--red)}" +

            // 日志区
            ".logs-wrap{margin:0 16px 16px;border-radius:12px;overflow:hidden;border:1px solid var(--border);background:var(--card)}" +
            ".logs-header{padding:10px 16px;background:var(--card2);border-bottom:1px solid var(--border);display:flex;justify-content:space-between;align-items:center}" +
            ".logs-header span{font-size:12px;color:var(--text2)}" +
            ".logs-body{max-height:55vh;overflow-y:auto;padding:4px 0;scroll-behavior:smooth}" +
            ".logs-body::-webkit-scrollbar{width:6px}.logs-body::-webkit-scrollbar-track{background:var(--bg)}" +
            ".logs-body::-webkit-scrollbar-thumb{background:var(--border2);border-radius:3px}" +

            // 日志行
            ".log-row{padding:4px 14px;font-family:'JetBrains Mono',monospace;font-size:12px;border-bottom:1px solid rgba(30,42,66,.4);" +
            "display:flex;align-items:flex-start;gap:8px;transition:background .15s}" +
            ".log-row:hover{background:rgba(99,102,241,.06)}" +
            ".log-dot{width:6px;height:6px;border-radius:50%;margin-top:5px;flex-shrink:0}" +
            ".log-n .log-dot{background:#475569}.log-r .log-dot{background:var(--green);box-shadow:0 0 6px var(--green)}" +
            ".log-e .log-dot{background:var(--red);box-shadow:0 0 6px var(--red)}" +
            ".log-i .log-dot{background:var(--blue);box-shadow:0 0 6px var(--blue)}" +
            ".log-b .log-dot{background:var(--cyan);box-shadow:0 0 6px var(--cyan)}" +
            ".log-r{color:#4ade80;font-weight:500}.log-e{color:#fca5a5}.log-i{color:#93c5fd}.log-b{color:#67e8f9}" +
            "@keyframes fadeIn{from{opacity:0;transform:translateX(-8px)}to{opacity:1;transform:translateX(0)}}" +

            "</style></head><body>" +

            // === 头部 ===
            "<div class='header'>" +
            "<h1>🎮 QukanHook 控制台</h1>" +
            "<div class='header-r'>" +
            "<button class='btn btn-accent' onclick='doTrigger()'>▶ 触发刷币</button>" +
            "<a href='/login' class='btn btn-blue'>🔐 扫码登录</a>" +
            "<button class='btn btn-ghost' onclick='location.reload()'>🔄</button>" +
            "<a href='/clear' class='btn btn-ghost'>🗑</a>" +
            "<a href='/json' class='btn btn-ghost'>{}</a>" +
            "<span id='msg'></span>" +
            "</div></div>" +

            // === 状态栏 ===
            "<div class='stats'>" +
            "<div class='stat-item'><div class='stat-val v-blue'>" + logs.size() + "</div><div class='stat-label'>日志条数</div></div>" +
            "<div class='stat-item'><div class='stat-val v-green'>" + rewardCount + "</div><div class='stat-label'>奖励次数</div></div>" +
            "<div class='stat-item'><div class='stat-val v-red'>" + errCount + "</div><div class='stat-label'>错误</div></div>" +
            "<div class='stat-item'><div class='stat-val v-orange'>" + blockCount + "</div><div class='stat-label'>广告拦截</div></div>" +
            "<div class='stat-item'><div class='stat-val v-purple'>" + AdRewardBot.botSuccessCount + "</div><div class='stat-label'>Bot成功</div></div>" +
            "<div class='stat-item'><div class='stat-val v-green'>" + AdRewardBot.botTotalCoins + "</div><div class='stat-label'>Bot金币</div></div>" +
            "</div>" +

            // === 开关面板 ===
            "<div class='toggles'>" +
            "<div class='toggle-card' id='tc_skip' onclick='toggleSkip()'>" +
            "<div class='t-icon'>⏭</div><div class='t-label'>跳过广告视频</div>" +
            "<div class='t-status " + (skipOn ? "t-on" : "t-off") + "' id='ts_skip'>" + (skipOn ? "✓ 已开启" : "✗ 已关闭") + "</div></div>" +
            "<div class='toggle-card' id='tc_block' onclick='toggleBlock()'>" +
            "<div class='t-icon'>🚫</div><div class='t-label'>拦截插屏广告</div>" +
            "<div class='t-status " + (blockOn ? "t-on" : "t-off") + "' id='ts_block'>" + (blockOn ? "✓ 已拦截" : "✗ 已放行") + "</div></div>" +
            "<div class='toggle-card' id='tc_bot' onclick='toggleBot()' style='border-color:" + (AdRewardBot.botRunning ? "var(--green)" : "var(--border)") + "'>" +
            "<div class='t-icon'>🤖</div><div class='t-label'>广告奖励 Bot</div>" +
            "<div class='t-status " + (AdRewardBot.botRunning ? "t-on" : "t-off") + "' id='ts_bot'>" + (AdRewardBot.botRunning ? "✓ 运行中" : "■ 已停止") + "</div></div>" +
            "</div>" +

            // === 账号卡片 ===
            "<div class='card'>" +
            "<div class='card-title'><span class='icon' style='background:#1e3a5f'>🔑</span>账号凭证替换</div>" +
            "<div class='inp-row'>" +
            "<input class='inp' id='inp_oaid' placeholder='OAID' style='flex:1'>" +
            "<input class='inp' id='inp_token' placeholder='Token' style='flex:2'>" +
            "</div>" +
            "<textarea class='inp' id='inp_user_json' placeholder='完整 USER_LOGIN_ENTITY JSON'></textarea>" +
            "<div style='margin-top:10px'><button class='btn btn-green' onclick='setCreds()'>💾 注入并持久化</button></div>" +
            "<div class='cred-info'>" +
            "<div class='cred-row'><div class='cred-val'><b>OAID:</b> <span id='v_oaid'>" + escHtml(MainHook.customOaid != null ? MainHook.customOaid : "未设置") + "</span></div>" +
            "<button class='btn-copy' onclick=\"copyText('v_oaid',this)\">📋 复制</button></div>" +
            "<div class='cred-row'><div class='cred-val'><b>Token:</b> <span id='v_token'>" + escHtml(MainHook.customToken != null ? MainHook.customToken : "未设置") + "</span></div>" +
            "<button class='btn-copy' onclick=\"copyText('v_token',this)\">📋 复制</button></div>" +
            "</div></div>" +

            buildWithdrawSection() +

            // === 日志区（双栏） ===
            "<div style='display:flex;gap:0;flex-wrap:wrap'>" +

            // Bot 日志（上方）
            "<div class='logs-wrap' style='flex:1;min-width:300px'>" +
            "<div class='logs-header' style='background:linear-gradient(135deg,rgba(168,85,247,.15),rgba(99,102,241,.08))'><span>🤖 Bot 日志</span>" +
            "<span style='color:var(--purple)'>● " + AdRewardBot.botSuccessCount + " 成功 / " + AdRewardBot.botFailCount + " 失败</span></div>" +
            "<div class='logs-body' id='botlogbox'>" + botRows + "</div></div>" +

            // Hook 日志（下方）
            "<div class='logs-wrap' style='flex:1;min-width:300px'>" +
            "<div class='logs-header'><span>📋 Hook 日志</span><span id='autoTag' style='color:var(--green)'>● 自动刷新</span></div>" +
            "<div class='logs-body' id='logbox'>" + rows + "</div></div>" +

            "</div>" +

            // === JS ===
            "<script>" +
            "var _autoScroll=true;var _lastLogCnt=0;var _lastBotCnt=0;" +
            "var lb=document.getElementById('logbox');lb.scrollTop=lb.scrollHeight;_lastLogCnt=lb.children.length;" +
            "var bb=document.getElementById('botlogbox');if(bb){bb.scrollTop=bb.scrollHeight;_lastBotCnt=bb.children.length;}" +
            // 检测用户是否手动滚动了日志区（停止自动滚动）
            "lb.addEventListener('scroll',function(){_autoScroll=lb.scrollTop+lb.clientHeight>=lb.scrollHeight-30;});" +
            // AJAX 轮询替代整页刷新（仅在日志数量变化时更新DOM）
            "function pollUpdate(){" +
            "fetch('/api/poll').then(r=>r.json()).then(d=>{" +
            // 更新状态栏
            "document.querySelectorAll('.stat-val')[0].textContent=d.logCount;" +
            "document.querySelectorAll('.stat-val')[1].textContent=d.rewardCount;" +
            "document.querySelectorAll('.stat-val')[2].textContent=d.errCount;" +
            "document.querySelectorAll('.stat-val')[3].textContent=d.blockCount;" +
            "document.querySelectorAll('.stat-val')[4].textContent=d.botSuccess;" +
            "document.querySelectorAll('.stat-val')[5].textContent=d.botCoins;" +
            // 更新 Hook 日志（仅当日志数量变化时）
            "if(d.logCount!==_lastLogCnt){_lastLogCnt=d.logCount;lb.innerHTML=d.hookHtml;if(_autoScroll)lb.scrollTop=lb.scrollHeight;}" +
            // 更新 Bot 日志（仅当日志数量变化时）
            "if(d.botLogCount!==_lastBotCnt&&bb){_lastBotCnt=d.botLogCount;bb.innerHTML=d.botHtml;bb.scrollTop=bb.scrollHeight;}" +
            // 更新 Bot 状态栏计数
            "var bh=document.querySelector('.logs-header span[style]');" +
            "if(bh)bh.textContent='● '+d.botSuccess+' 成功 / '+d.botFail+' 失败';" +
            // 更新开关状态
            "var ts=document.getElementById('ts_bot');var tc=document.getElementById('tc_bot');" +
            "if(ts){if(d.botRunning){ts.className='t-status t-on';ts.textContent='✓ 运行中';tc.style.borderColor='var(--green)';}" +
            "else{ts.className='t-status t-off';ts.textContent='■ 已停止';tc.style.borderColor='var(--border)';}}" +
            "}).catch(()=>{});" +
            "setTimeout(pollUpdate,3000);}" +
            "setTimeout(pollUpdate,3000);" +
            "function doTrigger(){var m=document.getElementById('msg');m.style.color='var(--orange)';m.textContent='触发中...';" +
            "fetch('/trigger').then(r=>r.json()).then(d=>{m.style.color='var(--green)';m.textContent=d.result;setTimeout(pollUpdate,1500);}).catch(e=>{m.style.color='var(--red)';m.textContent='失败';});}" +
            "function setCreds(){var o=document.getElementById('inp_oaid').value,t=document.getElementById('inp_token').value,u=document.getElementById('inp_user_json').value;" +
            "fetch('/set_creds?oaid='+encodeURIComponent(o)+'&token='+encodeURIComponent(t)+'&user_json='+encodeURIComponent(u)).then(r=>r.json()).then(()=>{alert('凭证已注入！');location.reload();});}" +
            "function toggleSkip(){fetch('/toggle_skip').then(r=>r.json()).then(d=>{var s=document.getElementById('ts_skip');" +
            "if(d.skipAdEnabled){s.className='t-status t-on';s.textContent='✓ 已开启';}else{s.className='t-status t-off';s.textContent='✗ 已关闭';}});}" +
            "function toggleBlock(){fetch('/toggle_blockads').then(r=>r.json()).then(d=>{var s=document.getElementById('ts_block');" +
            "if(d.blockNonRewardAds){s.className='t-status t-on';s.textContent='✓ 已拦截';}else{s.className='t-status t-off';s.textContent='✗ 已放行';}});}" +
            "function toggleBot(){var s=document.getElementById('ts_bot'),c=document.getElementById('tc_bot');" +
            "var isOn=s.textContent.indexOf('运行')>=0;" +
            "fetch(isOn?'/bot_stop':'/bot_start').then(r=>r.json()).then(d=>{" +
            "if(d.botRunning){s.className='t-status t-on';s.textContent='✓ 运行中';c.style.borderColor='var(--green)';}" +
            "else{s.className='t-status t-off';s.textContent='■ 已停止';c.style.borderColor='var(--border)';}" +
            "setTimeout(()=>location.reload(),2000);});}" +
            "function copyText(id,btn){var t=document.getElementById(id).textContent;if(!t||t==='未设置'){return;}" +
            "navigator.clipboard.writeText(t).then(()=>{btn.classList.add('copied');btn.textContent='✓ 已复制';" +
            "setTimeout(()=>{btn.classList.remove('copied');btn.textContent='📋 复制';},1500);" +
            "}).catch(()=>{var a=document.createElement('textarea');a.value=t;document.body.appendChild(a);a.select();document.execCommand('copy');document.body.removeChild(a);" +
            "btn.classList.add('copied');btn.textContent='✓ 已复制';setTimeout(()=>{btn.classList.remove('copied');btn.textContent='📋 复制';},1500);});}" +
            "</script></body></html>";

        writeHttp(out, "200 OK", "text/html; charset=utf-8", html.getBytes("UTF-8"));
    }

    // ---------------------------------------------------------------
    // JSON 页
    // ---------------------------------------------------------------
    private static void sendJson(OutputStream out) throws IOException {
        StringBuilder sb = new StringBuilder("{\"count\":");
        sb.append(logs.size()).append(",\"logs\":[");
        synchronized (logs) {
            for (int i = 0; i < logs.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(escJson(logs.get(i))).append("\"");
            }
        }
        sb.append("]}");
        writeHttp(out, "200 OK", "application/json; charset=utf-8",
                sb.toString().getBytes("UTF-8"));
    }

    // ---------------------------------------------------------------
    // AJAX 轮询接口：返回日志 HTML + 状态数据 JSON
    // ---------------------------------------------------------------
    private static void sendPollJson(OutputStream out) throws IOException {
        int rewardCount = 0, errCount = 0, blockCount = 0;
        StringBuilder hookHtml = new StringBuilder();
        synchronized (logs) {
            for (int idx = 0; idx < logs.size(); idx++) {
                String l = logs.get(idx);
                if (l.contains("★")) rewardCount++;
                if (l.contains("失败") || l.contains("STOP") || l.contains("异常")) errCount++;
                if (l.contains("[AdBlock]") && l.contains("已拦截")) blockCount++;
                String cls = "log-n";
                if (l.contains("★") || l.contains("Reward")) cls = "log-r";
                else if (l.contains("失败") || l.contains("STOP") || l.contains("异常")) cls = "log-e";
                else if (l.contains("[AdBlock]")) cls = "log-b";
                else if (l.contains("✓") || l.contains("激活") || l.contains("成功")) cls = "log-i";
                hookHtml.append("<div class='log-row ").append(cls).append("'>")
                        .append("<span class='log-dot'></span>").append(escHtml(l)).append("</div>");
            }
        }
        StringBuilder botHtml = new StringBuilder();
        synchronized (botLogs) {
            for (int idx = 0; idx < botLogs.size(); idx++) {
                String l = botLogs.get(idx);
                String cls = "log-n";
                if (l.contains("★") || l.contains("成功")) cls = "log-r";
                else if (l.contains("✗") || l.contains("失败") || l.contains("异常")) cls = "log-e";
                else if (l.contains("📊") || l.contains("参数")) cls = "log-i";
                else if (l.contains("▶") || l.contains("■")) cls = "log-b";
                botHtml.append("<div class='log-row ").append(cls).append("'>")
                        .append("<span class='log-dot'></span>").append(escHtml(l)).append("</div>");
            }
        }
        String json = "{" +
                "\"logCount\":" + logs.size() + "," +
                "\"rewardCount\":" + rewardCount + "," +
                "\"errCount\":" + errCount + "," +
                "\"blockCount\":" + blockCount + "," +
                "\"botSuccess\":" + AdRewardBot.botSuccessCount + "," +
                "\"botFail\":" + AdRewardBot.botFailCount + "," +
                "\"botCoins\":" + AdRewardBot.botTotalCoins + "," +
                "\"botPending\":" + AdRewardBot.botPendingCoins + "," +
                "\"botRunning\":" + AdRewardBot.botRunning + "," +
                "\"botLogCount\":" + botLogs.size() + "," +
                "\"hookHtml\":\"" + escJson(hookHtml.toString()) + "\"," +
                "\"botHtml\":\"" + escJson(botHtml.toString()) + "\"" +
                "}";
        writeHttp(out, "200 OK", "application/json; charset=utf-8", json.getBytes("UTF-8"));
    }


    // ---------------------------------------------------------------
    private static void sendRedirect(OutputStream out, String location) throws IOException {
        String resp = "HTTP/1.1 302 Found\r\nLocation: " + location + "\r\nContent-Length: 0\r\n\r\n";
        out.write(resp.getBytes("UTF-8"));
        out.flush();
    }

    // ---------------------------------------------------------------
    // 通用 HTTP 响应
    // ---------------------------------------------------------------
    private static void writeHttp(OutputStream out, String status,
            String contentType, byte[] body) throws IOException {
        String header = "HTTP/1.1 " + status + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    // ---------------------------------------------------------------
    // 提现链接区域
    // ---------------------------------------------------------------
    private static String buildWithdrawSection() {
        String pkgInfo = MainHook.lastPackageInfo;
        if (pkgInfo == null || pkgInfo.isEmpty()) {
            return "";
        }
        String encodedPkg;
        try {
            encodedPkg = java.net.URLEncoder.encode(pkgInfo, "UTF-8");
        } catch (Exception e) {
            encodedPkg = pkgInfo;
        }
        String txUrl = "http://123.207.224.230:4000/tx?mchId=1739585425&appId=wx3332152cb786c5b0&package=" + encodedPkg;
        return "<div class='card' style='border-color:rgba(34,197,94,.3);background:linear-gradient(135deg,rgba(34,197,94,.06),var(--card))'>" +
                "<div class='card-title'><span class='icon' style='background:rgba(34,197,94,.2)'>💰</span><span style='color:#4ade80'>提现可用</span></div>" +
                "<a href='" + escHtml(txUrl) + "' target='_blank' style='color:var(--accent2);font-size:12px;word-break:break-all;'>" + escHtml(txUrl) + "</a>" +
                "<div style='margin-top:10px'><button class='btn btn-green' style='width:auto;padding:7px 20px' onclick=\"window.open('" + escHtml(txUrl).replace("'", "\\'") + "','_blank')\">打开提现页面</button></div>" +
                "</div>";
    }

    // ---------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------
    private static String escHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    static String getLocalIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "0.0.0.0";
    }
}
