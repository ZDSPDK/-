package com.torrent.app;

import android.util.Log;

import org.libtorrent4j.AlertListener;
import org.libtorrent4j.AddTorrentParams;
import org.libtorrent4j.Sha1Hash;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.SaveResumeDataAlert;
import org.libtorrent4j.swig.add_torrent_params;
import org.libtorrent4j.swig.byte_vector;
import org.libtorrent4j.swig.error_code;
import org.libtorrent4j.swig.libtorrent;
import org.libtorrent4j.swig.sha1_hash;
import org.libtorrent4j.swig.torrent_flags_t;
import org.libtorrent4j.swig.torrent_handle;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONArray;
import org.json.JSONObject;

public class TorrentManager {

    private static final String TAG = "TorrentManager";

    private final SessionManager session;
    private volatile File downloadDir;
    private final File stateDir;
    private final File tasksFile;

    private final Map<String, String> types = new ConcurrentHashMap<>();
    private final Map<String, TorrentHandle> torrentHandles = new ConcurrentHashMap<>();
    private final Map<String, HttpDownload> httpDownloads = new ConcurrentHashMap<>();
    private final Map<String, File> savePaths = new ConcurrentHashMap<>();

    public TorrentManager(File downloadDir) {
        this(downloadDir, new File(downloadDir, ".bt_downloader"));
    }

    public TorrentManager(File downloadDir, File stateDir) {
        this.downloadDir = downloadDir;
        if (!downloadDir.exists()) downloadDir.mkdirs();
        this.stateDir = stateDir;
        if (!stateDir.exists()) stateDir.mkdirs();
        this.tasksFile = new File(stateDir, "tasks.json");

        session = new SessionManager();
        session.start();

        // fastresume 落盘监听
        session.addListener(new AlertListener() {
            @Override
            public int[] types() { return null; } // 所有告警

            @Override
            public void alert(Alert<?> a) {
                if (a instanceof SaveResumeDataAlert) {
                    try {
                        SaveResumeDataAlert sr = (SaveResumeDataAlert) a;
                        byte[] buf = AddTorrentParams.writeResumeDataBuf(sr.params());
                        String hex = sr.handle().infoHash().toHex();
                        File f = new File(stateDir, hex + ".fastresume");
                        try (FileOutputStream out = new FileOutputStream(f)) {
                            out.write(buf);
                        }
                        Log.d(TAG, "fastresume 已保存: " + hex + " (" + buf.length + " bytes)");
                    } catch (Exception e) {
                        Log.e(TAG, "保存 fastresume 失败", e);
                    }
                }
            }
        });

        // 恢复上次未完成任务 + 定时保存
        restoreTasks();
        new Thread(this::periodicSaveResume, "resume-saver").start();
        new Thread(this::periodicSnapshot, "tasks-snapshot").start();
    }

    public File getDownloadDir() { return downloadDir; }

    public synchronized File setDownloadDir(File path) {
        if (path == null) throw new IllegalArgumentException("目录不能为空");
        if (!path.exists() && !path.mkdirs()) {
            throw new IllegalArgumentException("无法创建目录: " + path.getAbsolutePath());
        }
        File probe = new File(path, ".write_test");
        try {
            try (FileOutputStream out = new FileOutputStream(probe)) {
                out.write("ok".getBytes("UTF-8"));
            }
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
        } catch (Exception e) {
            throw new IllegalArgumentException("目录不可写: " + path.getAbsolutePath());
        }
        this.downloadDir = path;
        Log.d(TAG, "下载目录已切换: " + path.getAbsolutePath());
        return path;
    }

