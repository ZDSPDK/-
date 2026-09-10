package com.torrent.app;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class LocalServer extends NanoHTTPD {

    private static final String TAG = "LocalServer";
    private final TorrentManager manager;
    private final Context context;

    public LocalServer(int port, TorrentManager manager, Context context) {
        super(port);
        this.manager = manager;
        this.context = context;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        try {
            if (method == Method.GET) {
                if (uri.equals("/") || uri.equals("/index.html")) {
                    String html = loadAsset("web/index.html");
                    return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html);
                }
                if (uri.equals("/api/list")) {
                    JSONObject json = new JSONObject(manager.list());
                    return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString());
                }
                if (uri.equals("/api/config")) {
                    JSONObject json = new JSONObject();
                    json.put("download_dir", manager.getDownloadDir().getAbsolutePath());
                    return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString());
                }
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404");
            }

            if (method == Method.POST) {
                if (uri.equals("/api/add")) {
                    return handleAdd(session);
                }
                if (uri.equals("/api/upload")) {
                    return handleUpload(session);
                }
                if (uri.startsWith("/api/remove")) {
                    Map<String, String> parms = session.getParms();
                    String id = parms.get("id");
                    boolean ok = manager.remove(id);
                    JSONObject resp = new JSONObject();
                    resp.put("ok", ok);
                    return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString());
                }
                if (uri.equals("/api/pick-dir")) {
                    Map<String, String> files = new HashMap<>();
                    session.parseBody(files);
                    return handlePickDir();
                }
            }

            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404");
        } catch (Exception e) {
            Log.e(TAG, "请求处理错误", e);
            try {
                JSONObject resp = new JSONObject();
                resp.put("ok", false);
                resp.put("error", e.getMessage());
                return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString());
            } catch (Exception ex) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "500");
            }
        }
    }

    private Response handleAdd(IHTTPSession session) throws IOException, ResponseException, JSONException {
        JSONObject req = new JSONObject(readBody(session));
        // 接受 "magnet" 或 "url" 字段
        String url = req.optString("magnet", "");
        if (url.isEmpty()) url = req.optString("url", "");
        if (url.isEmpty()) {
            JSONObject resp = new JSONObject();
            resp.put("ok", false); resp.put("error", "请输入磁力链接或下载地址");
            return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString());
        }

        // 解析可选的自定义 headers / cookies
        Map<String, String> headers = new HashMap<>();
        String rawHeaders = req.optString("headers", "").trim();
        parseHeaders(rawHeaders, headers);
        String cookies = req.optString("cookies", "").trim();
        if (!cookies.isEmpty()) headers.put("Cookie", cookies);
        String ua = req.optString("userAgent", "").trim();
        if (!ua.isEmpty()) headers.put("User-Agent", ua);

        String id = manager.addUrl(url, headers);
        JSONObject resp = new JSONObject();
        resp.put("ok", true); resp.put("id", id);
        return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString());
    }

    private Response handleUpload(IHTTPSession session) throws IOException, ResponseException, JSONException {
        byte[] fileData = extractFile(session);
        if (fileData == null) {
            JSONObject resp = new JSONObject();
            resp.put("ok", false); resp.put("error", "未找到文件");
            return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString());
        }
        String id = manager.addTorrentFile(fileData);
        JSONObject resp = new JSONObject();
        resp.put("ok", true); resp.put("id", id);
        return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString());
    }

    private Response handlePickDir() throws JSONException {
        JSONObject resp = new JSONObject();
        if (!(context instanceof MainActivity)) {
            resp.put("ok", false);
            resp.put("error", "无法打开目录选择器");
            return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString());
        }
        String path = ((MainActivity) context).pickDownloadDir();
        if (path == null) {
            resp.put("ok", false);
            resp.put("cancelled", true);
            return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString());
        }
        resp.put("ok", true);
        resp.put("download_dir", path);
        return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString());
    }

    /** 解析 "Key: Value\nKey2: Value2" 格式的 headers 文本 */
    private void parseHeaders(String raw, Map<String, String> out) {
        if (raw.isEmpty()) return;
        for (String line : raw.split("\n")) {
            line = line.trim();
            int idx = line.indexOf(':');
            if (idx > 0) {
                String key = line.substring(0, idx).trim();
                String val = line.substring(idx + 1).trim();
                if (!key.isEmpty()) out.put(key, val);
            }
        }
    }

    // ---------- 工具方法 ----------

    private String loadAsset(String path) throws IOException {
        InputStream is = context.getAssets().open(path);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
        is.close();
        return bos.toString("UTF-8");
    }

    private String readBody(IHTTPSession session) throws IOException, ResponseException {
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        String body = files.get("postData");
        if (body == null) {
            body = session.getQueryParameterString();
            if (body == null) body = "";
        }
        return body;
    }

    private byte[] extractFile(IHTTPSession session) throws IOException, ResponseException {
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        for (Map.Entry<String, String> entry : files.entrySet()) {
            if (entry.getKey().equals("file")) {
                java.io.File tmpFile = new java.io.File(entry.getValue());
                if (tmpFile.exists()) {
                    InputStream is = new FileInputStream(tmpFile);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
                    is.close();
                    tmpFile.delete();
                    return bos.toByteArray();
                }
            }
        }
        return null;
    }
}
