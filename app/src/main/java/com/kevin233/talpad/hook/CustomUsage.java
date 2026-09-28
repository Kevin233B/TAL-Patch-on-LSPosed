package com.kevin233.talpad.hook;
import android.content.SharedPreferences;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;
public final class CustomUsage {
    public static final String KEY_TODAY_USE_TIME = "today_use_time_duration_key";
    public static final long MAX_DAILY_MS = 24L * 60L * 60L * 1000L;
    public static final long MAX_DAILY_MINUTES = 24L * 60L;
    private static final long CACHE_TTL_MS = 5000L;
    public interface ConfigSupplier {
        JSONObject get();
    }
    private static volatile ConfigSupplier sSupplier;
    private static volatile JSONObject sCache;
    private static volatile long sCacheTime;
    private CustomUsage() {
    }
    public static void setSupplier(ConfigSupplier supplier) {
        sSupplier = supplier;
    }
    public static JSONObject freshConfig() {
        long now = SystemClock.elapsedRealtime();
        JSONObject cached = sCache;
        if (cached != null && now - sCacheTime < CACHE_TTL_MS) {
            return cached;
        }
        JSONObject json = null;
        ConfigSupplier supplier = sSupplier;
        if (supplier != null) {
            try {
                json = supplier.get();
            } catch (Throwable ignored) {
            }
        }
        sCache = json;
        sCacheTime = now;
        return json;
    }
    public static boolean isEnabled(SharedPreferences prefs) {
        if (prefs != null && prefs.contains(Config.KEY_CUSTOM_USAGE_ENABLED)) {
            return prefs.getBoolean(
                    Config.KEY_CUSTOM_USAGE_ENABLED,
                    Config.DEFAULT_CUSTOM_USAGE_ENABLED);
        }
        JSONObject json = freshConfig();
        if (json != null && json.has(Config.KEY_CUSTOM_USAGE_ENABLED)) {
            return json.optBoolean(
                    Config.KEY_CUSTOM_USAGE_ENABLED,
                    Config.DEFAULT_CUSTOM_USAGE_ENABLED);
        }
        return Config.DEFAULT_CUSTOM_USAGE_ENABLED;
    }
    public static boolean blockRootLogs(SharedPreferences prefs) {
        if (prefs != null && prefs.contains(Config.KEY_BLOCK_ROOT_LOGS)) {
            return prefs.getBoolean(
                    Config.KEY_BLOCK_ROOT_LOGS,
                    Config.DEFAULT_BLOCK_ROOT_LOGS);
        }
        JSONObject json = freshConfig();
        if (json != null && json.has(Config.KEY_BLOCK_ROOT_LOGS)) {
            return json.optBoolean(
                    Config.KEY_BLOCK_ROOT_LOGS,
                    Config.DEFAULT_BLOCK_ROOT_LOGS);
        }
        return Config.DEFAULT_BLOCK_ROOT_LOGS;
    }
    public static boolean blockUserCenterDetect(SharedPreferences prefs) {
        if (prefs != null && prefs.contains(Config.KEY_BLOCK_UC_ENV_DETECT)) {
            return prefs.getBoolean(
                    Config.KEY_BLOCK_UC_ENV_DETECT,
                    Config.DEFAULT_BLOCK_UC_ENV_DETECT);
        }
        return Config.DEFAULT_BLOCK_UC_ENV_DETECT;
    }
    public static boolean blockStudyReport(SharedPreferences prefs) {
        if (prefs != null && prefs.contains(Config.KEY_BLOCK_STUDY_REPORT)) {
            return prefs.getBoolean(
                    Config.KEY_BLOCK_STUDY_REPORT,
                    Config.DEFAULT_BLOCK_STUDY_REPORT);
        }
        JSONObject json = freshConfig();
        if (json != null && json.has(Config.KEY_BLOCK_STUDY_REPORT)) {
            return json.optBoolean(
                    Config.KEY_BLOCK_STUDY_REPORT,
                    Config.DEFAULT_BLOCK_STUDY_REPORT);
        }
        return Config.DEFAULT_BLOCK_STUDY_REPORT;
    }
    public static boolean blockMinorControl(SharedPreferences prefs) {
        if (prefs != null && prefs.contains(Config.KEY_BLOCK_MINOR_CONTROL)) {
            return prefs.getBoolean(
                    Config.KEY_BLOCK_MINOR_CONTROL,
                    Config.DEFAULT_BLOCK_MINOR_CONTROL);
        }
        JSONObject json = freshConfig();
        if (json != null && json.has(Config.KEY_BLOCK_MINOR_CONTROL)) {
            return json.optBoolean(
                    Config.KEY_BLOCK_MINOR_CONTROL,
                    Config.DEFAULT_BLOCK_MINOR_CONTROL);
        }
        return Config.DEFAULT_BLOCK_MINOR_CONTROL;
    }
    public static long studyMillis(SharedPreferences prefs) {
        long minutes = 120;
        if (prefs != null && prefs.contains(Config.KEY_CUSTOM_STUDY_MINUTES)) {
            try {
                String raw = prefs.getString(
                        Config.KEY_CUSTOM_STUDY_MINUTES,
                        Config.DEFAULT_CUSTOM_STUDY_MINUTES);
                minutes = Long.parseLong(raw == null ? "" : raw.trim());
            } catch (Throwable ignored) {
            }
        } else {
            JSONObject json = freshConfig();
            if (json != null && json.has(Config.KEY_CUSTOM_STUDY_MINUTES)) {
                minutes = json.optLong(
                        Config.KEY_CUSTOM_STUDY_MINUTES,
                        Long.parseLong(Config.DEFAULT_CUSTOM_STUDY_MINUTES));
            }
        }
        if (minutes < 0) {
            minutes = 0;
        }
        if (minutes > MAX_DAILY_MINUTES) {
            minutes = MAX_DAILY_MINUTES;
        }
        return minutes * 60000L;
    }
    public static Long appUsageMillis(SharedPreferences prefs, String pkg) {
        if (pkg == null) {
            return null;
        }
        Map<String, Long> entries = appUsageEntries(prefs);
        return entries.get(pkg);
    }
    public static Map<String, Long> appUsageEntries(SharedPreferences prefs) {
        Map<String, Long> result = new LinkedHashMap<>();
        String raw = null;
        if (prefs != null && prefs.contains(Config.KEY_CUSTOM_APP_USAGE)) {
            raw = prefs.getString(Config.KEY_CUSTOM_APP_USAGE, "");
        } else {
            JSONObject json = freshConfig();
            if (json != null && json.has(Config.KEY_CUSTOM_APP_USAGE)) {
                raw = json.optString(Config.KEY_CUSTOM_APP_USAGE, "");
            }
        }
        if (raw == null) {
            return result;
        }
        for (String line : raw.split("\\n")) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String name;
            String number;
            int colon = line.indexOf(':');
            int fullColon = line.indexOf('：');
            if (colon >= 0 && (fullColon < 0 || colon < fullColon)) {
                name = line.substring(0, colon).trim();
                number = line.substring(colon + 1).trim();
            } else if (fullColon >= 0) {
                name = line.substring(0, fullColon).trim();
                number = line.substring(fullColon + 1).trim();
            } else {
                String[] parts = line.split("\\s+");
                if (parts.length < 2) {
                    continue;
                }
                name = parts[0].trim();
                number = parts[1].trim();
            }
            if (name.isEmpty()) {
                continue;
            }
            try {
                long minutes = Long.parseLong(number);
                if (minutes < 0) {
                    minutes = 0;
                }
                if (minutes > MAX_DAILY_MINUTES) {
                    minutes = MAX_DAILY_MINUTES;
                }
                result.put(name, minutes * 60000L);
            } catch (Throwable ignored) {
            }
        }
        return result;
    }
}
