package com.kevin233.talpad.hook;
import android.content.Context;
import android.os.Environment;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
public final class SdcardLog {
    private static final String DIR = "TAL-Patch-Log";
    private static final String FILE = "tal_patch.log";
    private static volatile Context sContext;
    private SdcardLog() {
    }
    public static void init(Context context) {
        if (context != null) {
            sContext = context.getApplicationContext();
        }
    }
    private static Context context() {
        Context c = sContext;
        if (c != null) {
            return c;
        }
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                sContext = (Context) app;
                return sContext;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
    public static void append(String tag, String message) {
        try {
            String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date()) + " [" + tag + "] " + message + "\n";
            try {
                File dir = new File(Environment.getExternalStorageDirectory(), DIR);
                if (dir.isDirectory() || dir.mkdirs()) {
                    write(new File(dir, FILE), line);
                }
            } catch (Throwable ignored) {
            }
            Context c = context();
            if (c != null) {
                File dir = c.getExternalFilesDir(null);
                if (dir != null) {
                    write(new File(dir, FILE), line);
                }
            }
        } catch (Throwable ignored) {
        }
    }
    private static void write(File file, String line) {
        try {
            FileOutputStream out = new FileOutputStream(file, true);
            OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            writer.write(line);
            writer.flush();
            writer.close();
        } catch (Throwable ignored) {
        }
    }
    public static void clearSdcardLogs() {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), DIR);
            if (dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        file.delete();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }
    public static void clearAndCollect(Context context) {
        if (context == null) {
            return;
        }
        try {
            clearSdcardLogs();
            File targetDir = new File(Environment.getExternalStorageDirectory(), DIR);
            if (!targetDir.isDirectory() && !targetDir.mkdirs()) {
                return;
            }
            File target = new File(targetDir, FILE);
            String[] packages = {
                    "com.kevin233.talpad.hook",
                    "com.tal.pad.studyservice",
                    "com.tal.pad.onlineclass",
                    "com.tal.dataupload",
                    "com.tal.pad.backdoor",
                    "com.tal.backdoor"
            };
            for (String pkg : packages) {
                File source = new File(
                        Environment.getExternalStorageDirectory(),
                        "Android/data/" + pkg + "/files/" + FILE);
                if (source.exists()) {
                    write(target, "\n===== " + pkg + " =====\n");
                    appendFile(target, source);
                }
            }
        } catch (Throwable ignored) {
        }
    }
    private static void appendFile(File target, File source) {
        try {
            FileInputStream in = new FileInputStream(source);
            FileOutputStream out = new FileOutputStream(target, true);
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            out.flush();
            out.close();
            in.close();
        } catch (Throwable ignored) {
        }
    }
}
