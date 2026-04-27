package com.qukan.hook;

import android.util.Base64;
import org.json.JSONObject;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微信扫码登录处理器
 * 复刻 扫码登录(1).py 的逻辑：
 *   1. fetch_qr_info() → 获取微信 uuid + 二维码图片
 *   2. check_qr_status() → 长轮询扫码状态
 *   3. do_login() → AES-CBC 加密 payload POST 到 hd.dffhq.top
 */
public class QrLoginHandler {

    private static final String WX_APPID = "wx3332152cb786c5b0";
    private static final byte[] AES_KEY = hexToBytes("44ce8abb00ab82421c6f59594af37fe1");
    private static final byte[] AES_IV  = hexToBytes("0dbeddeefe601ac142bf01ac790d7833");
    private static final String LOGIN_URL = "https://hd.dffhq.top/g/wxlogins.ashx";

    // 会话存储
    static final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    static class Session {
        String uuid;
        String oaid;
        long createTime;
        volatile String status = "waiting"; // waiting, processing, login_success, login_failed
        volatile String loginResult = "";
        volatile String token = "";
        volatile String userJson = "";
    }

    // ==================== 核心方法 ====================

    /** 获取微信二维码 */
    static String handleGetQrcode(String oaid) {
        try {
            cleanExpired();
            String[] qr = fetchQrInfo();
            String uuid = qr[0], imgUrl = qr[1];
            String flowId = System.currentTimeMillis() + "_" + Math.random();
            Session s = new Session();
            s.uuid = uuid;
            s.oaid = oaid;
            s.createTime = System.currentTimeMillis();
            sessions.put(flowId, s);
            return "{\"status\":\"success\",\"uuid\":\"" + esc(uuid) + "\",\"img_url\":\"" + esc(imgUrl) + "\",\"flow_id\":\"" + esc(flowId) + "\"}";
        } catch (Exception e) {
            return "{\"status\":\"error\",\"msg\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    /** 轮询扫码状态 */
    static String handlePoll(String flowId) {
        Session s = sessions.get(flowId);
        if (s == null) return "{\"status\":\"error\",\"msg\":\"会话不存在\",\"refresh\":true}";

        // 已经是终态
        if (s.status.equals("login_success") || s.status.equals("login_failed")) {
            return "{\"status\":\"" + s.status + "\",\"msg\":\"" + esc(s.loginResult) + "\",\"token\":\"" + esc(s.token) + "\",\"user_json\":\"" + esc(s.userJson) + "\"}";
        }
        if (s.status.equals("processing")) {
            return "{\"status\":\"processing\",\"msg\":\"登录处理中...\"}";
        }

        try {
            String[] result = checkQrStatus(s.uuid);
            String st = result[0], data = result[1];

            if ("waiting".equals(st)) {
                return "{\"status\":\"waiting\",\"msg\":\"等待扫码...\"}";
            } else if ("scan_success".equals(st)) {
                return "{\"status\":\"waiting\",\"msg\":\"扫码成功，请在手机上确认\"}";
            } else if ("expired".equals(st)) {
                return "{\"status\":\"error\",\"msg\":\"二维码已过期\",\"refresh\":true}";
            } else if ("success".equals(st)) {
                s.status = "processing";
                // 异步执行登录
                final String code = data;
                new Thread(() -> {
                    try {
                        String resp = doLogin(code, s.oaid);
                        JSONObject json = new JSONObject(resp);
                        if (json.optInt("Code") == 200) {
                            JSONObject d = json.getJSONObject("Data");
                            s.token = d.optString("access_token", "");
                            s.userJson = d.toString();
                            s.loginResult = "登录成功";
                            s.status = "login_success";
                            // 自动设置凭证
                            MainHook.saveCreds(s.oaid, s.token, s.userJson);
                            LogServer.log("[Login] ✅ 扫码登录成功! token=" + s.token.substring(0, Math.min(20, s.token.length())) + "...");
                        } else {
                            s.loginResult = json.optString("Message", "登录失败");
                            s.status = "login_failed";
                            LogServer.log("[Login] ✗ 登录失败: " + s.loginResult);
                        }
                    } catch (Exception e) {
                        s.loginResult = "登录异常: " + e.getMessage();
                        s.status = "login_failed";
                        LogServer.log("[Login] ✗ 异常: " + e.getMessage());
                    }
                }).start();
                return "{\"status\":\"processing\",\"msg\":\"授权成功，正在登录...\"}";
            } else {
                return "{\"status\":\"error\",\"msg\":\"" + esc(data != null ? data : "未知错误") + "\",\"refresh\":true}";
            }
        } catch (Exception e) {
            return "{\"status\":\"error\",\"msg\":\"轮询异常: " + esc(e.getMessage()) + "\"}";
        }
    }

    /** 登录页 HTML */
    static String getLoginHtml() {
        return "<!DOCTYPE html><html lang='zh'><head><meta charset='utf-8'>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>扫码登录 - QukanHook</title>" +
            "<style>" +
            "@import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap');" +
            ":root{--bg:#0b0f19;--card:#141a2a;--border:#1e2a42;--border2:#2a3a5c;--text:#e2e8f0;--text2:#8892a8;" +
            "--accent:#6366f1;--accent2:#818cf8;--green:#22c55e;--red:#ef4444;--blue:#3b82f6}" +
            "*{margin:0;padding:0;box-sizing:border-box}" +
            "body{background:var(--bg);color:var(--text);font-family:'Inter',system-ui,sans-serif;" +
            "display:flex;justify-content:center;align-items:center;min-height:100vh;padding:20px;" +
            "background-image:radial-gradient(ellipse at 50% 0%,rgba(99,102,241,.12) 0%,transparent 60%)}" +

            ".card{background:var(--card);width:100%;max-width:420px;border-radius:16px;padding:32px;" +
            "border:1px solid var(--border);box-shadow:0 20px 60px rgba(0,0,0,.4),0 0 0 1px rgba(99,102,241,.1);" +
            "animation:cardIn .5s ease}" +
            "@keyframes cardIn{from{opacity:0;transform:translateY(20px)}to{opacity:1;transform:translateY(0)}}" +

            "h1{text-align:center;font-size:22px;font-weight:700;margin-bottom:4px;" +
            "background:linear-gradient(135deg,#818cf8,#c084fc);-webkit-background-clip:text;-webkit-text-fill-color:transparent}" +
            ".sub{text-align:center;color:var(--text2);font-size:13px;margin-bottom:24px}" +

            "label{display:block;margin-bottom:6px;font-size:12px;color:var(--text2);font-weight:500;text-transform:uppercase;letter-spacing:.5px}" +
            "input{width:100%;padding:11px 14px;background:var(--bg);color:var(--text);border:1px solid var(--border);" +
            "border-radius:10px;font-size:14px;margin-bottom:16px;outline:none;transition:all .2s;font-family:inherit}" +
            "input:focus{border-color:var(--accent);box-shadow:0 0 0 3px rgba(99,102,241,.15)}" +

            "button{width:100%;padding:12px;background:linear-gradient(135deg,#6366f1,#8b5cf6);color:#fff;border:none;" +
            "border-radius:10px;font-size:14px;font-weight:600;cursor:pointer;transition:all .2s;font-family:inherit}" +
            "button:hover{transform:translateY(-1px);box-shadow:0 6px 20px rgba(99,102,241,.35)}" +
            "button:active{transform:translateY(0)}" +
            "button:disabled{background:#1e2a42;color:#475569;cursor:not-allowed;transform:none;box-shadow:none}" +

            ".qr{display:none;text-align:center;margin-top:24px;padding:24px;background:var(--bg);border-radius:12px;" +
            "border:1px solid var(--border);position:relative;overflow:hidden}" +
            ".qr::before{content:'';position:absolute;inset:-2px;border-radius:14px;background:conic-gradient(from 0deg,#6366f1,#a855f7,#ec4899,#6366f1);" +
            "z-index:-1;animation:spin 3s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}" +
            ".qr img{width:200px;height:200px;border-radius:10px;margin-bottom:14px;background:#fff;padding:8px}" +

            ".badge{display:inline-flex;align-items:center;gap:6px;padding:6px 16px;border-radius:99px;font-size:12px;font-weight:500;" +
            "background:rgba(99,102,241,.15);color:var(--accent2);border:1px solid rgba(99,102,241,.2)}" +
            ".badge::before{content:'';width:6px;height:6px;border-radius:50%;background:var(--accent2);" +
            "animation:pulse 1.5s infinite}@keyframes pulse{0%,100%{opacity:1}50%{opacity:.3}}" +

            ".result{display:none;margin-top:24px;padding:18px;border-radius:12px;font-size:13px;animation:fadeIn .4s ease}" +
            "@keyframes fadeIn{from{opacity:0;transform:scale(.96)}to{opacity:1;transform:scale(1)}}" +
            ".ok{background:rgba(34,197,94,.08);border:1px solid rgba(34,197,94,.3);color:#4ade80}" +
            ".fail{background:rgba(239,68,68,.08);border:1px solid rgba(239,68,68,.3);color:#fca5a5}" +
            ".mono{font-family:'JetBrains Mono',monospace;font-size:11px;word-break:break-all;" +
            "background:rgba(0,0,0,.3);padding:8px;border-radius:8px;margin-top:10px;max-height:80px;overflow-y:auto}" +
            ".copy-btn{display:inline-block;margin-top:8px;padding:5px 14px;border-radius:6px;font-size:11px;font-weight:600;" +
            "cursor:pointer;border:1px solid var(--accent);background:rgba(99,102,241,.1);color:var(--accent2);transition:all .2s}" +
            ".copy-btn:hover{background:var(--accent);color:#fff}" +
            ".copy-btn.done{background:var(--green);border-color:var(--green);color:#fff}" +

            "a.back{display:flex;align-items:center;justify-content:center;gap:6px;margin-top:20px;color:var(--text2);" +
            "font-size:13px;text-decoration:none;padding:10px;border-radius:8px;transition:all .2s}" +
            "a.back:hover{background:rgba(99,102,241,.08);color:var(--accent2)}" +
            "</style></head><body>" +

            "<div class='card'>" +
            "<h1>🔐 扫码登录</h1><p class='sub'>微信扫码 · 自动注入凭证</p>" +
            "<label>设备 OAID <span style='color:var(--red)'>*</span></label>" +
            "<input id='oaid' placeholder='输入目标设备 OAID'>" +
            "<button id='btn' onclick='getQr()'>获取二维码</button>" +

            "<div class='qr' id='qr'><img id='qrimg'><br><span class='badge' id='badge'>等待扫码</span></div>" +
            "<div class='result' id='res'><div id='restxt'></div><div class='mono' id='resmono'></div>" +
            "<button class='copy-btn' id='cpbtn' onclick='copyToken()' style='display:none'>📋 复制 Token</button></div>" +
            "<a class='back' href='/'>← 返回控制台</a>" +
            "</div>" +

            "<script>" +
            "var fid=null,polling=false;" +
            "function getQr(){" +
            "  var o=document.getElementById('oaid').value.trim();" +
            "  if(!o){alert('请输入OAID');return;}" +
            "  var b=document.getElementById('btn');b.disabled=true;b.textContent='请求中...';" +
            "  document.getElementById('res').style.display='none';" +
            "  fetch('/get_qrcode?oaid='+encodeURIComponent(o)).then(r=>r.json()).then(d=>{" +
            "    if(d.status==='success'){" +
            "      fid=d.flow_id;document.getElementById('qrimg').src=d.img_url;" +
            "      document.getElementById('qr').style.display='block';" +
            "      document.getElementById('badge').textContent='请使用微信扫码';" +
            "      b.textContent='等待扫码...';polling=true;poll();" +
            "    }else{alert(d.msg);b.disabled=false;b.textContent='获取二维码';}" +
            "  }).catch(e=>{alert('网络错误');b.disabled=false;b.textContent='获取二维码';});" +
            "}" +
            "function poll(){" +
            "  if(!polling||!fid)return;" +
            "  fetch('/poll?flow_id='+fid).then(r=>r.json()).then(d=>{" +
            "    var bg=document.getElementById('badge');" +
            "    if(d.status==='waiting'){bg.textContent=d.msg;}" +
            "    else if(d.status==='processing'){bg.textContent='登录中...';bg.style.cssText='background:rgba(34,197,94,.15);color:#4ade80;border-color:rgba(34,197,94,.2)';}" +
            "    else if(d.status==='login_success'){" +
            "      polling=false;document.getElementById('qr').style.display='none';" +
            "      var r=document.getElementById('res');r.style.display='block';r.className='result ok';" +
            "      document.getElementById('restxt').innerHTML='✅ 登录成功！凭证已自动注入';" +
            "      document.getElementById('resmono').textContent='Token: '+d.token;" +
            "      document.getElementById('cpbtn').style.display='inline-block';document.getElementById('cpbtn').setAttribute('data-token',d.token);" +
            "      document.getElementById('btn').textContent='重新获取';document.getElementById('btn').disabled=false;return;" +
            "    }else if(d.status==='login_failed'){" +
            "      polling=false;document.getElementById('qr').style.display='none';" +
            "      var r=document.getElementById('res');r.style.display='block';r.className='result fail';" +
            "      document.getElementById('restxt').textContent='❌ '+d.msg;" +
            "      document.getElementById('btn').textContent='重新获取';document.getElementById('btn').disabled=false;return;" +
            "    }else if(d.status==='error'){" +
            "      if(d.refresh){bg.textContent=d.msg;bg.style.cssText='background:rgba(239,68,68,.15);color:#fca5a5;border-color:rgba(239,68,68,.2)';" +
            "      document.getElementById('btn').textContent='重新获取';document.getElementById('btn').disabled=false;polling=false;return;}" +
            "    }" +
            "    if(polling)setTimeout(poll,1200);" +
            "  }).catch(()=>{if(polling)setTimeout(poll,2000);});" +
            "}" +
            "function copyToken(){var b=document.getElementById('cpbtn'),t=b.getAttribute('data-token');if(!t)return;" +
            "navigator.clipboard.writeText(t).then(()=>{b.classList.add('done');b.textContent='✓ 已复制';" +
            "setTimeout(()=>{b.classList.remove('done');b.textContent='📋 复制 Token';},2000);" +
            "}).catch(()=>{var a=document.createElement('textarea');a.value=t;document.body.appendChild(a);a.select();document.execCommand('copy');document.body.removeChild(a);" +
            "b.classList.add('done');b.textContent='✓ 已复制';setTimeout(()=>{b.classList.remove('done');b.textContent='📋 复制 Token';},2000);});}" +
            "</script></body></html>";
    }

    // ==================== 内部方法 ====================

    private static String[] fetchQrInfo() throws Exception {
        String url = "https://open.weixin.qq.com/connect/app/qrconnect?appid=" + WX_APPID +
                "&bundleid=(null)&scope=snsapi_userinfo&state=w";
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 8_0 like Mac OS X) " +
                "AppleWebKit/600.1.4 Mobile/12A365 MicroMessenger/5.4.1");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        String body = readString(conn.getInputStream());
        conn.disconnect();

        if (!body.contains("uuid: \"") || !body.contains("auth_qrcode\" src=\""))
            throw new Exception("微信响应异常，无法获取二维码");

        String uuid = body.split("uuid: \"")[1].split("\"")[0];
        String imgUrl = body.split("auth_qrcode\" src=\"")[1].split("\"")[0];
        return new String[]{uuid, imgUrl};
    }

