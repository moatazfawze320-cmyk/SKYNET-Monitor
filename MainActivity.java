package com.skynet.monitor;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import android.text.InputType;
import android.content.Context;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private final String PREFS = "skynet_monitor";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(12);
    private EditText addresses;
    private LinearLayout results;
    private TextView summary, phoneInternet;
    private boolean scanning = false;

    private final Runnable autoScan = new Runnable() {
        @Override public void run() {
            scanAll();
            handler.postDelayed(this, 30000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        addresses.setText(getPreferences(0).getString("ips", ""));
        renderEmpty();
        handler.postDelayed(autoScan, 30000);
    }

    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density); }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setPadding(dp(8), dp(6), dp(8), dp(6));
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this); b.setText(label); return b;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        root.setBackgroundColor(Color.rgb(246,248,250));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = text("SKYNET MONITOR", 24, Color.rgb(20,75,125));
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, 1);
        root.addView(title);
        TextView subtitle = text("مراقبة الراوترات من هاتفك", 15, Color.DKGRAY);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle);

        summary = text("عدد الراوترات: 0 | يستجيب: -- | لا يستجيب: --", 16, Color.BLACK);
        summary.setGravity(Gravity.CENTER);
        root.addView(summary);

        phoneInternet = text("إنترنت الهاتف: لم يتم الفحص بعد", 14, Color.DKGRAY);
        phoneInternet.setGravity(Gravity.CENTER);
        root.addView(phoneInternet);

        root.addView(text("أدخل عناوين IP (عنوان واحد في كل سطر، حتى 50):", 15, Color.BLACK));
        addresses = new EditText(this);
        addresses.setHint("10.13.2.115\n10.13.2.116\n10.13.3.115");
        addresses.setGravity(Gravity.TOP | Gravity.START);
        addresses.setMinLines(5);
        addresses.setMaxLines(12);
        addresses.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        addresses.setTextSize(15);
        addresses.setPadding(dp(12), dp(10), dp(12), dp(10));
        addresses.setBackgroundColor(Color.WHITE);
        root.addView(addresses, new LinearLayout.LayoutParams(-1, dp(180)));

        Button save = button("حفظ عناوين IP");
        root.addView(save);
        save.setOnClickListener(v -> saveIps());

        Button scan = button("فحص جميع الراوترات الآن");
        root.addView(scan);
        scan.setOnClickListener(v -> scanAll());

        root.addView(text("نتائج الفحص", 18, Color.BLACK));
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        root.addView(results);

        TextView note = text(
            "ملاحظة: حالة الراوتر تعتمد على اختبار الوصول للشبكة (Reachability). " +
            "فحص الإنترنت المعروض هنا يخص اتصال الهاتف فقط، ولا يثبت أن الإنترنت يعمل عبر كل راوتر بشكل مستقل. " +
            "قد تمنع بعض الأجهزة رد Ping رغم أنها تعمل.",
            13, Color.DKGRAY);
        note.setPadding(dp(8), dp(16), dp(8), dp(8));
        root.addView(note);
    }

    private void saveIps() {
        List<String> list = getIps();
        if (list.size() > 50) {
            toast("الحد الأقصى 50 عنوان IP");
            return;
        }
        StringBuilder clean = new StringBuilder();
        for (String ip : list) clean.append(ip).append("\n");
        addresses.setText(clean.toString().trim());
        getPreferences(0).edit().putString("ips", clean.toString().trim()).apply();
        toast("تم حفظ " + list.size() + " عنوان IP");
        renderEmpty();
    }

    private List<String> getIps() {
        String raw = addresses.getText().toString();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String line : raw.split("[\\r\\n,;]+")) {
            String ip = line.trim();
            if (!ip.isEmpty() && validIpv4(ip)) unique.add(ip);
        }
        return new ArrayList<>(unique);
    }

    private boolean validIpv4(String ip) {
        String[] p = ip.split("\\.", -1);
        if (p.length != 4) return false;
        for (String s : p) {
            try {
                if (s.isEmpty() || s.length() > 3) return false;
                int n = Integer.parseInt(s);
                if (n < 0 || n > 255) return false;
            } catch (Exception e) { return false; }
        }
        return true;
    }

    private void renderEmpty() {
        results.removeAllViews();
        List<String> ips = getIps();
        summary.setText("عدد الراوترات: " + ips.size() + " | يستجيب: -- | لا يستجيب: --");
        for (String ip : ips) addResult(ip, "بانتظار الفحص", Color.GRAY, "--");
    }

    private void addResult(String ip, String state, int color, String latency) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(7), dp(8), dp(7));
        row.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.setMargins(0, dp(3), 0, dp(3));
        results.addView(row, rp);

        TextView address = text(ip, 15, Color.BLACK);
        address.setTypeface(null, 1);
        row.addView(address, new LinearLayout.LayoutParams(0, -2, 1));
        TextView ms = text(latency, 12, Color.DKGRAY);
        row.addView(ms);
        TextView status = text(state, 13, color);
        status.setTypeface(null, 1);
        row.addView(status);
    }

    private void scanAll() {
        if (scanning) return;
        List<String> ips = getIps();
        if (ips.isEmpty()) { toast("أدخل عناوين IP صحيحة أولاً"); return; }
        if (ips.size() > 50) { toast("الحد الأقصى 50 عنواناً"); return; }
        scanning = true;
        saveIps();
        results.removeAllViews();
        summary.setText("جارٍ فحص " + ips.size() + " راوتر...");
        for (String ip : ips) addResult(ip, "يفحص...", Color.GRAY, "--");
        final int[] done = {0}, alive = {0};
        final int total = ips.size();

        pool.execute(() -> {
            boolean internet = testPhoneInternet();
            runOnUiThread(() -> phoneInternet.setText(
                internet ? "إنترنت الهاتف: يعمل" : "إنترنت الهاتف: لا يستجيب"));
        });

        for (String ip : ips) {
            pool.execute(() -> {
                long start = System.currentTimeMillis();
                boolean ok = false;
                try {
                    InetAddress addr = InetAddress.getByName(ip);
                    ok = addr.isReachable(1800);
                } catch (Exception ignored) {}
                long elapsed = System.currentTimeMillis() - start;
                final boolean reachable = ok;
                runOnUiThread(() -> {
                    done[0]++;
                    if (reachable) alive[0]++;
                    refreshRow(ip, reachable, elapsed);
                    summary.setText("الإجمالي: " + total + " | يستجيب: " + alive[0] +
                        " | لا يستجيب: " + (done[0] - alive[0]) +
                        " | اكتمل: " + done[0] + "/" + total);
                    if (done[0] == total) scanning = false;
                });
            });
        }
    }

    private void refreshRow(String ip, boolean ok, long ms) {
        List<String> current = getIps();
        int index = current.indexOf(ip);
        if (index < 0) return;
        // Rebuild from latest row states by replacing the matching visible row.
        if (index < results.getChildCount()) {
            LinearLayout row = (LinearLayout) results.getChildAt(index);
            TextView latency = (TextView) row.getChildAt(1);
            TextView status = (TextView) row.getChildAt(2);
            latency.setText(ok ? ms + " ms" : "--");
            status.setText(ok ? "يستجيب" : "لا يستجيب");
            status.setTextColor(ok ? Color.rgb(20,145,70) : Color.rgb(190,40,40));
        }
    }

    private boolean testPhoneInternet() {
        HttpURLConnection c = null;
        try {
            URL u = new URL("https://connectivitycheck.gstatic.com/generate_204");
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(3500);
            c.setReadTimeout(3500);
            c.setUseCaches(false);
            return c.getResponseCode() == 204;
        } catch (Exception e) { return false; }
        finally { if (c != null) c.disconnect(); }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override public void onDestroy() {
        handler.removeCallbacks(autoScan);
        pool.shutdownNow();
        super.onDestroy();
    }
}
