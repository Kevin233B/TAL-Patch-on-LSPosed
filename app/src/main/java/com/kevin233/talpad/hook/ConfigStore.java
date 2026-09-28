package com.kevin233.talpad.hook;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;
public final class ConfigStore implements XposedServiceHelper.OnServiceListener {
    private static final String TAG = "TAL-Patch-Config";
    private static final String DIAG_FILE = "xposed_service_status.txt";
    private static volatile Context sAppContext;
    private static volatile XposedService sService;
    private static final Map<String, Object> sPending = new ConcurrentHashMap<>();
    private ConfigStore() {
    }
    public static void init(Context context) {
        if (sAppContext == null) {
            sAppContext = context.getApplicationContext();
        }
        XposedServiceHelper.registerListener(new ConfigStore());
        appendDiag("app started");
    }
    public static boolean getBoolean(String key, boolean defaultValue) {
        SharedPreferences local = localPrefs();
        if (local != null && local.contains(key)) {
            return local.getBoolean(key, defaultValue);
        }
        SharedPreferences remote = remotePrefs();
        if (remote != null && remote.contains(key)) {
            return remote.getBoolean(key, defaultValue);
        }
        return defaultValue;
    }
    public static void setBoolean(String key, boolean value) {
        setValue(key, value);
    }
    public static String getString(String key, String defaultValue) {
        SharedPreferences local = localPrefs();
        if (local != null && local.contains(key)) {
            return local.getString(key, defaultValue);
        }
        SharedPreferences remote = remotePrefs();
        if (remote != null && remote.contains(key)) {
            return remote.getString(key, defaultValue);
        }
        return defaultValue;
    }
    public static void setString(String key, String value) {
        setValue(key, value);
    }
    private static void setValue(String key, Object value) {
        SharedPreferences local = localPrefs();
        if (local != null) {
            SharedPreferences.Editor editor = local.edit();
            if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
            } else {
                editor.putString(key, (String) value);
            }
            editor.commit();
        }
        SharedPreferences remote = remotePrefs();
        if (remote != null) {
            try {
                SharedPreferences.Editor editor = remote.edit();
                if (value instanceof Boolean) {
                    editor.putBoolean(key, (Boolean) value);
                } else {
                    editor.putString(key, (String) value);
                }
                if (editor.commit()) {
                    sPending.remove(key);
                    writeRemoteFileSnapshot();
                    appendDiag("saved remote: " + key + "=" + value);
                    return;
                }
                Log.w(TAG, "remote commit returned false, queued: " + key);
            } catch (Throwable t) {
                Log.w(TAG, "remote save failed, queued: " + key, t);
            }
        }
        sPending.put(key, value);
        appendDiag("saved local + queued: " + key + "=" + value);
    }
    private static SharedPreferences remotePrefs() {
        XposedService service = sService;
        if (service == null) {
            return null;
        }
        try {
            return service.getRemotePreferences(Config.PREFS);
        } catch (Throwable t) {
            Log.w(TAG, "get remote prefs failed", t);
            return null;
        }
    }
    private static SharedPreferences localPrefs() {
        Context context = sAppContext;
        if (context == null) {
            return null;
        }
        return context.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE);
    }
    @Override
    public void onServiceBind(XposedService service) {
        sService = service;
        try {
            SharedPreferences remote = service.getRemotePreferences(Config.PREFS);
            SharedPreferences.Editor editor = remote.edit();
            boolean dirty = false;
            for (Map.Entry<String, Object> entry : sPending.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Boolean) {
                    editor.putBoolean(entry.getKey(), (Boolean) value);
                } else {
                    editor.putString(entry.getKey(), (String) value);
                }
                dirty = true;
            }
            SharedPreferences local = localPrefs();
            if (local != null) {
                for (String key : new String[]{
                        Config.KEY_RESTORE_NOTIFICATION,
                        Config.KEY_UNLOCK_INSTALL,
                        Config.KEY_BLOCK_UC_ENV_DETECT,
                        Config.KEY_BLOCK_DEFAULT_WALLPAPER,
                        Config.KEY_BLOCK_DEFAULT_LAUNCHER,
                        Config.KEY_CUSTOM_USAGE_ENABLED,
                        Config.KEY_STUDY_ONCE_PER_DAY,
                        Config.KEY_CUSTOM_STUDY_MINUTES,
                        Config.KEY_CUSTOM_APP_USAGE,
                        Config.KEY_BLOCK_ROOT_LOGS,
                        Config.KEY_BLOCK_STUDY_REPORT,
                        Config.KEY_BLOCK_MINOR_CONTROL,
                        Config.KEY_UI_COLOR_MODE,
                        Config.KEY_UI_CUSTOM_COLOR}) {
                    if (local.contains(key) && !remote.contains(key)) {
                        Object value = local.getAll().get(key);
                        if (value instanceof Boolean) {
                            editor.putBoolean(key, (Boolean) value);
                        } else if (value instanceof String) {
                            editor.putString(key, (String) value);
                        } else if (value instanceof Integer) {
                            editor.putInt(key, (Integer) value);
                        } else if (value instanceof Long) {
                            editor.putLong(key, (Long) value);
                        } else if (value instanceof Float) {
                            editor.putFloat(key, (Float) value);
                        }
                        dirty = true;
                    }
                }
            }
            if (dirty) {
                editor.commit();
                sPending.clear();
                appendDiag("flushed pending + local to framework");
            }
            writeRemoteFileSnapshot();
            appendDiag("xposed service connected, api=" + service.getApiVersion());
        } catch (Throwable t) {
            Log.w(TAG, "service bind flush failed", t);
            appendDiag("service bind error: " + t);
        }
    }
    @Override
    public void onServiceDied(XposedService service) {
        if (sService == service) {
            sService = null;
        }
        appendDiag("xposed service died");
    }
    public static void appendDiag(String message) {
        Context context = sAppContext;
        SdcardLog.append("ConfigStore", message);
        if (context == null) {
            return;
        }
        try {
            File file = new File(context.getFilesDir(), DIAG_FILE);
            FileOutputStream out = new FileOutputStream(file, true);
            OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            writer.write(System.currentTimeMillis() + " " + message + "\n");
            writer.flush();
            writer.close();
        } catch (Throwable ignored) {
        }
    }
    private static void writeRemoteFileSnapshot() {
        XposedService service = sService;
        Context context = sAppContext;
        if (service == null || context == null) {
            return;
        }
        ParcelFileDescriptor pfd = null;
        try {
            JSONObject json = new JSONObject();
            SharedPreferences local =
                    context.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> entry : local.getAll().entrySet()) {
                Object value = entry.getValue();
                json.put(entry.getKey(),
                        value instanceof Boolean ? value : String.valueOf(value));
            }
            pfd = service.openRemoteFile(Config.REMOTE_FILE);
            FileOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pfd);
            pfd = null;
            byte[] content = json.toString().getBytes(StandardCharsets.UTF_8);
            byte[] padded = new byte[4096];
            int len = Math.min(content.length, padded.length);
            System.arraycopy(content, 0, padded, 0, len);
            for (int i = len; i < padded.length; i++) {
                padded[i] = ' ';
            }
            out.write(padded);
            out.flush();
            out.close();
        } catch (Throwable t) {
            Log.w(TAG, "write remote file snapshot failed", t);
            appendDiag("write remote file snapshot failed: " + t);
        } finally {
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
