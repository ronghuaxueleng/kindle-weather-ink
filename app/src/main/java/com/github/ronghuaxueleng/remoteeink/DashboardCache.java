package com.github.ronghuaxueleng.remoteeink;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Locale;

/** Stores payload and timestamp together so interrupted writes preserve the old cache. */
public final class DashboardCache {
    private static final int MAX_CACHE_BYTES = 2 * 1024 * 1024;
    private final Context context;
    private String lastCityId;
    private long lastSavedAtMillis = -1;

    public DashboardCache(Context context) { this.context = context.getApplicationContext(); }

    public File fileFor(String cityId) {
        return new File(context.getFilesDir(), "dashboard-" + cacheKey(cityId) + ".json");
    }
    private static String cacheKey(String cityId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    (cityId == null ? "default" : cityId).getBytes("UTF-8"));
            StringBuilder result = new StringBuilder();
            for (byte b : hash) result.append(String.format(Locale.US, "%02x", b & 255));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
    public synchronized String load(String cityId) {
        lastCityId = null;
        lastSavedAtMillis = -1;
        try (FileInputStream input = new AtomicFile(fileFor(cityId)).openRead()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_CACHE_BYTES) throw new IOException("Cache too large");
                bytes.write(buffer, 0, count);
            }
            JSONObject envelope = new JSONObject(new String(bytes.toByteArray(), "UTF-8"));
            String payload = envelope.getString("payload");
            DashboardJson.parse(payload);
            lastCityId = cityId;
            lastSavedAtMillis = envelope.optLong("saved_at", -1);
            return payload;
        } catch (Exception e) {
            return null;
        }
    }
    public synchronized boolean save(String cityId, String json) {
        AtomicFile file = new AtomicFile(fileFor(cityId));
        FileOutputStream stream = null;
        try {
            DashboardJson.parse(json);
            long savedAt = System.currentTimeMillis();
            JSONObject envelope = new JSONObject();
            envelope.put("saved_at", savedAt);
            envelope.put("payload", json);
            byte[] bytes = envelope.toString().getBytes("UTF-8");
            if (bytes.length > MAX_CACHE_BYTES) return false;
            stream = file.startWrite();
            stream.write(bytes);
            file.finishWrite(stream);
            lastCityId = cityId;
            lastSavedAtMillis = savedAt;
            return true;
        } catch (Exception e) {
            if (stream != null) file.failWrite(stream);
            return false;
        }
    }
    public synchronized long ageSeconds() {
        return lastSavedAtMillis <= 0 ? -1 : Math.max(0, (System.currentTimeMillis() - lastSavedAtMillis) / 1000);
    }
    public synchronized long savedAtMillis() { return lastSavedAtMillis; }
    public synchronized String lastCityId() { return lastCityId; }
}