    // ============================================================
    // 任务持久化
    // ============================================================
    private synchronized void saveTasksSnapshot() {
        try {
            JSONArray arr = new JSONArray();
            for (Map.Entry<String, String> e : types.entrySet()) {
                String id = e.getKey();
                JSONObject t = new JSONObject();
                t.put("id", id);
                File sp = savePaths.get(id);
                if (sp == null && "http".equals(e.getValue())) {
                    HttpDownload cur = httpDownloads.get(id);
                    if (cur != null) sp = cur.saveDir;
                }
                t.put("save_path", (sp != null ? sp : downloadDir).getAbsolutePath());
                if ("http".equals(e.getValue())) {
                    HttpDownload dl = httpDownloads.get(id);
                    if (dl == null) continue;
                    t.put("type", "http");
                    t.put("url", dl.url);
                    t.put("name", dl.name);
                    t.put("state", dl.state);
                    JSONObject hs = new JSONObject();
                    for (Map.Entry<String, String> h : dl.headers.entrySet())
                        hs.put(h.getKey(), h.getValue());
                    t.put("headers", hs);
                } else {
                    t.put("type", "torrent");
                    t.put("kind", "magnet");
                    Object src = torrentMeta.get(id);
                    if (src instanceof String) {
                        t.put("source", (String) src);
                    } else if (src instanceof File) {
                        t.put("kind", "file");
                        t.put("torrent_file", ((File) src).getAbsolutePath());
                        t.put("source", "");
                    } else {
                        t.put("source", "");
                    }
                }
                arr.put(t);
            }
            JSONObject root = new JSONObject();
            root.put("tasks", arr);
            File tmp = new File(stateDir, "tasks.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString(2).getBytes("UTF-8"));
            }
            // noinspection ResultOfMethodCallIgnored
            tmp.renameTo(tasksFile);
        } catch (Exception e) {
            Log.e(TAG, "保存 tasks.json 失败", e);
        }
    }

    // id -> magnet字符串 或 .torrent 文件
    private final Map<String, Object> torrentMeta = new ConcurrentHashMap<>();

    private void periodicSnapshot() {
        while (true) {
            try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
            saveTasksSnapshot();
        }
    }

    private void periodicSaveResume() {
        while (true) {
            try { Thread.sleep(30000); } catch (InterruptedException e) { return; }
            for (TorrentHandle h : torrentHandles.values()) {
                try { h.saveResumeData(); } catch (Exception ignored) {}
            }
        }
    }

    // ============================================================
    // 统一入口
    // ============================================================
    public String addUrl(String url, Map<String, String> headers) {
        String u = url.trim().toLowerCase();
        if (u.startsWith("http://") || u.startsWith("https://")) {
            return addHttpUrl(url.trim(), headers);
        }
        if (u.startsWith("magnet:")) {
            return addMagnet(url.trim());
        }
        throw new IllegalArgumentException("不支持的链接类型: " + url);
    }

    public String addMagnet(String magnet) {
        File sp = downloadDir;
        torrent_flags_t flags = torrent_flags_t.all();
        session.download(magnet, sp, flags);
        Sha1Hash hash = parseMagnetHash(magnet);
        String id = UUID.randomUUID().toString();
        types.put(id, "torrent");
        torrentMeta.put(id, magnet);
        savePaths.put(id, sp);
        if (hash != null) waitForHandle(id, hash);
        saveTasksSnapshot();
        return id;
    }

    public String addTorrentFile(byte[] data) {
        TorrentInfo info = new TorrentInfo(data);
        File sp = downloadDir;
        // 复制 .torrent 到状态目录
        String id = UUID.randomUUID().toString();
        File tFile = new File(stateDir, id + ".torrent");
        try (FileOutputStream out = new FileOutputStream(tFile)) {
            out.write(data);
        } catch (Exception e) {
            Log.e(TAG, "保存 .torrent 副本失败", e);
        }

        String hashHex = info.infoHash().toHex();
        File fr = new File(stateDir, hashHex + ".fastresume");
        boolean added = false;
        if (fr.exists()) {
            try {
                byte[] frData = readAll(fr);
                error_code ec = new error_code();
                add_torrent_params p = libtorrent.read_resume_data_ex(new byte_vector(frData), ec);
                p.setSave_path(sp.getAbsolutePath());
                AddTorrentParams wrapper = new AddTorrentParams(p);
                wrapper.setTorrentInfo(info);
                torrent_handle th = session.swig().add_torrent(p, ec);
                torrentHandles.put(id, new TorrentHandle(th));
                added = true;
            } catch (Exception e) {
                Log.e(TAG, "fastresume 恢复失败，回退普通添加", e);
            }
        }
        if (!added) {
            session.download(info, sp);
        }

        types.put(id, "torrent");
        torrentMeta.put(id, tFile);
        savePaths.put(id, sp);
        waitForHandle(id, info.infoHash());
        saveTasksSnapshot();
        return id;
    }

