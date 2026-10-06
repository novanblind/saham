package com.sahamglobal;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final String IDX_URL = "https://www.idx.co.id/id/perusahaan-tercatat/keterbukaan-informasi";

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
    private boolean isInitialLoaded = false;

    // Peta pencarian nama bank populer ke kode saham Bursa Efek Indonesia
    private static final Map<String, String> BANK_ALIAS = new HashMap<>();
    static {
        BANK_ALIAS.put("bca", "BBCA");
        BANK_ALIAS.put("bri", "BBRI");
        BANK_ALIAS.put("mandiri", "BMRI");
        BANK_ALIAS.put("bni", "BBNI");
        BANK_ALIAS.put("btn", "BBTN");
        BANK_ALIAS.put("bsi", "BRIS");
        BANK_ALIAS.put("permata", "BNLI");
        BANK_ALIAS.put("danamon", "BDMN");
        BANK_ALIAS.put("panin", "PNBN");
        BANK_ALIAS.put("cimb", "BNGA");
        BANK_ALIAS.put("mega", "MEGA");
        BANK_ALIAS.put("jago", "ARTO");
        BANK_ALIAS.put("allo", "BBHI");
        BANK_ALIAS.put("bjb", "BJBR");
        BANK_ALIAS.put("jatim", "BJTM");
    }

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

        // Tata letak antarmuka native murni (Sangat cepat dan ramah pembaca layar)
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 16, 16, 16);

        // Baris status
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

        // Baris tombol kontrol atas
        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setPadding(0, 8, 0, 12);

        btnRefresh = new Button(this);
        btnRefresh.setText("SEGARKAN");
        btnRefresh.setContentDescription("Tombol, Segarkan pengumuman saham");
        btnRefresh.setOnClickListener(v -> refreshData());

        btnSearch = new Button(this);
        btnSearch.setText("CARI KODE / BANK");
        btnSearch.setContentDescription("Tombol, Cari kode saham atau nama bank");
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

        // Tampilan daftar pengumuman saham (Native ListView)
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
                // Jalankan ekstraksi sekali saja tanpa perulangan interval
                injeksiEkstraktorPengumumanSatuKali(view);
            }
        });
    }

    /**
     * Skrip ekstraksi: Dijalankan 1 KALI SAJA tanpa setInterval berulang-ulang
     * sehingga pembaca layar tenang dan tidak berulang kali bicara.
     */
    private void injeksiEkstraktorPengumumanSatuKali(WebView view) {
        String js = "javascript:(function() {" +
                "  function ambilDaftarSekali() {" +
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
                "  /* Buka dropdown dan pilih Saham 1 kali dengan jeda halus */" +
                "  setTimeout(function() {" +
                "    var elemen = document.querySelectorAll('button, div, span, input, a');" +
                "    for (var k = 0; k < elemen.length; k++) {" +
                "      var txt = (elemen[k].innerText || elemen[k].value || '').trim();" +
                "      if (txt === 'Jenis - Semua') {" +
                "        var btn = elemen[k].closest('button, [role=\"button\"], .multiselect, .v-select') || elemen[k];" +
                "        try { btn.click(); } catch(e){}" +
                "        break;" +
                "      }" +
                "    }" +
                "    setTimeout(function() {" +
                "      var opsi = document.querySelectorAll('li, div[role=\"option\"], a, span, button');" +
                "      for (var m = 0; m < opsi.length; m++) {" +
                "        var namaOpsi = (opsi[m].innerText || opsi[m].textContent || '').trim();" +
                "        if (namaOpsi === 'Saham') {" +
                "          try {" +
                "            opsi[m].click();" +
                "            opsi[m].dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));" +
                "          } catch(e){}" +
                "          break;" +
                "        }" +
                "      }" +
                "      ambilDaftarSekali();" +
                "    }, 400);" +
                "  }, 600);" +
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

            Toast.makeText(this, "Berhasil memuat " + allItems.size() + " pengumuman saham.", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }

    private void applyFilter() {
        displayItems.clear();
        listTitles.clear();

        String q = filterQuery.trim().toLowerCase(Locale.ROOT);
        String aliasCode = BANK_ALIAS.get(q);

        for (AnnouncementItem item : allItems) {
            boolean matches = false;
            if (q.isEmpty()) {
                matches = true;
            } else {
                String titleLower = item.title.toLowerCase(Locale.ROOT);
                String codeUpper = item.code.toUpperCase(Locale.ROOT);

                if (titleLower.contains(q) || item.code.toLowerCase(Locale.ROOT).contains(q)) {
                    matches = true;
                } else if (aliasCode != null && codeUpper.equals(aliasCode)) {
                    matches = true;
                }
            }

            if (matches) {
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
            status += " [Filter: " + filterQuery + (aliasCode != null ? " / " + aliasCode : "") + "]";
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
        itemLabels[1] = filterQuery.isEmpty() ? "Cari kode saham / bank" : "Hapus filter saat ini (" + filterQuery + ")";

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

        b.setPositiveButton("buka / unduh PDF", (d, w) -> {
            bukaPdfLangsung(item.url);
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

    private void bukaPdfLangsung(String urlTarget) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(urlTarget));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Tidak ada aplikasi browser untuk membuka tautan.", Toast.LENGTH_LONG).show();
        }
    }

    private void showSearchDialog() {
        EditText input = new EditText(this);
        input.setHint("Ketik: bca, bri, mandiri, bni, ptpp, dll.");
        input.setSingleLine(true);
        if (!filterQuery.isEmpty()) {
            input.setText(filterQuery);
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Cari Saham / Bank");
        b.setMessage("Ketik nama bank (bca, bri, mandiri, bni) atau kode saham (BBCA, PTPP):");
        b.setView(input);

        b.setPositiveButton("terapkan", (d, w) -> {
            filterQuery = input.getText().toString().trim();
            applyFilter();
            if (displayItems.isEmpty()) {
                Toast.makeText(this, "Tidak ada pengumuman untuk '" + filterQuery + "' di daftar terbaru saat ini.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Ditemukan " + displayItems.size() + " pengumuman.", Toast.LENGTH_SHORT).show();
            }
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

    @Override
    protected void onResume() {
        super.onResume();
        if (!isInitialLoaded) {
            isInitialLoaded = true;
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