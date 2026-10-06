package com.sahamglobal;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final String IDX_URL = "https://www.idx.co.id/id/perusahaan-tercatat/keterbukaan-informasi";
    private static final int REQ_STORAGE = 102;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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

        // Tata letak utama antarmuka native (Aksesibel untuk pembaca layar)
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 16, 16, 16);

        // Baris status koneksi & pemuatan
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, 0, 0, 8);

        loadingBar = new ProgressBar(this);
        loadingBar.setLayoutParams(new LinearLayout.LayoutParams(48, 48));
        statusRow.addView(loadingBar);

        statusTextView = new TextView(this);
        statusTextView.setText("Menghubungkan ke server IDX (Khusus Saham)...");
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

        // Tampilan daftar pengumuman saham
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

        // WebView tersembunyi sebagai parser data di latar belakang
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
        s.setLoadsImagesAutomatically(false);
        s.setBlockNetworkImage(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        hiddenWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void kirimDataPengumuman(String json) {
                mainHandler.post(() -> prosesHasilEkstraksi(json));
            }
        }, "AndroidBridge");

        hiddenWebView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                unduhLewatDownloadManagerResmi(url, pendingDownloadName);
            }
        });

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
                statusTextView.setText("Memilih kategori Saham & menganalisis...");
                injeksiEkstraktorPengumuman(view);
            }
        });
    }

    private void injeksiEkstraktorPengumuman(WebView view) {
        String js = "javascript:(function() {" +
                "  /* 1. Otomatis pilih jenis 'Saham' pada dropdown situs */" +
                "  function aktifkanDropdownSaham() {" +
                "    var elemen = document.querySelectorAll('button, div, span, input, a');" +
                "    for (var k = 0; k < elemen.length; k++) {" +
                "      var txt = (elemen[k].innerText || elemen[k].value || '').trim();" +
                "      if (txt === 'Jenis - Semua') {" +
                "        var btn = elemen[k].closest('button, [role=\"button\"], .multiselect, .v-select') || elemen[k];" +
                "        try { btn.click(); } catch(e){}" +
                "        setTimeout(function() {" +
                "          var opsi = document.querySelectorAll('li, div[role=\"option\"], a, span, button');" +
                "          for (var m = 0; m < opsi.length; m++) {" +
                "            var namaOpsi = (opsi[m].innerText || opsi[m].textContent || '').trim();" +
                "            if (namaOpsi === 'Saham') {" +
                "              try {" +
                "                opsi[m].click();" +
                "                opsi[m].dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));" +
                "              } catch(e){}" +
                "              break;" +
                "            }" +
                "          }" +
                "        }, 350);" +
                "        break;" +
                "      }" +
                "    }" +
                "  }" +
                "" +
                "  /* 2. Ekstraksi daftar pengumuman */" +
                "  function ambilDaftar() {" +
                "    var hasil = [];" +
                "    var pdfLinks = document.querySelectorAll('a[href*=\".pdf\"], a[href*=\"lamp\"]');" +
                "    var seenUrl = {};" +
                "    pdfLinks.forEach(function(a) {" +
                "      var href = a.href;" +
                "      if (!href || seenUrl[href]) return;" +
                "      seenUrl[href] = true;" +
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
                "  aktifkanDropdownSaham();" +
                "  var putaran = 0;" +
                "  var timer = setInterval(function() {" +
                "    putaran++;" +
                "    aktifkanDropdownSaham();" +
                "    ambilDaftar();" +
                "    if (putaran >= 15) clearInterval(timer);" +
                "  }, 800);" +
                "})()";

        view.loadUrl(js);
    }

    private boolean isKategoriSahamMurni(AnnouncementItem item) {
        String t = (item.title + " " + item.fileName).toLowerCase(Locale.ROOT);

        if (t.contains("nilai aktiva bersih") ||
            t.contains("komposisi portofolio") ||
            t.contains("laporan harian nab") ||
            t.contains("etf") ||
            t.contains("reksa dana") ||
            t.contains("reksadana")) {
            return false;
        }

        if (t.contains("obligasi") ||
            t.contains("sukuk") ||
            t.contains("surat utang") ||
            t.contains("kupon") ||
            t.contains("bunga tahunan") ||
            t.contains("jatuh tempo obligasi")) {
            return false;
        }

        if (t.contains("efek beragun") ||
            t.contains("eba") ||
            t.contains("dire ") ||
            t.contains("dinfra")) {
            return false;
        }

        if (item.code != null && !item.code.isEmpty()) {
            String c = item.code.toUpperCase(Locale.ROOT);
            if (c.startsWith("X") && c.length() == 4) {
                return false;
            }
            if (c.startsWith("R-")) {
                return false;
            }
        }

        return true;
    }

    private void prosesHasilEkstraksi(String jsonStr) {
        try {
            JSONArray arr = new JSONArray(jsonStr);
            if (arr.length() == 0) return;

            allItems.clear();
            Set<String> seenPost = new HashSet<>();

            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String t = obj.optString("title", "");
                String d = obj.optString("date", "");
                String f = obj.optString("fileName", "");
                String u = obj.optString("url", "");

                if (!u.isEmpty()) {
                    AnnouncementItem item = new AnnouncementItem(t, d, f, u);

                    if (isKategoriSahamMurni(item)) {
                        String postKey = item.title.trim().toLowerCase(Locale.ROOT) + "|" + item.date.trim();
                        if (!seenPost.contains(postKey)) {
                            seenPost.add(postKey);
                            allItems.add(item);
                        }
                    }
                }
            }

            loadingBar.setVisibility(View.GONE);
            applyFilter();

            Toast.makeText(this, "Memuat " + allItems.size() + " pengumuman saham.", Toast.LENGTH_SHORT).show();
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

        String status = "Kategori: Saham (" + displayItems.size() + " pengumuman)";
        if (!filterQuery.isEmpty()) {
            status += " [Filter: " + filterQuery + "]";
        }
        statusTextView.setText(status);
    }

    private void showAnnouncementDialogMenu() {
        if (displayItems.isEmpty()) {
            Toast.makeText(this, "Daftar pengumuman saham kosong.", Toast.LENGTH_SHORT).show();
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
        statusTextView.setText("Menghubungkan ke server IDX (Khusus Saham)...");
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
        pendingDownloadUrl = url;
        pendingDownloadName = fileName;

        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
                return;
            }
        }
        unduhLewatDownloadManagerResmi(url, fileName);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (!pendingDownloadUrl.isEmpty()) {
                unduhLewatDownloadManagerResmi(pendingDownloadUrl, pendingDownloadName);
                pendingDownloadUrl = "";
                pendingDownloadName = "";
            }
        } else {
            Toast.makeText(this, "Izin penyimpanan dibutuhkan untuk mengunduh.", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Solusi Tuntas Unduh PDF (Bebas Error 403 Forbidden):
     * Memanfaatkan sesi resmi yang sudah terautentikasi di WebView dan meneruskannya
     * ke DownloadManager lengkap dengan Cookie, User-Agent, dan Referer resmi IDX.
     */
    private void unduhLewatDownloadManagerResmi(String urlTarget, String initialFileName) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(urlTarget));

            String safeName = initialFileName;
            if (safeName == null || safeName.trim().isEmpty()) {
                safeName = URLUtil.guessFileName(urlTarget, null, "application/pdf");
            }
            safeName = safeName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            if (!safeName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                safeName += ".pdf";
            }

            // Pasang identitas sesi resmi agar tidak ditolak server (403 Forbidden)
            String cookies = CookieManager.getInstance().getCookie(urlTarget);
            if (cookies != null && !cookies.isEmpty()) {
                request.addRequestHeader("Cookie", cookies);
            }
            String ua = hiddenWebView.getSettings().getUserAgentString();
            request.addRequestHeader("User-Agent", ua);
            request.addRequestHeader("Referer", IDX_URL);
            request.addRequestHeader("Accept", "application/pdf,*/*");

            request.setTitle(safeName);
            request.setDescription("Mengunduh dokumen pengumuman emiten IDX...");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName);

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
                Toast.makeText(this, "Mulai mengunduh: " + safeName, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            // Alternatif otomatis bila DownloadManager terganggu: buka browser langsung
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(urlTarget));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception err) {
                Toast.makeText(this, "Gagal mengunduh: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Otomatis segarkan saat aplikasi dibuka agar layar tidak kosong
        if (allItems.isEmpty()) {
            refreshData();
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