    public String addHttpUrl(String url, Map<String, String> headers) {
        File sp = downloadDir;
        String id = UUID.randomUUID().toString();
        HttpDownload dl = new HttpDownload(url, sp, stateDir, headers, null);
        types.put(id, "http");
        httpDownloads.put(id, dl);
        savePaths.put(id, sp);
        dl.start();
        saveTasksSnapshot();
        return id;
    }

    public boolean remove(String id) {
        String type = types.remove(id);
        if (type == null) return false;
        Object meta = torrentMeta.remove(id);
        savePaths.remove(id);
        if ("torrent".equals(type)) {
            TorrentHandle h = torrentHandles.remove(id);
            if (h != null) {
                try {
                    // fastresume 按 hash 命名，删除之
                    File fr = new File(stateDir, h.infoHash().toHex() + ".fastresume");
                    if (fr.exists()) //noinspection ResultOfMethodCallIgnored
                        fr.delete();
                    session.remove(h);
                } catch (Exception ignored) {}
            }
            if (meta instanceof File) //noinspection ResultOfMethodCallIgnored
                ((File) meta).delete();
        } else if ("http".equals(type)) {
            HttpDownload dl = httpDownloads.remove(id);
            if (dl != null) {
                dl.stop();
                if (dl.name != null && !dl.name.isEmpty()) {
                    File part = new File(dl.saveDir, dl.name + ".part");
                    if (part.exists()) //noinspection ResultOfMethodCallIgnored
                        part.delete();
                }
            }
        }
        saveTasksSnapshot();
        return true;
    }

    // ============================================================
    // 重启恢复
    // ============================================================
    private void restoreTasks() {
        if (!tasksFile.exists()) return;
        try {
            JSONObject root = new JSONObject(new String(readAll(tasksFile), "UTF-8"));
            JSONArray arr = root.optJSONArray("tasks");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.getJSONObject(i);
                String id = t.optString("id", UUID.randomUUID().toString());
                try {
                    if ("http".equals(t.optString("type"))) {
                        restoreHttp(id, t);
                    } else {
                        restoreTorrent(id, t);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "恢复任务失败: " + id, e);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "读取 tasks.json 失败", e);
        }
    }

    private void restoreHttp(String id, JSONObject t) {
        String url = t.optString("url", "");
        if (url.isEmpty()) return;
        String name = t.optString("name", "");
        File sp = resolveSavePath(t);
        Map<String, String> headers = new HashMap<>();
        JSONObject hs = t.optJSONObject("headers");
        if (hs != null) {
            java.util.Iterator<String> it = hs.keys();
            while (it.hasNext()) {
                String k = it.next();
                headers.put(k, hs.optString(k));
            }
        }
        File finalFile = name.isEmpty() ? null : new File(sp, name);
        if (finalFile != null && finalFile.exists()
                && !new File(sp, name + ".part").exists()) {
            // 已完成
            HttpDownload dl = new HttpDownload(url, sp, stateDir, headers, name);
            dl.state = "finished";
            dl.progress = 1.0;
            dl.totalSize = finalFile.length();
            dl.totalDownload = dl.totalSize;
            types.put(id, "http");
            httpDownloads.put(id, dl);
            savePaths.put(id, sp);
        } else {
            HttpDownload dl = new HttpDownload(url, sp, stateDir, headers,
                    name.isEmpty() ? null : name);
            types.put(id, "http");
            httpDownloads.put(id, dl);
            savePaths.put(id, sp);
            dl.start();
        }
        Log.d(TAG, "恢复 HTTP 任务: " + (name.isEmpty() ? url : name));
    }