    private static String[] checkQrStatus(String uuid) throws Exception {
        String url = "https://long.open.weixin.qq.com/connect/l/qrconnect?uuid=" + uuid +
                "&f=url&_=" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 8_0 like Mac OS X) " +
                "AppleWebKit/600.1.4 Mobile/12A365 MicroMessenger/5.4.1");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(20000);
        String body = readString(conn.getInputStream());
        conn.disconnect();

        if (body.contains("wx_errcode=404")) return new String[]{"scan_success", null};
        if (body.contains("wx_errcode=402")) return new String[]{"expired", null};
        if (body.contains("oauth")) {
            String redirect = body.split("wx_redirecturl=")[1].split(";")[0]
                    .replace("'", "").replace("\"", "");
            // Parse code from redirect URL
            String q = redirect.substring(redirect.indexOf("?") + 1);
            for (String p : q.split("&")) {
                if (p.startsWith("code=")) return new String[]{"success", p.substring(5)};
            }
            return new String[]{"error", "无法解析code"};
        }
        return new String[]{"waiting", null};
    }

    private static String doLogin(String code, String oaid) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("ad_id", 2);
        payload.put("cid", "");
        payload.put("code", code);
        payload.put("driver", "wechat");
        payload.put("gid", "top.dffhq.qwsh");
        payload.put("group_invite", "0");
        payload.put("oaid", oaid);
        payload.put("phone_brand", "OnePlus");
        payload.put("phone_model", "ONEPLUS A600");
        payload.put("pid", "");
        payload.put("shopWeb", "0");
        payload.put("sim_state", false);
        payload.put("sys_version", "10");

        String encrypted = aesEncrypt(payload.toString());

        HttpURLConnection conn = (HttpURLConnection) new URL(LOGIN_URL).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
        conn.setRequestProperty("User-Agent", "okhttp/4.9.0");
        byte[] bodyBytes = encrypted.getBytes("UTF-8");
        conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
        conn.getOutputStream().write(bodyBytes);

        InputStream is = conn.getResponseCode() < 400 ? conn.getInputStream() : conn.getErrorStream();
        String resp = readString(is);
        conn.disconnect();
        return resp;
    }

    private static String aesEncrypt(String plainText) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"), new IvParameterSpec(AES_IV));
        byte[] encrypted = cipher.doFinal(plainText.getBytes("UTF-8"));
        return Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private static String readString(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    private static void cleanExpired() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> now - e.getValue().createTime > 600_000);
    }

    private static byte[] hexToBytes(String hex) {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
