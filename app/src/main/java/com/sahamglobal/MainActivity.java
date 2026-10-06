package com.sahamglobal;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String IDX_URL = "https://www.idx.co.id/id/perusahaan-tercatat/keterbukaan-informasi";
    private static final int REQ_STORAGE = 102;

    private WebView hiddenWebView;
    private TextView statusTextView;
    private ProgressBar loadingBar;
    private Button btnRefresh;
    private Button btnSearch;
    private Button btnDialogMenu;
    private ListView announcementListView;

    private final List<AnnouncementItem> allItems = new ArrayList<>();
    private final List<AnnouncementItem> displayItems = new ArrayList<>();
    private ArrayAdapter<String> listAdapter;
    private final List<String> listTitles = new ArrayList<>();

    private String filterQuery = "";
    private String pendingDownloadUrl = "";
    private String pendingDownloadName = "";

    public static class AnnouncementItem {
        String title;
        String date;
        String fileName;
        String url;
        String code;

        AnnouncementItem(String title, String date, String fileName, String url) {
            this.title = title;
            this.date = date;
            this.fileName = fileName;
            this.url = url;
            this.code = extractStockCode(title);
        }

        private String extractStockCode(String t) {
            if (t == null) return "";
            int start = t.lastIndexOf('[');
            int end = t.lastIndexOf(']');
            if (start != -1 && end != -1 && end > start) {
                return t.substring(start + 1, end).trim();
            }
            return "";
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Tata letak utama native Android murni (Sangat ramah pembaca layar)
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 16, 16, 16);

        // Baris status & indikator
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, 0, 0, 8);

        loadingBar = new ProgressBar(this);
        loadingBar.setLayoutParams(new LinearLayout.LayoutParams(48, 48));
        statusRow.addView(loadingBar);

        statusTextView = new TextView(this);
        statusTextView.setText("Menghubungkan ke server IDX...");
        statusTextView.setTextSize(14);
        statusTextView.setPadding(16, 0, 0, 0);
        statusRow.addView(statusTextView);

        root.addView(statusRow);

        // Baris tombol aksi cepat
        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setPadding(0, 8, 0, 12);

        btnRefresh = new Button(this);
        btnRefresh.setText("SEGARKAN");
        btnRefresh.setContentDescription("Tombol, Segarkan pengumuman saham");
        btnRefresh.setOnClickListener(v -> refreshData());

        btnSearch = new Button(this);
        btnSearch.setText("CARI KODE");
        btnSearch.setContentDescription("Tombol, Cari atau filter kode saham");
        btnSearch.setOnClickListener(v -> showSearchDialog());

        btnDialogMenu = new Button(this);
        btnDialogMenu.setText("MENU DIALOG");
        btnDialogMenu.setContentDescription("Tombol, Buka daftar dalam menu dialog");
        btnDialogMenu.setOnClickListener(v -> showAnnouncementDialogMenu());

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        buttonRow.addView(btnRefresh, btnParams);
        buttonRow.addView(btnSearch, btnParams);
        buttonRow.addView(btnDialogMenu, btnParams);

        root.addView(buttonRow);

        // Daftar tampilan pengumuman (Native ListView)
        announcementListView = new ListView(this);
        announcementListView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        listAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, listTitles);
        announcementListView.setAdapter(listAdapter);

        announcementListView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < displayItems.size()) {
                showDetailActionDialog(displayItems.get(position));
            }
        });

        root.addView(announcementListView);

        // WebView tersembunyi (Hanya sebagai mesin pengambil data di latar belakang)
        hiddenWebView = new WebView(this);
        hiddenWebView.setVisibility(View.GONE);
        root.addView(hiddenWebView);

        setContentView(root);

        initHiddenWebView();
        refreshData();
    }

    private void initHiddenWebView() {
        WebSettings s = hiddenWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadsImagesAutomatically(false); // Hemat kuota & sangat cepat
        s.setBlockNetworkImage(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        hiddenWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void kirimDataPengumuman(String json) {
                runOnUiThread(() -> prosesHasilEkstraksi(json));
            }
        }, "AndroidBridge");

        hiddenWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                loadingBar.setVisibility(View.VISIBLE);
                statusTextView.setText("Sedang memuat data dari IDX...");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                statusTextView.setText("Menganalisis pengumuman saham...");
                injeksiEkstraktorPengumuman(view);
            }
        });
    }

    private void injeksiEkstraktorPengumuman(WebView view) {
        String js = "javascript:(function() {" +
                "  /* 1. Otomatis pilih opsi 'Saham' jika belum */" +
                "  var pemicu = document.querySelectorAll('button, div, span, input, a');" +
                "  for (var k = 0; k < pemicu.length; k++) {" +
                "    var txt = (pemicu[k].innerText || pemicu[k].value || '').trim();" +
                "    if (txt === 'Jenis - Semua') {" +
                "      var btn = pemicu[k].closest('button, [role=\"button\"], .multiselect, .v-select') || pemicu[k];" +
                "      try { btn.click(); } catch(e){}" +
                "      break;" +
                "    }" +
                "  }" +
                "  var opsi = document.querySelectorAll('li, div[role=\"option\"], a, span, button');" +
                "  for (var m = 0; m < opsi.length; m++) {" +
                "    if ((opsi[m].innerText || opsi[m].textContent || '').trim() === 'Saham') {" +
                "      try { opsi[m].click(); } catch(e){}" +
                "      break;" +
                "    }" +
                "  }" +
                "" +
                "  /* 2. Ekstraksi daftar pengumuman saham */" +
                "  function ambilDaftar() {" +
                "    var hasil = [];" +
                "    var pdfLinks = document.querySelectorAll('a[href*=\".pdf\"], a[href*=\"lamp\"]');" +
                "    var seen = {};" +
                "    pdfLinks.forEach(function(a) {" +
                "      var href = a.href;" +
                "      if (seen[href]) return;" +
                "      seen[href] = true;" +
                "      var namaFile = (a.innerText || a.textContent || '').trim();" +
                "      var container = a.parentElement;" +
                "      var tgl = '';" +
                "      var jdl = '';" +
                "      for (var d = 0; d < 8 && container && container !== document.body; d++) {" +
                "        var raw = container.innerText || '';" +
                "        var m = raw.match(/\\d{1,2}\\s+[A-Za-z]+\\s+\\d{4}(\\s+\\d{1,2}:\\d{2}(:\\d{2})?)?/);" +
                "        if (m) {" +
                "          tgl = m[0];" +
                "          var baris = raw.split('\\n');" +
                "          for (var i = 0; i < baris.length; i++) {" +
                "            var b = baris[i].trim();" +
                "            if (b.length > 5 && b !== tgl && b.indexOf(namaFile) === -1 && b.indexOf('.pdf') === -1) {" +
                "              jdl = b;" +
                "              break;" +
                "            }" +
                "          }" +
                "          break;" +
                "        }" +
                "        container = container.parentElement;" +
                "      }" +
                "      if (!jdl) jdl = namaFile;" +
                "      hasil.push({ title: jdl, date: tgl, fileName: namaFile, url: href });" +
                "    });" +
                "    if (hasil.length > 0) {" +
                "      AndroidBridge.kirimDataPengumuman(JSON.stringify(hasil));" +
                "    }" +
                "  }" +
                "" +
                "  ambilDaftar();" +
                "  var putaran = 0;" +
                "  var timer = setInterval(function() {" +
                "    putaran++;" +
                "    ambilDaftar();" +
                "    if (putaran >= 6) clearInterval(timer);" +
                "  }, 800);" +
                "})()";

        view.loadUrl(js);
    }

    private void prosesHasilEkstraksi(String jsonStr) {
        try {
            JSONArray arr = new JSONArray(jsonStr);
            if (arr.length() == 0) return;

            allItems.clear();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String t = obj.optString("title", "");
                String d = obj.optString("date", "");
                String f = obj.optString("fileName", "");
                String u = obj.optString("url", "");
                if (!u.isEmpty()) {
                    allItems.add(new AnnouncementItem(t, d, f, u));
                }
            }

            loadingBar.setVisibility(View.GONE);
            applyFilter();

            Toast.makeText(this, "Berhasil memuat " + allItems.size() + " pengumuman.", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }

    private void applyFilter() {
        displayItems.clear();
        listTitles.clear();

        for (AnnouncementItem item : allItems) {
            if (filterQuery.isEmpty() ||
                item.title.toLowerCase(Locale.ROOT).contains(filterQuery.toLowerCase(Locale.ROOT)) ||
                item.code.toLowerCase(Locale.ROOT).contains(filterQuery.toLowerCase(Locale.ROOT))) {

                displayItems.add(item);
                String label = String.format("%d. %s\n(%s)",
                        displayItems.size(),
                        item.title,
                        item.date.isEmpty() ? "Terbaru" : item.date);
                listTitles.add(label);
            }
        }

        listAdapter.notifyDataSetChanged();

        String status = "Ditemukan " + displayItems.size() + " pengumuman";
        if (!filterQuery.isEmpty()) {
            status += " (Filter: " + filterQuery + ")";
        }
        statusTextView.setText(status);
    }

    // Menu dialog bergaya Pengelola GitHub
    private void showAnnouncementDialogMenu() {
        if (displayItems.isEmpty()) {
            Toast.makeText(this, "Daftar pengumuman masih kosong.", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] itemLabels = new String[displayItems.size() + 2];
        itemLabels[0] = "Segarkan pengumuman";
        itemLabels[1] = filterQuery.isEmpty() ? "Cari / Filter kode saham" : "Hapus filter saat ini (" + filterQuery + ")";

        for (int i = 0; i < displayItems.size(); i++) {
            AnnouncementItem it = displayItems.get(i);
            String prefix = it.code.isEmpty() ? "" : "[" + it.code + "] ";
            itemLabels[i + 2] = String.format("%d. %s%s", i + 1, prefix, it.title);
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Pengumuman Saham IDX (" + displayItems.size() + ")");
        b.setItems(itemLabels, (dialog, which) -> {
            if (which == 0) {
                refreshData();
            } else if (which == 1) {
                if (filterQuery.isEmpty()) {
                    showSearchDialog();
                } else {
                    filterQuery = "";
                    applyFilter();
                    Toast.makeText(this, "Filter dihapus.", Toast.LENGTH_SHORT).show();
                }
            } else {
                int itemIdx = which - 2;
                if (itemIdx >= 0 && itemIdx < displayItems.size()) {
                    showDetailActionDialog(displayItems.get(itemIdx));
                }
            }
        });
        b.setNegativeButton("tutup", null);
        b.show();
    }

    // Dialog aksi saat suatu pengumuman dipilih
    private void showDetailActionDialog(AnnouncementItem item) {
        String pesan = "Judul:\n" + item.title + "\n\n" +
                "Tanggal Rilis:\n" + (item.date.isEmpty() ? "-" : item.date) + "\n\n" +
                "Nama Berkas:\n" + (item.fileName.isEmpty() ? "Lampiran.pdf" : item.fileName);

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(item.code.isEmpty() ? "Rincian Pengumuman" : "Emiten: [" + item.code + "]");
        b.setMessage(pesan);

        b.setPositiveButton("unduh PDF", (d, w) -> {
            checkPermissionAndDownload(item.url, item.fileName);
        });

        b.setNeutralButton("salin tautan", (d, w) -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Tautan PDF IDX", item.url));
                Toast.makeText(this, "Tautan PDF berhasil disalin!", Toast.LENGTH_SHORT).show();
            }
        });

        b.setNegativeButton("kembali", null);
        b.show();
    }

    private void showSearchDialog() {
        EditText input = new EditText(this);
        input.setHint("Contoh: BBCA, PTPP, atau kata kunci");
        input.setSingleLine(true);
        if (!filterQuery.isEmpty()) {
            input.setText(filterQuery);
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Cari Pengumuman / Saham");
        b.setMessage("Masukkan 4 huruf kode saham atau kata kunci:");
        b.setView(input);

        b.setPositiveButton("terapkan", (d, w) -> {
            filterQuery = input.getText().toString().trim();
            applyFilter();
            Toast.makeText(this, "Menampilkan " + displayItems.size() + " hasil.", Toast.LENGTH_SHORT).show();
        });

        b.setNeutralButton("reset filter", (d, w) -> {
            filterQuery = "";
            applyFilter();
        });

        b.setNegativeButton("batal", null);
        b.show();
    }

    private void refreshData() {
        if (!isOnline()) {
            statusTextView.setText("Koneksi terputus");
            Toast.makeText(this, "Tidak ada koneksi internet.", Toast.LENGTH_LONG).show();
            return;
        }
        loadingBar.setVisibility(View.VISIBLE);
        statusTextView.setText("Menghubungkan ke server IDX...");
        hiddenWebView.loadUrl(IDX_URL);
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo n = cm.getActiveNetworkInfo();
            return n != null && n.isConnected();
        }
        return false;
    }

    private void checkPermissionAndDownload(String url, String fileName) {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                pendingDownloadUrl = url;
                pendingDownloadName = fileName;
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
                return;
            }
        }
        startDownload(url, fileName);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (!pendingDownloadUrl.isEmpty()) {
                startDownload(pendingDownloadUrl, pendingDownloadName);
                pendingDownloadUrl = "";
                pendingDownloadName = "";
            }
        } else {
            Toast.makeText(this, "Izin penyimpanan dibutuhkan untuk mengunduh.", Toast.LENGTH_SHORT).show();
        }
    }

    private void startDownload(String url, String fileName) {
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            if (fileName == null || fileName.isEmpty()) {
                fileName = URLUtil.guessFileName(url, null, "application/pdf");
            }
            if (!fileName.toLowerCase().endsWith(".pdf")) {
                fileName += ".pdf";
            }

            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) {
                req.addRequestHeader("cookie", cookies);
            }
            req.addRequestHeader("User-Agent", hiddenWebView.getSettings().getUserAgentString());
            req.setDescription("Mengunduh dokumen pengumuman saham IDX...");
            req.setTitle(fileName);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(req);
                Toast.makeText(this, "Mengunduh: " + fileName, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Gagal mengunduh berkas.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        if (hiddenWebView != null) {
            hiddenWebView.destroy();
        }
        super.onDestroy();
    }
}