    private void restoreTorrent(String id, JSONObject t) throws Exception {
        String kind = t.optString("kind", "magnet");
        TorrentInfo info = null;
        String magnet = t.optString("source", "");
        File tFile = null;

        if ("file".equals(kind)) {
            String path = t.optString("torrent_file", "");
            if (!path.isEmpty()) {
                tFile = new File(path);
                if (tFile.exists()) {
                    info = new TorrentInfo(readAll(tFile));
                    torrentMeta.put(id, tFile);
                }
            }
        } else {
            torrentMeta.put(id, magnet);
        }

        File sp = resolveSavePath(t);
        Sha1Hash hash = info != null ? info.infoHash() : parseMagnetHash(magnet);
        String hashHex = hash != null ? hash.toHex() : null;
        File fr = hashHex != null ? new File(stateDir, hashHex + ".fastresume") : null;

        boolean added = false;
        if (fr != null && fr.exists()) {
            try {
                byte[] frData = readAll(fr);
                error_code ec = new error_code();
                add_torrent_params p = libtorrent.read_resume_data_ex(new byte_vector(frData), ec);
                p.setSave_path(sp.getAbsolutePath());
                if (info != null) {
                    new AddTorrentParams(p).setTorrentInfo(info);
                }
                torrent_handle th = session.swig().add_torrent(p, ec);
                torrentHandles.put(id, new TorrentHandle(th));
                added = true;
                Log.d(TAG, "BT 任务通过 fastresume 恢复（免校验）: " + t.optString("name"));
            } catch (Exception e) {
                Log.e(TAG, "fastresume 恢复失败，回退重新添加", e);
            }
        }
        if (!added) {
            if (info != null) {
                session.download(info, sp);
            } else if (!magnet.isEmpty()) {
                session.download(magnet, sp, torrent_flags_t.all());
            } else {
                return;
            }
        }

        types.put(id, "torrent");
        savePaths.put(id, sp);
        if (hash != null) waitForHandle(id, hash);
        // 拿到 handle 后立即请求一次 fastresume
        TorrentHandle h = torrentHandles.get(id);
        if (h != null) {
            try { h.saveResumeData(); } catch (Exception ignored) {}
        }
    }

    // ============================================================
    // 列表
    // ============================================================
    public Map<String, Object> list() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();

