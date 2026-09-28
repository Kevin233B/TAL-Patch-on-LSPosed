package com.kevin233.talpad.hook;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
public final class RootTraceHider {
    private static final Set<String> ROOT_PACKAGES = new HashSet<>(Arrays.asList(
            "com.topjohnwu.magisk",
            "io.github.lsposed.manager",
            "org.lsposed.manager",
            "de.robv.android.xposed.installer",
            "com.koushikdutta.superuser",
            "eu.chainfire.supersu",
            "com.saurik.substrate",
            "com.saurik.substrate.helper",
            "com.kevin233.talpad.hook"));
    private static final Set<String> ROOT_PATHS = new HashSet<>(Arrays.asList(
            "/system/app/Superuser.apk",
            "/system/priv-app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su",
            "/system/bin/busybox",
            "/system/xbin/busybox",
            "/data/adb/magisk",
            "/data/adb/magisk.db",
            "/data/adb/magisk.img",
            "/sbin/.magisk",
            "/sbin/magisk",
            "/system/bin/magisk",
            "/system/xbin/magisk",
            "/data/adb/ksu",
            "/data/adb/kernel-su",
            "/data/adb/modules",
            "/data/adb/post-fs-data.d",
            "/data/adb/service.d",
            "/data/adb/lspd",
            "/data/adb/modules/zygisk_lsposed",
            "/data/adb/modules/zygisk_lsposed/framework.dex",
            "/data/adb/modules/zygisk_lsposed/daemon",
            "/system/etc/init/magisk.rc"));
    private RootTraceHider() {
    }
    public static boolean isRootPath(String path) {
        return path != null && ROOT_PATHS.contains(path);
    }
    public static boolean isRootPackage(String pkg) {
        return pkg != null && ROOT_PACKAGES.contains(pkg);
    }
    public static String sanitizeProp(String key, String value) {
        if (key == null || value == null) {
            return value;
        }
        switch (key) {
            case "ro.debuggable":
                return "0";
            case "ro.secure":
            case "ro.adb.secure":
                return "1";
            case "ro.build.tags":
                return "release-keys";
            case "ro.build.type":
                return "user";
            case "service.adb.root":
            case "persist.sys.root_access":
            case "sys.oem_unlock_allowed":
                return "0";
            case "ro.boot.verifiedbootstate":
                return "green";
            case "ro.build.flavor":
            case "ro.build.description":
            case "ro.build.fingerprint":
                return value.replace("userdebug", "user").replace("-eng", "").replace("eng", "user");
            default:
                return value;
        }
    }
    public static String scrubOutput(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (String line : text.split("\\n")) {
            String lower = line.toLowerCase();
            if (lower.contains("uid=0")
                    || lower.contains("magisk")
                    || lower.contains("lsposed")
                    || lower.contains("zygisk")
                    || lower.contains("tal-patch")
                    || lower.contains("kevin233")
                    || lower.contains("/su")
                    || lower.contains("busybox")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
