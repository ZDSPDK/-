package com.torrent.app;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import fi.iki.elonen.NanoHTTPD;

import java.io.File;

public class MainActivity extends AppCompatActivity {

    private static final int WEB_PORT = 8888;
    private static final int REQ_PICK_DIR = 1001;
    private static final String PREFS = "bt_downloader";
    private static final String KEY_DOWNLOAD_DIR = "download_dir";

    private WebView webView;
    private LocalServer server;
    private TorrentManager torrentManager;
    private SharedPreferences prefs;

    private final Object pickLock = new Object();
    private volatile String pickPath;
    private volatile boolean pickDone;
    private volatile String pickError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        requestPermissions();

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        File defaultDir = defaultDownloadDir();
        String saved = prefs.getString(KEY_DOWNLOAD_DIR, defaultDir.getAbsolutePath());
        File downloadDir = new File(saved);
        if (!downloadDir.exists()) downloadDir.mkdirs();
        // 任务状态始终放在默认目录下，避免切换下载路径后丢失续传数据
        File stateDir = new File(defaultDir, ".bt_downloader");
        torrentManager = new TorrentManager(downloadDir, stateDir);

        try {
            server = new LocalServer(WEB_PORT, torrentManager, this);
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        } catch (Exception e) {
            e.printStackTrace();
        }

        webView = findViewById(R.id.webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl("http://127.0.0.1:" + WEB_PORT + "/");
    }

    static File defaultDownloadDir() {
        return new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "TorrentDownloads");
    }

    /** 供本地 HTTP 服务在后台线程调用：弹出系统文件夹选择器并等待结果 */
    public String pickDownloadDir() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && !Environment.isExternalStorageManager()) {
            runOnUiThread(this::openAllFilesSettings);
            throw new IllegalStateException("请先授予「所有文件访问」权限，返回后再次点击浏览");
        }

        synchronized (pickLock) {
            pickDone = false;
            pickPath = null;
            pickError = null;
        }

        runOnUiThread(() -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, REQ_PICK_DIR);
        });

        synchronized (pickLock) {
            long deadline = System.currentTimeMillis() + 300_000;
            while (!pickDone) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) break;
                try {
                    pickLock.wait(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (pickError != null) throw new IllegalStateException(pickError);
            return pickPath;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_DIR) return;

        String path = null;
        String error = null;
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {}
            path = treeUriToPath(uri);
            if (path == null) {
                error = "无法解析该文件夹路径，请选择内部存储中的普通目录";
            } else {
                try {
                    File dir = torrentManager.setDownloadDir(new File(path));
                    path = dir.getAbsolutePath();
                    prefs.edit().putString(KEY_DOWNLOAD_DIR, path).apply();
                } catch (Exception e) {
                    error = e.getMessage() != null ? e.getMessage() : "设置下载目录失败";
                    path = null;
                }
            }
        }

        synchronized (pickLock) {
            pickPath = path;
            pickError = error;
            pickDone = true;
            pickLock.notifyAll();
        }
    }

    private String treeUriToPath(Uri uri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(uri);
            String[] split = docId.split(":", 2);
            if (split.length == 0) return null;
            String type = split[0];
            String rel = split.length > 1 ? split[1] : "";
            File root;
            if ("primary".equalsIgnoreCase(type)) {
                root = Environment.getExternalStorageDirectory();
            } else {
                root = new File("/storage/" + type);
                if (!root.exists()) return null;
            }
            return rel.isEmpty() ? root.getAbsolutePath() : new File(root, rel).getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private void openAllFilesSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception ignored) {}
        }
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{
                                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                Manifest.permission.READ_EXTERNAL_STORAGE
                        }, 1);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        synchronized (pickLock) {
            pickDone = true;
            pickLock.notifyAll();
        }
        super.onDestroy();
        if (server != null) {
            server.stop();
        }
        if (torrentManager != null) {
            torrentManager.stop();
        }
    }
}