        for (Map.Entry<String, HttpDownload> e : httpDownloads.entrySet()) {
            list.add(e.getValue().toInfoMap(e.getKey()));
        }
        for (Map.Entry<String, TorrentHandle> e : torrentHandles.entrySet()) {
            list.add(torrentToInfoMap(e.getKey(), e.getValue()));
        }
        result.put("downloads", list);
        return result;
    }

    private Map<String, Object> torrentToInfoMap(String id, TorrentHandle h) {
        Map<String, Object> info = new HashMap<>();
        info.put("id", id);
        try {
            TorrentStatus s = h.status();
            info.put("name", s.name());
            info.put("progress", s.progress());
            info.put("state", stateName(s.state()));
            info.put("download_rate", (long) s.downloadRate());
            info.put("upload_rate", (long) s.uploadRate());
            info.put("num_seeds", s.numSeeds());
            info.put("num_peers", s.numPeers());
            info.put("total_download", s.totalDownload());
            info.put("total_size", s.totalWanted());
        } catch (Exception e) {
            info.put("name", "未知");
            info.put("progress", 0.0);
            info.put("state", "error");
            info.put("download_rate", 0L);
            info.put("upload_rate", 0L);
            info.put("num_seeds", 0);
            info.put("num_peers", 0);
            info.put("total_download", 0L);
            info.put("total_size", 0L);
        }
        return info;
    }

    private void waitForHandle(final String id, final Sha1Hash hash) {
        new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                try {
                    TorrentHandle h = session.find(hash);
                    if (h != null && h.isValid()) {
                        torrentHandles.put(id, h);
                        try { h.saveResumeData(); } catch (Exception ignored) {}
                        return;
                    }
                } catch (Exception ignored) {}
                try { Thread.sleep(100); } catch (InterruptedException ignored) { return; }
            }
        }, "wait-handle-" + id).start();
    }

    private File resolveSavePath(JSONObject t) {
        String saved = t.optString("save_path", "");
        if (!saved.isEmpty()) return new File(saved);
        return downloadDir;
    }

    private Sha1Hash parseMagnetHash(String magnet) {
        try {
            int idx = magnet.indexOf("urn:btih:");
            if (idx < 0) return null;
            int start = idx + 9, end = start;
            while (end < magnet.length() && magnet.charAt(end) != '&') end++;
            String hashStr = magnet.substring(start, end).trim();
            if (hashStr.length() == 40) {
                return new Sha1Hash(sha1_hash.from_hex(hashStr));
            } else if (hashStr.length() == 32) {
                byte[] bytes = base32Decode(hashStr);
                if (bytes != null && bytes.length == 20)
                    return new Sha1Hash(new sha1_hash(new byte_vector(bytes)));
            }
        } catch (Exception e) {
            Log.e(TAG, "解析 magnet hash 失败", e);
        }
        return null;
    }

    private byte[] base32Decode(String s) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        s = s.toUpperCase().replaceAll("=", "");
        byte[] result = new byte[s.length() * 5 / 8];
        int buffer = 0, bitsLeft = 0, pos = 0;
        for (char c : s.toCharArray()) {
            int idx = alphabet.indexOf(c);
            if (idx < 0) return null;
            buffer = (buffer << 5) | idx;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                result[pos++] = (byte) (buffer >> bitsLeft);
            }
        }
        return result;
    }

    private String stateName(TorrentStatus.State s) {
        if (s == null) return "unknown";
        switch (s) {
            case CHECKING_FILES: return "checking";
            case DOWNLOADING_METADATA: return "downloading metadata";
            case DOWNLOADING: return "downloading";
            case FINISHED: return "finished";
            case SEEDING: return "seeding";
            case CHECKING_RESUME_DATA: return "checking fastresume";
            default: return "queued";
        }
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    public void stop() { session.stop(); }

    // ============================================================
    // HTTP 下载（支持 Range 断点续传）
    // ============================================================
    static class HttpDownload {
        final String url;
        final File saveDir;
        final Map<String, String> headers;
        volatile String name = "";
        volatile double progress = 0.0;
        volatile String state = "queued";
        volatile long downloadRate = 0;
        volatile long totalDownload = 0;
        volatile long totalSize = 0;
        volatile String errorMsg = "";
        private final Thread thread;
        private volatile boolean stopped = false;

        HttpDownload(String url, File saveDir, File stateDir,
                     Map<String, String> headers, String presetName) {
            this.url = url;
            this.saveDir = saveDir;
            this.headers = headers != null ? headers : new HashMap<>();
            if (presetName != null) this.name = presetName;
            this.thread = new Thread(this::run, "http-dl-" + UUID.randomUUID().toString().substring(0, 8));
        }

        void start() { thread.start(); }

        void stop() { stopped = true; }

        private HttpURLConnection openConn(long resumeFrom) throws Exception {
            URL u = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            for (Map.Entry<String, String> h : headers.entrySet()) {
                conn.setRequestProperty(h.getKey(), h.getValue());
            }
            if (resumeFrom > 0) {
                conn.setRequestProperty("Range", "bytes=" + resumeFrom + "-");
            }
            conn.connect();
            return conn;
        }

        void run() {
            HttpURLConnection conn = null;
            try {
                long resumeFrom = 0;
                File tmpFile = null;
                if (!name.isEmpty()) {
                    tmpFile = new File(saveDir, name + ".part");
                    if (tmpFile.exists()) resumeFrom = tmpFile.length();
                }

                conn = openConn(resumeFrom);
                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK && code != 206) {
                    state = "error";
                    errorMsg = "HTTP " + code;
                    return;
                }

                // 首次：从响应头获取文件名
                if (name.isEmpty()) {
                    String cd = conn.getHeaderField("Content-Disposition");
                    if (cd != null && cd.contains("filename=")) {
                        String fn = cd.substring(cd.indexOf("filename=") + 9)
                                .replaceAll("\"", "").replaceAll("'", "").trim();
                        if (fn.startsWith("UTF-8''"))
                            fn = URLDecoder.decode(fn.substring(7), "UTF-8");
                        if (!fn.isEmpty()) name = fn;
                    }
                    if (name.isEmpty()) {
                        String path = URLDecoder.decode(new URL(url).getPath(), "UTF-8");
                        String fn = path.substring(path.lastIndexOf('/') + 1);
                        name = fn.isEmpty() ? "download.bin" : fn;
                    }
                    tmpFile = new File(saveDir, name + ".part");
                    if (tmpFile.exists() && resumeFrom == 0) {
                        conn.disconnect();
                        resumeFrom = tmpFile.length();
                        conn = openConn(resumeFrom);
                        code = conn.getResponseCode();
                    }
                }

                File outFile = new File(saveDir, name);
                boolean append;
                if (code == 206 && resumeFrom > 0) {
                    // 续传
                    String cr = conn.getHeaderField("Content-Range");
                    long total = 0;
                    if (cr != null && cr.contains("/")) {
                        try {
                            total = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1));
                        } catch (NumberFormatException ignored) {}
                    }
                    long len = conn.getContentLengthLong();
                    totalSize = total > 0 ? total : (resumeFrom + (len > 0 ? len : 0));
                    totalDownload = resumeFrom;
                    append = true;
                    Log.d(TAG, "HTTP 断点续传: 从 " + resumeFrom + " 字节继续");
                } else {
                    totalSize = conn.getContentLengthLong();
                    totalDownload = 0;
                    resumeFrom = 0;
                    append = false;
                }

                state = "downloading";
                long lastBytes = totalDownload;
                long lastTime = System.currentTimeMillis();

                try (BufferedInputStream in = new BufferedInputStream(conn.getInputStream(), 8192);
                     FileOutputStream out = new FileOutputStream(tmpFile, append)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while (!stopped && (n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        totalDownload += n;
                        long now = System.currentTimeMillis();
                        long elapsed = now - lastTime;
                        if (elapsed >= 500) {
                            downloadRate = (totalDownload - lastBytes) * 1000 / elapsed;
                            lastBytes = totalDownload;
                            lastTime = now;
                        }
                        if (totalSize > 0) progress = (double) totalDownload / totalSize;
                    }
                }

                if (stopped) {
                    state = "queued";
                    return;
                }

                if (tmpFile.renameTo(outFile)) {
                    progress = 1.0;
                    downloadRate = 0;
                    state = "finished";
                } else {
                    state = "error";
                    errorMsg = "重命名文件失败";
                }
            } catch (Exception e) {
                state = "error";
                errorMsg = e.getMessage() != null ? e.getMessage() : e.toString();
                Log.e(TAG, "HTTP 下载出错（.part 已保留，重启可续传）: " + errorMsg);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        Map<String, Object> toInfoMap(String id) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", id);
            m.put("name", name.isEmpty() ? "(连接中...)" : name);
            m.put("progress", progress);
            m.put("state", state);
            m.put("download_rate", downloadRate);
            m.put("upload_rate", 0L);
            m.put("num_seeds", 0);
            m.put("num_peers", 0);
            m.put("total_download", totalDownload);
            m.put("total_size", totalSize);
            return m;
        }
    }
}
