package com.sahamglobal;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String IDX_URL = "https://www.idx.co.id/id/perusahaan-tercatat/keterbukaan-informasi";
    private static final int REQ_STORAGE = 102;

    private WebView webView;
    private ProgressBar progressBar;
    private TextView statusText;
    private Button refreshButton;

    private String pendingDownloadUrl = "";
    private String pendingContentDisposition = "";
    private String pendingMimeType = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Tata letak utama antarmuka
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // Baris tombol atas
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setPadding(16, 12, 16, 12);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        refreshButton = new Button(this);
        refreshButton.setText("Segarkan Halaman");
        refreshButton.setContentDescription("Tombol, Segarkan halaman keterbukaan informasi saham");
        refreshButton.setOnClickListener(v -> refreshPage());

        statusText = new TextView(this);
        statusText.setText("Siap memuat data");
        statusText.setPadding(16, 0, 0, 0);
        statusText.setTextSize(14);

        topBar.addView(refreshButton);
        topBar.addView(statusText);
        root.addView(topBar);

        // Indikator proses pemuatan
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar);

        // WebView penampil keterbukaan informasi
        webView = new WebView(this);
        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        webView.setLayoutParams(webParams);
        root.addView(webView);

        setContentView(root);

        configureWebView();
        loadIdxPage();
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);

        // Muat data langsung tanpa cache agar selalu segar
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        // 1. Perintah tangkap unduhan otomatis dari web
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                checkPermissionAndDownload(url, contentDisposition, mimetype);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                progressBar.setVisibility(View.VISIBLE);
                statusText.setText("Memuat data saham terbaru...");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                statusText.setText("Halaman berhasil diperbarui");

                // Sembunyikan bagian header dan footer agar pembaca layar langsung membaca daftar tabel pengumuman
                String cleanJs = "javascript:(function() {" +
                        "var elements = document.querySelectorAll('header, footer, .banner, .header-wrapper');" +
                        "for (var i = 0; i < elements.length; i++) { elements[i].style.display = 'none'; }" +
                        "})()";
                view.loadUrl(cleanJs);
            }

            // 2. Perintah cegat tautan dokumen PDF/lampiran agar tidak blank
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (Build.VERSION.SDK_INT >= 24) {
                    String url = request.getUrl().toString();
                    if (isDownloadableFile(url)) {
                        checkPermissionAndDownload(url, "", "application/pdf");
                        return true;
                    }
                    view.loadUrl(url);
                }
                return false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (isDownloadableFile(url)) {
                    checkPermissionAndDownload(url, "", "application/pdf");
                    return true;
                }
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress == 100) {
                    progressBar.setVisibility(View.GONE);
                }
            }
        });
    }

    private boolean isDownloadableFile(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.endsWith(".pdf") || u.contains(".pdf?") ||
               u.endsWith(".zip") || u.contains(".zip?") ||
               u.endsWith(".xlsx") || u.contains(".xlsx?") ||
               u.endsWith(".docx") || u.contains(".docx?");
    }

    private void checkPermissionAndDownload(String url, String contentDisposition, String mimeType) {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                pendingDownloadUrl = url;
                pendingContentDisposition = contentDisposition;
                pendingMimeType = mimeType;
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
                return;
            }
        }
        executeDownload(url, contentDisposition, mimeType);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (!pendingDownloadUrl.isEmpty()) {
                executeDownload(pendingDownloadUrl, pendingContentDisposition, pendingMimeType);
                pendingDownloadUrl = "";
            }
        } else {
            Toast.makeText(this, "Izin penyimpanan dibutuhkan untuk mengunduh berkas.", Toast.LENGTH_SHORT).show();
        }
    }

    private void executeDownload(String url, String contentDisposition, String mimeType) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);

            if (mimeType != null && mimeType.equalsIgnoreCase("application/pdf") && !fileName.toLowerCase().endsWith(".pdf")) {
                fileName += ".pdf";
            }

            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) {
                request.addRequestHeader("cookie", cookies);
            }
            request.addRequestHeader("User-Agent", webView.getSettings().getUserAgentString());
            request.setDescription("Mengunduh dokumen keterbukaan informasi saham...");
            request.setTitle(fileName);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
                Toast.makeText(this, "Mulai mengunduh: " + fileName, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Gagal mengunduh berkas laporan.", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadIdxPage() {
        if (!isOnline()) {
            Toast.makeText(this, "Tidak ada koneksi internet. Periksa koneksi Anda.", Toast.LENGTH_LONG).show();
            statusText.setText("Koneksi terputus");
            return;
        }
        webView.loadUrl(IDX_URL);
    }

    private void refreshPage() {
        if (!isOnline()) {
            Toast.makeText(this, "Koneksi terputus. Gagal menyegarkan.", Toast.LENGTH_SHORT).show();
            return;
        }
        statusText.setText("Menyegarkan halaman...");
        Toast.makeText(this, "Menyegarkan data saham...", Toast.LENGTH_SHORT).show();
        webView.reload();
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnected();
        }
        return false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.reload();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}