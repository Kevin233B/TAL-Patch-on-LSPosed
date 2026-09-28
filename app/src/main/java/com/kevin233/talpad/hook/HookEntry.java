package com.kevin233.talpad.hook;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
public class HookEntry extends XposedModule {
    private static final String TAG = "TAL-Patch";
    private static final String EXTRA_TITLE = "android.title";
    private static final String EXTRA_TEXT = "android.text";
    private static final String EXTRA_PROGRESS = "android.progress";
    private static final String EXTRA_PROGRESS_MAX = "android.progressMax";
    private static final String EXTRA_PROGRESS_INDETERMINATE = "android.progressIndeterminate";
    private static final int VIEW_TEXT = 0x1020515;
    private static final int VIEW_ACTION_ROW = 0x10201ca;
    private static final int VIEW_ACTION_PANEL = 0x10201cb;
    private static final int VIEW_ACTION_MARGIN_VIEW = 0x10203e7;
    private static final int VIEW_ACTION_BUTTON = 0x10201b1;
    private static final java.util.Set<String> TAL_APP_PACKAGES =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "com.tal.pad.studyservice",
                    "com.tal.dataupload",
                    "com.tal.pad.backdoor",
                    "com.tal.backdoor",
                    "com.tal.pad.onlineclass",
                    "com.tal.pad.usercenter",
                    "com.tal.pad.minor_protect",
                    "com.tal.pad.znxxservice"));
    private static final String PACKAGE_INSTALLER_AOSP = "com.android.packageinstaller";
    private static final List<String> ALWAYS_ALLOWED = new AbstractList<String>() {
        @Override
        public String get(int index) {
            throw new IndexOutOfBoundsException("always allowed list is empty");
        }
        @Override
        public int size() {
            return 0;
        }
        @Override
        public boolean contains(Object o) {
            return true;
        }
    };
    private static Method sGetNonContextualActions;
    private static Method sGenerateActionButton;
    private static Method sProcessTextSpans;
    private static Method sSetHeaderlessVerticalMargins;
    private volatile ClassLoader sSystemServerClassLoader;
    private boolean hooksInstalled;
    private boolean talAppHooksInstalled;
    private boolean installHooksInstalled;
    private boolean clientHooksInstalled;
    private static volatile boolean sUsageFabricated;
    private static int requestInstallOpCode = -1;
    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, TAG, "TAL-Patch loaded in " + param.getProcessName());
        if (param.isSystemServer()) {
            return;
        }
        String process = param.getProcessName();
        if (isPackageInstaller(process)) {
            installPackageInstallerHooks(null);
            return;
        }
        installHooks();
    }
    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        sSystemServerClassLoader = param.getClassLoader();
        log(Log.INFO, TAG, "system server starting, classloader ready");
        installHooks();
        installInstallHooks(param.getClassLoader());
        installGenieControlHooks(param.getClassLoader());
    }
    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (isPackageInstaller(pkg)) {
            installPackageInstallerHooks(param.getClassLoader());
            return;
        }
        if (talAppHooksInstalled) {
            return;
        }
        if (!TAL_APP_PACKAGES.contains(pkg)) {
            return;
        }
        talAppHooksInstalled = true;
        try {
            installTalAppHooks(param.getClassLoader(), pkg);
            log(Log.INFO, TAG, "TAL app hooks installed for " + pkg);
            SdcardLog.append("HookEntry",
                    "TAL app hooks installed for " + pkg);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "install TAL app hooks failed", t);
        }
    }
    private boolean isPackageInstaller(String pkg) {
        return PACKAGE_INSTALLER_AOSP.equals(pkg);
    }
    private synchronized void installInstallHooks(ClassLoader systemClassLoader) {
        if (installHooksInstalled) {
            return;
        }
        installHooksInstalled = true;
        SharedPreferences prefs = getRemotePreferences(Config.PREFS);
        if (prefs != null
                && !prefs.getBoolean(
                        Config.KEY_UNLOCK_INSTALL,
                        Config.DEFAULT_UNLOCK_INSTALL)) {
            log(Log.INFO, TAG, "install unlock disabled by config, skip");
            return;
        }
        markInstalled();
        enableWilfullyInstall();
        disableUserVFlag();
        try {
            Class<?> pmService = loadSystemClass(
                    "com.android.server.pm.PackageManagerService", systemClassLoader);
            Class<?> installHelper = loadSystemClass(
                    "com.android.server.pm.InstallPackageHelper", systemClassLoader);
            Class<?> installerService = loadSystemClass(
                    "com.android.server.pm.PackageInstallerService", systemClassLoader);
            Class<?> userManagerService = loadSystemClass(
                    "com.android.server.pm.UserManagerService", systemClassLoader);
            hookMethod(pmService, "getEnableInstallPackageList", chain -> ALWAYS_ALLOWED);
            hookMethod(pmService, "getSelfUpdateWhiteList", chain -> ALWAYS_ALLOWED);
            hookMethod(pmService, "allowWilfullyInstallOnDebugRom", chain -> true);
            hookMethod(pmService, "isUserRestricted", chain -> {
                Object restriction = chain.getArg(1);
                if ("no_install_apps".equals(restriction)) {
                    return false;
                }
                return chain.proceed();
            }, int.class, String.class);
            hookMethodByName(installerService, "createSessionInternal", 4, chain -> {
                disableUserVFlag();
                return chain.proceed();
            });
            hookMethodByName(userManagerService, "getUserRestrictionSource", 2, chain -> {
                Object restriction = chain.getArg(0);
                if ("no_install_apps".equals(restriction)
                        || "no_install_unknown_sources".equals(restriction)
                        || "no_install_unknown_sources_globally".equals(restriction)) {
                    return 0;
                }
                return chain.proceed();
            });
            hookMethodByName(installHelper, "preparePackageLI", 2, chain -> {
                ensureInstallerPackage(chain.getArg(0));
                return chain.proceed();
            });
            log(Log.INFO, TAG, "install restriction hooks installed");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "install hooks failed", t);
        }
    }
    private synchronized void installPackageInstallerHooks(ClassLoader targetLoader) {
        if (clientHooksInstalled) {
            return;
        }
        SharedPreferences prefs = getRemotePreferences(Config.PREFS);
        if (prefs != null
                && !prefs.getBoolean(
                        Config.KEY_UNLOCK_INSTALL,
                        Config.DEFAULT_UNLOCK_INSTALL)) {
            log(Log.INFO, TAG, "install unlock disabled by config, skip client hooks");
            return;
        }
        try {
            Class<?> activity = findClass(
                    "com.android.packageinstaller.PackageInstallerActivity", targetLoader);
            hookMethodByName(activity, "checkCanInstallAppsByDefault", 1, chain -> true);
            hookMethodByName(activity, "isInstallRequestFromUnknownSource", 1, chain -> false);
            hookMethodByName(activity, "checkIfAllowedAndInitiateInstall", 0, chain -> {
                try {
                    Method initiate = findMethodByName(activity, "initiateInstall", 0);
                    invokePrivate(initiate, chain.getThisObject());
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "initiateInstall invocation failed", t);
                }
                return null;
            });
            hookMethodByName(activity, "showDialogInner", 1, chain -> {
                Object arg = chain.getArg(0);
                int dialogId = arg instanceof Integer ? (Integer) arg : -1;
                if (dialogId == 5 || dialogId == 6 || dialogId == 8
                        || dialogId == 9 || dialogId == 10) {
                    try {
                        Method initiate = findMethodByName(activity, "initiateInstall", 0);
                        invokePrivate(initiate, chain.getThisObject());
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "showDialogInner bypass failed", t);
                    }
                    return null;
                }
                return chain.proceed();
            });
            hookMethodByName(activity, "handleUnknownSources", 0, chain -> {
                try {
                    Method initiate = findMethodByName(activity, "initiateInstall", 0);
                    invokePrivate(initiate, chain.getThisObject());
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "handleUnknownSources bypass failed", t);
                }
                return null;
            });
            Class<?> installStart = findClass(
                    "com.android.packageinstaller.InstallStart", targetLoader);
            hookMethodByName(installStart, "isUidRequestingPermission", 2, chain -> true);
            Class<?> appOps = findClass("android.app.AppOpsManager", targetLoader);
            hookMethodByName(appOps, "noteOpNoThrow", 5, chain -> {
                Object op = chain.getArg(0);
                if (op instanceof Integer && (Integer) op == getRequestInstallOp()) {
                    return 0;
                }
                return chain.proceed();
            });
            hookMethodByName(appOps, "noteOpNoThrow", 4, chain -> {
                Object op = chain.getArg(0);
                if (op instanceof Integer && (Integer) op == getRequestInstallOp()) {
                    return 0;
                }
                return chain.proceed();
            });
            Class<?> userManager = findClass("android.os.UserManager", targetLoader);
            hookMethodByName(userManager, "getUserRestrictionSource", 2, chain -> {
                Object restriction = chain.getArg(0);
                if ("no_install_apps".equals(restriction)
                        || "no_install_unknown_sources".equals(restriction)
                        || "no_install_unknown_sources_globally".equals(restriction)) {
                    return 0;
                }
                return chain.proceed();
            });
            clientHooksInstalled = true;
            log(Log.INFO, TAG, "package installer whitelist check removed");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "package installer whitelist hook failed", t);
        }
    }
    private static int getRequestInstallOp() {
        if (requestInstallOpCode != -1) {
            return requestInstallOpCode;
        }
        try {
            Method method = android.app.AppOpsManager.class.getMethod(
                    "permissionToOpCode", String.class);
            requestInstallOpCode = (Integer) method.invoke(
                    null, "android.permission.REQUEST_INSTALL_PACKAGES");
        } catch (Throwable t) {
            requestInstallOpCode = 66;
        }
        return requestInstallOpCode;
    }
    private void ensureInstallerPackage(Object installArgs) {
        try {
            Object source = getField(installArgs, "mInstallSource");
            if (source == null) {
                return;
            }
            Object installer = getField(source, "installerPackageName");
            if (installer instanceof String && !TextUtils.isEmpty((String) installer)) {
                return;
            }
            Object initiating = getField(source, "initiatingPackageName");
            String fallback = initiating instanceof String
                    && !TextUtils.isEmpty((String) initiating)
                    ? (String) initiating
                    : "com.android.shell";
            setField(source, "installerPackageName", fallback);
            log(Log.INFO, TAG, "installerPackageName was null, set to " + fallback);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "ensureInstallerPackage failed", t);
        }
    }
    private void markInstalled() {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Method set = systemProperties.getMethod("set", String.class, String.class);
            set.invoke(null, "sys.tal_patch.install.hook", "1");
            log(Log.INFO, TAG, "hook marker written");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "failed to write hook marker", t);
        }
    }
    private void enableWilfullyInstall() {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Method set = systemProperties.getMethod("set", String.class, String.class);
            set.invoke(null, "persist.sys.debug.allow.wilfully.install", "true");
            log(Log.INFO, TAG, "wilfully install property enabled");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "failed to set wilfully install property", t);
        }
        try {
            Class<?> buildClass = Class.forName("android.os.Build");
            Field field = buildClass.getDeclaredField("IS_USERDEBUG");
            field.setAccessible(true);
            try {
                Field modifiers = Field.class.getDeclaredField("modifiers");
                modifiers.setAccessible(true);
                modifiers.setInt(field, field.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
            } catch (Throwable ignored) {
            }
            field.setBoolean(null, true);
            log(Log.INFO, TAG, "Build.IS_USERDEBUG enabled");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "failed to enable Build.IS_USERDEBUG", t);
        }
    }
    private void disableUserVFlag() {
        try {
            Class<?> buildClass = Class.forName("android.os.Build");
            Field field = buildClass.getDeclaredField("IS_USER_V");
            field.setAccessible(true);
            try {
                Field modifiers = Field.class.getDeclaredField("modifiers");
                modifiers.setAccessible(true);
                modifiers.setInt(field, field.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
            } catch (Throwable ignored) {
            }
            field.setBoolean(null, false);
            log(Log.INFO, TAG, "Build.IS_USER_V disabled");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "failed to disable Build.IS_USER_V", t);
        }
    }
    private static Class<?> loadSystemClass(String name, ClassLoader loader)
            throws ClassNotFoundException {
        if (loader != null) {
            return Class.forName(name, false, loader);
        }
        return Class.forName(name);
    }
    private static Class<?> findClass(String name) throws ClassNotFoundException {
        return findClass(name, null);
    }
    private static Class<?> findClass(String name, ClassLoader preferred)
            throws ClassNotFoundException {
        try {
            if (preferred != null) {
                return Class.forName(name, false, preferred);
            }
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
            if (contextLoader != null) {
                try {
                    return Class.forName(name, false, contextLoader);
                } catch (ClassNotFoundException ignored) {
                }
            }
            try {
                Class<?> activityThread = Class.forName("android.app.ActivityThread");
                Method currentApplication = activityThread.getMethod("currentApplication");
                Object application = currentApplication.invoke(null);
                if (application != null) {
                    return application.getClass().getClassLoader().loadClass(name);
                }
            } catch (Throwable ignored) {
            }
            throw e;
        }
    }
    private synchronized void installHooks() {
        if (hooksInstalled) {
            return;
        }
        hooksInstalled = true;
        CustomUsage.setSupplier(this::readRemoteConfigFile);
        try {
            org.json.JSONObject remote = CustomUsage.freshConfig();
            log(Log.INFO, TAG, "remote config file: "
                    + (remote == null ? "null/missing" : remote.toString()));
        } catch (Throwable t) {
            log(Log.WARN, TAG, "read remote config failed", t);
        }
        SharedPreferences prefs = getRemotePreferences(Config.PREFS);
        boolean restoreNotification = prefs == null
                || prefs.getBoolean(
                Config.KEY_RESTORE_NOTIFICATION,
                Config.DEFAULT_RESTORE_NOTIFICATION);
        boolean blockWallpaper = prefs != null
                && prefs.getBoolean(
                Config.KEY_BLOCK_DEFAULT_WALLPAPER,
                Config.DEFAULT_BLOCK_DEFAULT_WALLPAPER);
        boolean blockDefaultLauncher = prefs != null
                && prefs.getBoolean(
                Config.KEY_BLOCK_DEFAULT_LAUNCHER,
                Config.DEFAULT_BLOCK_DEFAULT_LAUNCHER);
        log(Log.INFO, TAG, "config: restore_notification=" + restoreNotification
                + ", block_default_wallpaper=" + blockWallpaper
                + ", block_default_launcher=" + blockDefaultLauncher);
        log(Log.INFO, TAG, "config: custom_usage_enabled="
                + (prefs != null && prefs.getBoolean(
                Config.KEY_CUSTOM_USAGE_ENABLED,
                Config.DEFAULT_CUSTOM_USAGE_ENABLED))
                + ", custom_study_minutes="
                + (prefs == null ? Config.DEFAULT_CUSTOM_STUDY_MINUTES
                : prefs.getString(Config.KEY_CUSTOM_STUDY_MINUTES,
                Config.DEFAULT_CUSTOM_STUDY_MINUTES))
                + ", custom_app_usage="
                + (prefs == null ? Config.DEFAULT_CUSTOM_APP_USAGE
                : prefs.getString(Config.KEY_CUSTOM_APP_USAGE,
                Config.DEFAULT_CUSTOM_APP_USAGE))
                + ", block_root_logs="
                + (prefs != null && prefs.getBoolean(
                Config.KEY_BLOCK_ROOT_LOGS,
                Config.DEFAULT_BLOCK_ROOT_LOGS)));
        SdcardLog.append("HookEntry", "config loaded in process");
        if (restoreNotification) {
            try {
                Class<?> builderClass = Class.forName("android.app.Notification$Builder");
                hookMethod(builderClass, "setContentTitle", chain -> {
                    Object result = chain.proceed();
                    try {
                        Bundle extras = getExtras(chain.getThisObject());
                        if (extras != null) {
                            CharSequence title = (CharSequence) chain.getArg(0);
                            extras.putCharSequence(EXTRA_TITLE, safeCharSequence(title));
                        }
                    } catch (Throwable t) {
                        log(Log.WARN, TAG, "restore title failed", t);
                    }
                    return result;
                }, CharSequence.class);
                hookMethod(builderClass, "setContentText", chain -> {
                    Object result = chain.proceed();
                    try {
                        Bundle extras = getExtras(chain.getThisObject());
                        if (extras != null) {
                            CharSequence text = (CharSequence) chain.getArg(0);
                            extras.putCharSequence(EXTRA_TEXT, safeCharSequence(text));
                        }
                    } catch (Throwable t) {
                        log(Log.WARN, TAG, "restore text failed", t);
                    }
                    return result;
                }, CharSequence.class);
                hookMethod(builderClass, "setProgress", chain -> {
                    Object result = chain.proceed();
                    try {
                        Bundle extras = getExtras(chain.getThisObject());
                        if (extras != null) {
                            int max = (Integer) chain.getArg(0);
                            int progress = (Integer) chain.getArg(1);
                            boolean indeterminate = (Boolean) chain.getArg(2);
                            extras.putInt(EXTRA_PROGRESS, progress);
                            extras.putInt(EXTRA_PROGRESS_MAX, max);
                            extras.putBoolean(EXTRA_PROGRESS_INDETERMINATE, indeterminate);
                        }
                    } catch (Throwable t) {
                        log(Log.WARN, TAG, "restore progress failed", t);
                    }
                    return result;
                }, int.class, int.class, boolean.class);
                hookMethodByName(builderClass, "applyStandardTemplate", 3, chain -> {
                    Object params = null;
                    try {
                        params = chain.getArg(1);
                    } catch (Throwable ignored) {
                    }
                    CharSequence originalTitle = params == null ? null : getCharSequenceField(params, "title");
                    Object result = chain.proceed();
                    try {
                        if (result instanceof RemoteViews) {
                            if (params != null) {
                                setField(params, "title", originalTitle);
                            }
                            RemoteViews contentView = (RemoteViews) result;
                            contentView.setViewVisibility(VIEW_TEXT, View.VISIBLE);
                            CharSequence text = getCharSequenceField(params, "text");
                            if (text != null && text.length() != 0) {
                                contentView.setTextViewText(
                                        VIEW_TEXT,
                                        processTextSpans(chain.getThisObject(), text));
                                setHeaderlessVerticalMargins(
                                        chain.getThisObject(), contentView, params, true);
                            }
                        }
                    } catch (Throwable t) {
                        log(Log.WARN, TAG, "restore text visibility failed", t);
                    }
                    return result;
                });
                hookMethodByName(builderClass, "applyStandardTemplateWithActions", 3, chain -> {
                    Object result = chain.proceed();
                    try {
                        Object builder = chain.getThisObject();
                        Object params = chain.getArg(1);
                        if (result instanceof RemoteViews) {
                            restoreActions(builder, params, (RemoteViews) result);
                        }
                    } catch (Throwable t) {
                        log(Log.WARN, TAG, "restore actions failed", t);
                    }
                    return result;
                });
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "install notification hooks failed", t);
            }
        }
        if (blockWallpaper) {
            try {
                installWallpaperHooks();
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "install wallpaper hooks failed", t);
            }
        }
        if (blockDefaultLauncher) {
            try {
                installLauncherHooks();
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "install launcher hooks failed", t);
            }
        }
    }
    private void installLauncherHooks() throws Throwable {
        try {
            Class<?> rootWindowContainer = loadSystemServerClass(
                    "com.android.server.wm.RootWindowContainer");
            hookMethodByName(rootWindowContainer, "setDefaultLauncher", 1,
                    chain -> null);
        } catch (ClassNotFoundException e) {
            if (sSystemServerClassLoader != null) {
                log(Log.WARN, TAG, "launcher hooks: RootWindowContainer not found in system server");
            }
        }
        try {
            Class<?> resolverActivity = loadClass(
                    "com.android.internal.app.ResolverActivity");
            hookMethodByName(resolverActivity, "setDefaultLauncher", 1,
                    chain -> null);
            hookMethodByName(resolverActivity, "maybeAutolaunchHomeActivity", 0,
                    chain -> Boolean.FALSE);
        } catch (ClassNotFoundException e) {
            if (sSystemServerClassLoader != null) {
                log(Log.WARN, TAG, "launcher hooks: ResolverActivity not found in system server");
            }
        }
    }
    private void installWallpaperHooks() throws Throwable {
        Class<?> wallpaperService;
        Class<?> wallpaperReceiver;
        try {
            wallpaperService = loadSystemServerClass(
                    "com.android.server.wallpaper.WallpaperManagerService");
            wallpaperReceiver = loadSystemServerClass(
                    "com.android.server.WallpaperUpdateReceiver");
        } catch (ClassNotFoundException e) {
            if (sSystemServerClassLoader != null) {
                log(Log.WARN, TAG, "wallpaper hooks: system server classes not found");
            }
            return;
        }
        hookMethodByName(wallpaperService, "isSetWallpaperEnableOnTclTheme", 0,
                chain -> Boolean.TRUE);
        hookMethodByName(wallpaperReceiver, "updateWallpaper", 0,
                chain -> null);
        hookMethodByName(wallpaperService, "errorCheck", 1,
                chain -> null);
    }
    private void installTalAppHooks(ClassLoader loader, String pkg) throws Throwable {
        installStudyServiceHooks(loader, pkg);
        installDataUploadHooks(loader);
        installBackdoorHooks(loader);
        if ("com.tal.pad.onlineclass".equals(pkg)) {
            installOnlineClassHooks(loader);
        }
        if ("com.tal.pad.usercenter".equals(pkg)) {
            installUserCenterHooks(loader);
        }
        if ("com.tal.pad.minor_protect".equals(pkg)) {
            installMinorProtectHooks(loader);
        }
        if ("com.tal.pad.znxxservice".equals(pkg)) {
            installZnxxServiceHooks(loader);
        }
    }
    private void installGenieControlHooks(ClassLoader systemLoader) {
        try {
            Class<?> genie = loadSystemClass(
                    "com.android.server.GenieManagerService", systemLoader);
            hookMethodByName(genie, "isAppAllowToStart", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? Boolean.TRUE : chain.proceed();
            });
            hookMethodByName(genie, "addNotAllowToStartAppPkg", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            });
            hookMethodByName(genie, "setNotAllowToStartAppPkgs", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            });
            log(Log.INFO, TAG, "genie app-control hooks installed (system_server)");
            SdcardLog.append("HookEntry", "genie app-control hooks installed");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook GenieManagerService failed", t);
        }
    }
    private void installMinorProtectHooks(ClassLoader loader) {
        try {
            Class<?> ctrl = loadAppClass(
                    "com.tal.znxx.system_sdk.XPadSystemControlManager", loader);
            hookMethodByName(ctrl, "checkAppAllowStart", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (!CustomUsage.blockMinorControl(prefs)) {
                    return chain.proceed();
                }
                Object allow = ControlPair.allowStart(loader);
                return allow != null ? allow : chain.proceed();
            });
            hookMethodByName(ctrl, "checkAppStatusList", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (!CustomUsage.blockMinorControl(prefs)) {
                    return chain.proceed();
                }
                return java.util.Collections.emptyList();
            });
            hookMethodByName(ctrl, "getAppControlStatusFromServer", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (!CustomUsage.blockMinorControl(prefs)) {
                    return chain.proceed();
                }
                Object allow = ControlPair.allowStart(loader);
                return allow != null ? allow : chain.proceed();
            });
            hookMethodByName(ctrl, "checkAppStatusListFromServer", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            });
            hookMethodByName(ctrl, "initCheckAllAppControlStatus", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            });
            hookMethodByName(ctrl, "forbidUseApp", 2, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            });
            hookMethodByName(ctrl, "forbidUseApp", 3, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            });
            hookMethodByName(ctrl, "forbidUseAppList", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            });
            hookMethodByName(ctrl, "notifyAppForbid", 3, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            });
            hookMethodByName(ctrl, "setLockScreenDisable", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            });
            log(Log.INFO, TAG, "minor protect control hooks installed");
            SdcardLog.append("HookEntry", "minor protect control hooks installed");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook XPadSystemControlManager failed", t);
        }
        try {
            Class<?> sls = loadAppClass(
                    "com.tal.znxx.log.sls.SLSProducer", loader);
            hookMethodByName(sls, "sendLog", 2, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            });
            hookMethod(sls, "sendLog", chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            }, java.util.Map.class);
            hookMethod(sls, "sendLog", chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            }, org.json.JSONObject.class);
            log(Log.INFO, TAG, "minor protect SLS log hooks installed");
            SdcardLog.append("HookEntry", "minor protect SLS log hooks installed");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook minor SLSProducer failed", t);
        }
        try {
            Class<?> api = loadAppClass(
                    "com.sensorsdata.analytics.android.sdk.SensorsDataAPI", loader);
            hookMethod(api, "track", chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            }, String.class);
            hookMethod(api, "track", chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            }, String.class, org.json.JSONObject.class);
            hookMethod(api, "trackEventFromH5", chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            }, String.class);
            hookMethod(api, "trackEventFromH5", chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            }, String.class, boolean.class);
            log(Log.INFO, TAG, "minor protect SensorsData track hooks installed");
            SdcardLog.append("HookEntry", "minor protect SensorsData track hooks installed");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook SensorsDataAPI failed", t);
        }
    }
    private static final class ControlPair {
        static Object allowStart(ClassLoader loader) {
            try {
                Class<?> pairClass = Class.forName("kotlin.Pair", false, loader);
                java.lang.reflect.Constructor<?> ctor =
                        pairClass.getConstructor(Object.class, Object.class);
                Object inner = ctor.newInstance(Integer.valueOf(0), "");
                return ctor.newInstance(Boolean.TRUE, inner);
            } catch (Throwable t) {
                return null;
            }
        }
        static Object emptyProcess(ClassLoader loader) {
            try {
                Class<?> pairClass = Class.forName("kotlin.Pair", false, loader);
                java.lang.reflect.Constructor<?> ctor =
                        pairClass.getConstructor(Object.class, Object.class);
                return ctor.newInstance("", "");
            } catch (Throwable t) {
                return null;
            }
        }
    }
    private void installZnxxServiceHooks(ClassLoader loader) {
        try {
            Class<?> control = loadAppClass(
                    "com.tal.znxx.service.systemapi.control.SystemApiControlManager", loader);
            hookMethodByName(control, "allowAppStart", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (!CustomUsage.blockMinorControl(prefs)) {
                    return chain.proceed();
                }
                Object allow = ControlPair.allowStart(loader);
                return allow != null ? allow : chain.proceed();
            });
            hookMethodByName(control, "forbidUseApp", 2, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? Boolean.FALSE : chain.proceed();
            });
            hookMethodByName(control, "forbidUseApp", 3, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? Boolean.FALSE : chain.proceed();
            });
            hookMethodByName(control, "forbidUseAppList", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (!CustomUsage.blockMinorControl(prefs)) {
                    return chain.proceed();
                }
                try {
                    return new org.json.JSONArray();
                } catch (Throwable ignored) {
                    return null;
                }
            });
            hookMethodByName(control, "setLockScreenDisable", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            });
            log(Log.INFO, TAG, "znxxservice control hooks installed");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook SystemApiControlManager failed", t);
        }
        try {
            Class<?> process = loadAppClass(
                    "com.tal.znxx.service.systemapi.process.SystemApiProcessManager", loader);
            hookMethodByName(process, "removeTask", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            });
            hookMethodByName(process, "getForegroundProcessActivity", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (!CustomUsage.blockMinorControl(prefs)) {
                    return chain.proceed();
                }
                Object empty = ControlPair.emptyProcess(loader);
                return empty != null ? empty : chain.proceed();
            });
            log(Log.INFO, TAG, "znxxservice process hooks installed");
            SdcardLog.append("HookEntry", "znxxservice control/process hooks installed");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook SystemApiProcessManager failed", t);
        }
        try {
            Class<?> sls = loadAppClass(
                    "com.tal.znxx.log.sls.SLSProducer", loader);
            hookMethodByName(sls, "sendLog", 2, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            });
            hookMethod(sls, "sendLog", chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            }, java.util.Map.class);
            hookMethod(sls, "sendLog", chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            }, org.json.JSONObject.class);
            SdcardLog.append("HookEntry", "znxxservice SLS log hooks installed");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook znxxservice SLSProducer failed", t);
        }
        try {
            Class<?> common = loadAppClass(
                    "com.tal.znxx.service.systemapi.common.SystemApiCommonManager", loader);
            hookMethodByName(common, "install", 2, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            });
            hookMethodByName(common, "uninstall", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            });
            hookMethodByName(common, "rebootDevice", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            });
            hookMethodByName(common, "switchScreenTimeout", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            });
            String[] statusBarMethods = {
                    "toggleStatusBarBack", "toggleStatusBarExpand", "toggleStatusBarHome",
                    "toggleStatusBarExpandHome", "toggleStatusBarExpandBack",
                    "toggleStatusBarHomeBack", "toggleStatusBarExpandHomeBack"};
            for (String m : statusBarMethods) {
                hookMethodByName(common, m, 1, chain -> {
                    SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                    return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
                });
            }
            SdcardLog.append("HookEntry", "znxxservice common-control hooks installed");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook SystemApiCommonManager failed", t);
        }
        try {
            Class<?> screen = loadAppClass(
                    "com.tal.znxx.service.systemapi.SystemApiScreenManager", loader);
            hookMethodByName(screen, "provideMediaProjection", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            });
            SdcardLog.append("HookEntry", "znxxservice screen-control hooks installed");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook SystemApiScreenManager failed", t);
        }
        try {
            Class<?> logMgr = loadAppClass("SystemApiLogManager", loader);
            hookMethodByName(logMgr, "getLogs", 2, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            });
            hookMethodByName(logMgr, "startGrab", 2, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            });
            hookMethodByName(logMgr, "stopGrab", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            });
            hookMethodByName(logMgr, "cancelGrab", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockMinorControl(prefs) ? null : chain.proceed();
            });
            SdcardLog.append("HookEntry", "znxxservice log-grab hooks installed");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook SystemApiLogManager failed", t);
        }
    }
    private void installUserCenterHooks(ClassLoader loader) {
        try {
            Class<?> sysInfo = loadAppClass(
                    "com.tal.user.device.helper.SysInfoHelper", loader);
            hookMethodByName(sysInfo, "checkRootFile", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? 0 : chain.proceed();
            });
            hookMethodByName(sysInfo, "findHookStack", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? Boolean.FALSE : chain.proceed();
            });
            hookMethodByName(sysInfo, "checkEmulatorFiles", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? "[]" : chain.proceed();
            });
            hookMethodByName(sysInfo, "checkProperties", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? "" : chain.proceed();
            });
            hookMethodByName(sysInfo, "getADBStatus", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? "0" : chain.proceed();
            });
            hookMethodByName(sysInfo, "isVpnUsed", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? 0 : chain.proceed();
            });
            hookMethodByName(sysInfo, "isWifiProxy", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? Boolean.FALSE : chain.proceed();
            });
            hookMethodByName(sysInfo, "getCheckSelinuxState", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? "1" : chain.proceed();
            });
            hookMethodByName(sysInfo, "getSysInfo", 0, chain -> {
                Object result = chain.proceed();
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.blockUserCenterDetect(prefs)) {
                    sanitizeSysInfoMap(result);
                }
                return result;
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook SysInfoHelper failed", t);
        }
        try {
            Class<?> jni = loadAppClass(
                    "com.tal.user.device.utils.JNISecurity", loader);
            hookMethodByName(jni, "getCmdlineJava", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? "" : chain.proceed();
            });
            hookMethodByName(jni, "checkMagisk", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? "" : chain.proceed();
            });
            hookMethodByName(jni, "checkCheatApp", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? "" : chain.proceed();
            });
            hookMethodByName(jni, "checkCheatDebuggable", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? 0 : chain.proceed();
            });
            hookMethodByName(jni, "checkInjectSoInfo", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? "" : chain.proceed();
            });
            hookMethodByName(jni, "getHookFramwork", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? "" : chain.proceed();
            });
            hookMethodByName(jni, "getCmdline", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? "" : chain.proceed();
            });
            hookMethodByName(jni, "getCheckAttached", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? 0 : chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook JNISecurity failed", t);
        }
        try {
            Class<?> risk = loadAppClass(
                    "com.tal.user.device.helper.RiskManInfoHelper", loader);
            hookMethodByName(risk, "checkDebug", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? "0" : chain.proceed();
            });
            hookMethodByName(risk, "getRiskManagementInfo", 0, chain -> {
                Object result = chain.proceed();
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.blockUserCenterDetect(prefs)
                        && result instanceof Map) {
                    ((Map<Object, Object>) result).put("debug", "0");
                }
                return result;
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook RiskManInfoHelper failed", t);
        }
        try {
            Class<?> shell = loadAppClass(
                    "com.tal.user.device.utils.CommandShellUtils", loader);
            hookMethodByName(shell, "deviceIsRoot", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? Boolean.FALSE : chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook CommandShellUtils.deviceIsRoot failed", t);
        }
        try {
            Class<?> checkRoot = loadAppClass(
                    "com.mobile.auth.gatewayauth.utils.security.CheckRoot", loader);
            hookMethodByName(checkRoot, "isDeviceRooted", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? "" : chain.proceed();
            });
            hookMethodByName(checkRoot, "checkDeviceDebuggable", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? Boolean.FALSE : chain.proceed();
            });
            hookMethodByName(checkRoot, "checkRootPathSU", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? Boolean.FALSE : chain.proceed();
            });
            hookMethodByName(checkRoot, "checkSuperuserApk", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? Boolean.FALSE : chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook CheckRoot failed", t);
        }
        try {
            Class<?> checker = loadAppClass(
                    "com.mobile.auth.gatewayauth.utils.Checker", loader);
            hookMethodByName(checker, "c", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? "0" : chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook Checker.c failed", t);
        }
        try {
            Class<?> checkHook = loadAppClass(
                    "com.mobile.auth.gatewayauth.utils.security.CheckHook", loader);
            hookMethodByName(checkHook, "isHookByJar", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? Boolean.FALSE : chain.proceed();
            });
            hookMethodByName(checkHook, "isHookByStack", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? Boolean.FALSE : chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook CheckHook failed", t);
        }
        try {
            Class<?> emulator = loadAppClass(
                    "com.mobile.auth.gatewayauth.utils.security.EmulatorDetector", loader);
            hookMethodByName(emulator, "isEmulator", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? Boolean.FALSE : chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook EmulatorDetector failed", t);
        }
        try {
            Class<?> proxy = loadAppClass(
                    "com.mobile.auth.gatewayauth.utils.security.CheckProxy", loader);
            hookMethodByName(proxy, "isDevicedProxy", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? Boolean.FALSE : chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook CheckProxy failed", t);
        }
        try {
            Class<?> reporter = loadAppClass(
                    "com.tal.user.device.report.TalDeviceReporter", loader);
            hookMethodByName(reporter, "reportSource", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? null : chain.proceed();
            });
            hookMethodByName(reporter, "reportToLogger", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? null : chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook TalDeviceReporter failed", t);
        }
        try {
            Class<?> strReporter = loadAppClass(
                    "com.tal.user.device.report.TalDeviceStrReporter", loader);
            hookMethodByName(strReporter, "report", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? null : chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook TalDeviceStrReporter failed", t);
        }
        try {
            Class<?> sls = loadAppClass(
                    "com.tal.znxx.log.sls.SLSProducer", loader);
            hookMethodByName(sls, "sendLog", 2, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? null : chain.proceed();
            });
            hookMethod(sls, "sendLog", chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? null : chain.proceed();
            }, java.util.Map.class);
            hookMethod(sls, "sendLog", chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs) ? null : chain.proceed();
            }, org.json.JSONObject.class);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook SLSProducer failed", t);
        }
        try {
            Class<?> phoneAuth = loadAppClass(
                    "com.mobile.auth.gatewayauth.PhoneNumberAuthHelper", loader);
            hookMethodByName(phoneAuth, "checkEnvAvailable", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs)
                        ? Boolean.TRUE : chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook PhoneNumberAuthHelper.checkEnvAvailable failed", t);
        }
        try {
            Class<?> phoneAuthProxy = loadAppClass(
                    "com.mobile.auth.gatewayauth.PhoneNumberAuthHelperProxy", loader);
            hookMethodByName(phoneAuthProxy, "checkEnvAvailable", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockUserCenterDetect(prefs)
                        ? Boolean.TRUE : chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook PhoneNumberAuthHelperProxy.checkEnvAvailable failed", t);
        }
    }
    private void sanitizeSysInfoMap(Object result) {
        if (!(result instanceof Map)) {
            return;
        }
        Map<Object, Object> map = (Map<Object, Object>) result;
        putClean(map, "hook_method", "0");
        putClean(map, "hook_framwork", "0");
        putClean(map, "cmdline", "");
        putClean(map, "adb", "0");
        putClean(map, "debug", "0");
        putClean(map, "root", "0");
        putClean(map, "magisk", "0");
        putClean(map, "inject_so_info", "");
        putClean(map, "vm_file", "[]");
        putClean(map, "vm_property", "");
        putClean(map, "cheats", "");
        putClean(map, "proxy", "0");
        putClean(map, "vpn", "0");
    }
    private static void putClean(Map<Object, Object> map, String key, String value) {
        if (map.containsKey(key)) {
            map.put(key, value);
        }
    }
    private void installOnlineClassHooks(ClassLoader loader) {
        try {
            Class<?> detailsClass = loadAppClass(
                    "com.tal.pad.study_service_sdk.detailsreport.StudyDetails", loader);
            hookMethodByName(detailsClass, "initReport", 2, chain -> {
                Object result = chain.proceed();
                try {
                    SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                    if (CustomUsage.isEnabled(prefs)) {
                        boolean oncePerDay = prefs == null
                                || prefs.getBoolean(
                                        Config.KEY_STUDY_ONCE_PER_DAY,
                                        Config.DEFAULT_STUDY_ONCE_PER_DAY);
                        if (oncePerDay && studyFabricatedToday()) {
                            log(Log.INFO, TAG,
                                    "study once-per-day already sent, skip");
                            return result;
                        }
                        long studyMillis = Math.min(
                                CustomUsage.studyMillis(prefs),
                                remainingStudyBudget());
                        if (studyMillis > 0) {
                            sendStudyChunks(detailsClass,
                                    chain.getThisObject(), studyMillis);
                            addStudyReported(studyMillis);
                            if (oncePerDay) {
                                markStudyFabricatedToday();
                            }
                            log(Log.INFO, TAG, "lesson entered, fabricated study: "
                                    + studyMillis + " ms (day total "
                                    + sStudyBudgetMs + " ms)");
                            SdcardLog.append("HookEntry",
                                    "lesson entered, fabricated study: "
                                            + studyMillis + " ms (day total "
                                            + sStudyBudgetMs + " ms)");
                        }
                    }
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "lesson study fabricate failed", t);
                }
                return result;
            });
            hookMethodByName(detailsClass, "reportStudyTime", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.isEnabled(prefs)) {
                    logStudyZeroedOnce();
                    return null;
                }
                return chain.proceed();
            });
            try {
                Class<?> reqBase = loadAppClass(
                        "com.tal.onlineclass.studypath.model.ReqVideoBase", loader);
                hookMethodByName(reqBase, "setWatch_time", 1, chain -> {
                    SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                    if (CustomUsage.isEnabled(prefs)) {
                        return chain.proceed(new Object[]{0});
                    }
                    return chain.proceed();
                });
            } catch (Throwable t) {
                log(Log.WARN, TAG, "hook ReqVideoBase.setWatch_time failed", t);
            }
            try {
                Class<?> detailsReporter = loadAppClass(
                        "com.tal.pad.study_service_sdk.detailsreport.StudyDetailsReporter",
                        loader);
                hookMethodByName(detailsReporter, "reportStudyDetails", 1, chain -> {
                    SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                    if (CustomUsage.isEnabled(prefs)
                            && chain.getArg(0) instanceof org.json.JSONObject) {
                        ((org.json.JSONObject) chain.getArg(0)).put("duration", 0L);
                    }
                    return chain.proceed();
                });
            } catch (Throwable t) {
                log(Log.WARN, TAG, "hook StudyDetailsReporter.reportStudyDetails failed", t);
            }
            try {
                Class<?> playerVm = loadAppClass(
                        "com.tal.onlineclass.player.viewmodel.DefaultPlayerViewModel", loader);
                hookMethodByName(playerVm, "n", 6, chain -> {
                    SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                    if (CustomUsage.isEnabled(prefs)) {
                        return chain.proceed(new Object[]{
                                chain.getArg(0), chain.getArg(1), chain.getArg(2),
                                chain.getArg(3), 0, chain.getArg(5)});
                    }
                    return chain.proceed();
                });
            } catch (Throwable t) {
                log(Log.WARN, TAG, "hook DefaultPlayerViewModel.n failed", t);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook StudyDetails failed", t);
        }
    }
    private static volatile String sStudyBlockLogDate;
    private void logStudyZeroedOnce() {
        String today = todayStr();
        if (today.equals(sStudyBlockLogDate)) {
            return;
        }
        sStudyBlockLogDate = today;
        log(Log.INFO, TAG,
                "real study report blocked, only fake study uploads");
    }
    private boolean shiftStudyStartTime(Object details, long studyMillis) {
        try {
            if (studyMillis <= 0) {
                return false;
            }
            Object pair = getField(details, "currentStudyDetails");
            if (pair == null) {
                return false;
            }
            Object second = pair.getClass().getMethod("getSecond").invoke(pair);
            if (!(second instanceof org.json.JSONObject)) {
                return false;
            }
            ((org.json.JSONObject) second).put(
                    "start_time",
                    SystemClock.elapsedRealtime() - studyMillis);
            return true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "shift study start_time failed", t);
            return false;
        }
    }
    private void sendStudyChunks(Class<?> detailsClass, Object details,
                                 long totalMillis) throws Throwable {
        Method reportStudyTime =
                findMethodByName(detailsClass, "reportStudyTime", 0);
        long remaining = totalMillis;
        while (remaining > 0) {
            long chunk = Math.min(remaining, 60000L);
            shiftStudyStartTime(details, chunk);
            invokePrivate(reportStudyTime, details);
            remaining -= chunk;
        }
    }
    private long remainingStudyBudget() {
        String today = todayStr();
        if (!today.equals(sStudyBudgetDate)) {
            sStudyBudgetDate = today;
            sStudyBudgetMs = 0L;
            try {
                android.content.Context ctx = appContext();
                if (ctx != null) {
                    SharedPreferences p = ctx.getSharedPreferences(
                            "tal_patch_markers",
                            android.content.Context.MODE_PRIVATE);
                    if (today.equals(p.getString("study_budget_date", ""))) {
                        sStudyBudgetMs = p.getLong("study_budget_ms", 0L);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return Math.max(0L, CustomUsage.MAX_DAILY_MS - sStudyBudgetMs);
    }
    private void addStudyReported(long ms) {
        if (ms <= 0) {
            return;
        }
        String today = todayStr();
        if (!today.equals(sStudyBudgetDate)) {
            sStudyBudgetDate = today;
            sStudyBudgetMs = 0L;
        }
        sStudyBudgetMs += ms;
        try {
            android.content.Context ctx = appContext();
            if (ctx != null) {
                ctx.getSharedPreferences("tal_patch_markers",
                        android.content.Context.MODE_PRIVATE)
                        .edit()
                        .putString("study_budget_date", today)
                        .putLong("study_budget_ms", sStudyBudgetMs)
                        .commit();
            }
        } catch (Throwable ignored) {
        }
    }
    private void installStudyServiceHooks(ClassLoader loader, String hostPkg) {
        try {
            Class<?> useCache = loadAppClass(
                    "com.tal.znxx.studyservice.ctr.device.control.UseCache", loader);
            hookMethodByName(useCache, "getTodayUseTime", 2, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.isEnabled(prefs)
                        && CustomUsage.KEY_TODAY_USE_TIME.equals(chain.getArg(0))) {
                    return CustomUsage.studyMillis(prefs);
                }
                return chain.proceed();
            });
            hookMethodByName(useCache, "saveTodayUseTime", 3, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.isEnabled(prefs)
                        && CustomUsage.KEY_TODAY_USE_TIME.equals(chain.getArg(0))) {
                    return chain.proceed(new Object[]{
                            chain.getArg(0), chain.getArg(1), CustomUsage.studyMillis(prefs)});
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook UseCache failed", t);
        }
        try {
            Class<?> singleDay = loadAppClass(
                    "com.tal.znxx.studyservice.ctr.device.control.SingleDayUseCtr", loader);
            hookMethodByName(singleDay, "getDayUser", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.isEnabled(prefs)) {
                    long value = CustomUsage.studyMillis(prefs);
                    log(Log.INFO, TAG, "getDayUser -> " + value + " (custom)");
                    return value;
                }
                Object result = chain.proceed();
                log(Log.INFO, TAG, "getDayUser -> " + result + " (real)");
                return result;
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook SingleDayUseCtr failed", t);
        }
        try {
            Class<?> continueDur = loadAppClass(
                    "com.tal.znxx.studyservice.ctr.device.duration.ContinueUseDurationUtils",
                    loader);
            hookMethodByName(continueDur, "getCurrentUserUsage", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                Object bean = chain.proceed();
                if (CustomUsage.isEnabled(prefs) && bean != null) {
                    setField(bean, "usageDuration", CustomUsage.studyMillis(prefs));
                }
                return bean;
            });
            hookMethodByName(continueDur, "saveCurrentUserUsage", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                Object bean = chain.getArg(0);
                if (CustomUsage.isEnabled(prefs) && bean != null) {
                    setField(bean, "usageDuration", CustomUsage.studyMillis(prefs));
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook ContinueUseDurationUtils failed", t);
        }
        try {
            Class<?> appUseTime = loadAppClass(
                    "com.tal.znxx.studyservice.ctr.app.AppUseTimeManager", loader);
            hookMethodByName(appUseTime, "getAppTotalUseTime", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.isEnabled(prefs)) {
                    Long custom = CustomUsage.appUsageMillis(
                            prefs, (String) chain.getArg(0));
                    return custom != null ? custom : 0L;
                }
                return chain.proceed();
            });
            hookMethodByName(appUseTime, "saveAppUseTime", 3, chain -> {
                if (sFabricatingUsage) {
                    return chain.proceed();
                }
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.isEnabled(prefs)) {
                    Long custom = CustomUsage.appUsageMillis(
                            prefs, (String) chain.getArg(0));
                    if (custom != null) {
                        return chain.proceed(new Object[]{
                                chain.getArg(0), custom, chain.getArg(2)});
                    }
                    return chain.proceed(new Object[]{
                            chain.getArg(0), 0L, chain.getArg(2)});
                }
                return chain.proceed();
            });
            hookMethodByName(appUseTime, "getAppUseTime", 2, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.isEnabled(prefs)) {
                    Long custom = CustomUsage.appUsageMillis(
                            prefs, (String) chain.getArg(0));
                    return custom != null ? custom : 0L;
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook AppUseTimeManager failed", t);
        }
        try {
            Class<?> sensorApp = loadAppClass(
                    "com.tal.znxx.studyservice.reporter.SensorAppUsedTime", loader);
            hookMethodByName(sensorApp, "getStartTime", 0, chain -> {
                if (sFabricatingUsage) {
                    return chain.proceed();
                }
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.isEnabled(prefs)) {
                    String pkg = null;
                    try {
                        pkg = (String) getField(chain.getThisObject(), "id");
                    } catch (Throwable ignored) {
                    }
                    Long custom = CustomUsage.appUsageMillis(prefs, pkg);
                    if (custom != null) {
                        return SystemClock.elapsedRealtime() - custom;
                    }
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook SensorAppUsedTime failed", t);
        }
        try {
            Class<?> keyguard = Class.forName("android.app.KeyguardManager");
            hookMethodByName(keyguard, "isKeyguardLocked", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.isEnabled(prefs)) {
                    return Boolean.FALSE;
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook KeyguardManager failed", t);
        }
        try {
            Class<?> autoTrack = loadAppClass(
                    "com.tal.znxx.studyservice.track.AutoUserTrackManager", loader);
            hookMethodByName(autoTrack, "hearBeatUserTrack", 2, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.isEnabled(prefs)) {
                    return chain.proceed(new Object[]{chain.getArg(0), 0});
                }
                return chain.proceed();
            });
            hookMethodByName(autoTrack, "reportUserTrack", 2, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.isEnabled(prefs)
                        && "screen_lock".equals(chain.getArg(0))) {
                    return chain.proceed(new Object[]{"screen_light", chain.getArg(1)});
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook AutoUserTrackManager failed", t);
        }
        try {
            Class<?> ctrManager = loadAppClass(
                    "com.tal.znxx.studyservice.ctr.app.AppCtrManager", loader);
            hookMethodByName(ctrManager, "dealUserControlApps", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.isEnabled(prefs) ? null : chain.proceed();
            });
            hookMethodByName(ctrManager, "asyncCheckAppControl", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.isEnabled(prefs) ? null : chain.proceed();
            });
            hookMethodByName(ctrManager, "forbidApp", 2, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.isEnabled(prefs) ? null : chain.proceed();
            });
            hookMethodByName(ctrManager, "forbidUseApp", 5, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.isEnabled(prefs) ? null : chain.proceed();
            });
            hookMethodByName(ctrManager, "showForbiddenTip", 3, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.isEnabled(prefs) ? null : chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook AppCtrManager failed", t);
        }
        try {
            Class<?> ctrUtils = loadAppClass(
                    "com.tal.znxx.studyservice.ctr.app.utils.AppCtrUtils", loader);
            hookMethodByName(ctrUtils, "forceStopPackage", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.isEnabled(prefs) ? null : chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook AppCtrUtils failed", t);
        }
        try {
            Class<?> durationCtr = loadAppClass(
                    "com.tal.controller.controller.DurationController", loader);
            hookMethodByName(durationCtr, "getUsedTime", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.isEnabled(prefs)) {
                    long value = CustomUsage.studyMillis(prefs);
                    log(Log.INFO, TAG, "DurationController.getUsedTime -> " + value + " (custom)");
                    return value;
                }
                Object result = chain.proceed();
                log(Log.INFO, TAG, "DurationController.getUsedTime -> " + result + " (real)");
                return result;
            });
            hookMethodByName(durationCtr, "getUsedTimeFromKvDaily", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.isEnabled(prefs)) {
                    long value = CustomUsage.studyMillis(prefs);
                    log(Log.INFO, TAG, "DurationController.getUsedTimeFromKvDaily -> " + value + " (custom)");
                    return value;
                }
                Object result = chain.proceed();
                log(Log.INFO, TAG, "DurationController.getUsedTimeFromKvDaily -> " + result + " (real)");
                return result;
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook DurationController failed", t);
        }
        try {
            Class<?> reporterClass = loadAppClass(
                    "com.tal.znxx.studyservice.reporter.AppUsageReporter", loader);
            hookMethodByName(reporterClass, "reportSensorData", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.isEnabled(prefs) && !sFabricatingUsage) {
                    return null;
                }
                return chain.proceed();
            });
            hookMethodByName(reporterClass, "reportAppUsageStats", 2, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.isEnabled(prefs) && !sFabricatingUsage) {
                    return null;
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook AppUsageReporter failed", t);
        }
        try {
            Class<?> reportSa = loadAppClass(
                    "com.tal.pad.study_service_sdk.helper.ReportSaHelper", loader);
            hookMethodByName(reportSa, "reportSa", 3, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (!CustomUsage.isEnabled(prefs)) {
                    return chain.proceed();
                }
                String event = (String) chain.getArg(0);
                String project = (String) chain.getArg(1);
                Object data = chain.getArg(2);
                if ("xpad_study_duration".equals(event)
                        && CustomUsage.blockStudyReport(prefs)) {
                    if (!(data instanceof org.json.JSONObject)) {
                        return null;
                    }
                    org.json.JSONObject replacement =
                            sanitizeStudyDuration((org.json.JSONObject) data);
                    if (replacement == null) {
                        return null;
                    }
                    return chain.proceed(new Object[]{event, project, replacement});
                }
                if ("xpad_app_use_duration".equals(event)) {
                    return sFabricatingUsage ? chain.proceed() : null;
                }
                if ("enter_app".equals(event)
                        && "xpad_event_track".equals(project)
                        && data instanceof org.json.JSONObject) {
                    String pkg = ((org.json.JSONObject) data).optString("app_id", "");
                    if (CustomUsage.appUsageMillis(prefs, pkg) == null) {
                        log(Log.INFO, TAG, "enter_app track blocked for unconfigured "
                                + pkg);
                        return null;
                    }
                }
                return chain.proceed();
            });
            hookMethodByName(reportSa, "reportSaBatch", 2, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (!CustomUsage.isEnabled(prefs)
                        || !CustomUsage.blockStudyReport(prefs)) {
                    return chain.proceed();
                }
                Object listObj = chain.getArg(1);
                if (!(listObj instanceof List)) {
                    return chain.proceed();
                }
                List<?> list = (List<?>) listObj;
                List<Object> filtered = new ArrayList<>(list.size());
                for (Object item : list) {
                    String event = null;
                    try {
                        event = (String) getField(item, "event");
                    } catch (Throwable ignored) {
                    }
                    if ("xpad_study_duration".equals(event)) {
                        continue;
                    }
                    filtered.add(item);
                }
                if (filtered.size() == list.size()) {
                    return chain.proceed();
                }
                return chain.proceed(new Object[]{chain.getArg(0), filtered});
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook ReportSaHelper failed", t);
        }
        try {
            Class<?> osReporter = loadAppClass(
                    "com.tal.znxx.studyservice.reporter.OsDataReporter", loader);
            hookMethodByName(osReporter, "reportOsData", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (!CustomUsage.isEnabled(prefs)) {
                    return chain.proceed();
                }
                Object arg = chain.getArg(0);
                if (!(arg instanceof String) || ((String) arg).isEmpty()) {
                    return chain.proceed();
                }
                return chain.proceed(new Object[]{
                        scrubOsReportData((String) arg)});
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook OsDataReporter failed", t);
        }
        if ("com.tal.pad.studyservice".equals(hostPkg)) {
            fabricateUsageReports(loader);
        }
    }
    private static volatile boolean sFabricatingUsage;
    private static String todayStr() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date());
    }
    private static volatile String sStudyMarkerDate;
    private static volatile String sStudyBudgetDate;
    private static volatile long sStudyBudgetMs;
    private boolean studyFabricatedToday() {
        String today = todayStr();
        if (today.equals(sStudyMarkerDate)) {
            return true;
        }
        try {
            android.content.Context ctx = appContext();
            if (ctx != null) {
                String local = ctx.getSharedPreferences("tal_patch_markers",
                        android.content.Context.MODE_PRIVATE)
                        .getString("study", "");
                if (today.equals(local)) {
                    sStudyMarkerDate = today;
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        ParcelFileDescriptor pfd = null;
        try {
            pfd = openRemoteFile("study_fabricated_marker");
            if (pfd == null) {
                return false;
            }
            java.io.FileInputStream in =
                    new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            pfd = null;
            byte[] buffer = new byte[64];
            int n = in.read(buffer);
            in.close();
            if (n <= 0) {
                return false;
            }
            String value = new String(buffer, 0, n, StandardCharsets.UTF_8).trim();
            if (today.equals(value)) {
                sStudyMarkerDate = today;
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
    private void markStudyFabricatedToday() {
        sStudyMarkerDate = todayStr();
        try {
            android.content.Context ctx = appContext();
            if (ctx != null) {
                ctx.getSharedPreferences("tal_patch_markers",
                        android.content.Context.MODE_PRIVATE)
                        .edit().putString("study", sStudyMarkerDate).commit();
            }
        } catch (Throwable ignored) {
        }
        ParcelFileDescriptor pfd = null;
        try {
            pfd = openRemoteFile("study_fabricated_marker");
            if (pfd == null) {
                return;
            }
            java.io.FileOutputStream out =
                    new ParcelFileDescriptor.AutoCloseOutputStream(pfd);
            pfd = null;
            byte[] content = todayStr().getBytes(StandardCharsets.UTF_8);
            byte[] padded = new byte[64];
            System.arraycopy(content, 0, padded, 0,
                    Math.min(content.length, padded.length));
            out.write(padded);
            out.flush();
            out.close();
        } catch (Throwable ignored) {
        } finally {
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
    private static volatile String sMinorStudyMarkerDate;
    private boolean minorStudyFabricatedToday() {
        String today = todayStr();
        if (today.equals(sMinorStudyMarkerDate)) {
            return true;
        }
        try {
            android.content.Context ctx = appContext();
            if (ctx != null) {
                String local = ctx.getSharedPreferences("tal_patch_markers",
                        android.content.Context.MODE_PRIVATE)
                        .getString("minor_study", "");
                if (today.equals(local)) {
                    sMinorStudyMarkerDate = today;
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
    private void markMinorStudyFabricatedToday() {
        sMinorStudyMarkerDate = todayStr();
        try {
            android.content.Context ctx = appContext();
            if (ctx != null) {
                ctx.getSharedPreferences("tal_patch_markers",
                        android.content.Context.MODE_PRIVATE)
                        .edit().putString("minor_study", sMinorStudyMarkerDate).commit();
            }
        } catch (Throwable ignored) {
        }
    }
    private JSONObject sanitizeStudyDuration(JSONObject content) {
        SharedPreferences prefs = getRemotePreferences(Config.PREFS);
        if (minorStudyFabricatedToday()) {
            return null;
        }
        long millis = CustomUsage.studyMillis(prefs);
        try {
            content.put("duration", millis);
            content.put("start_time", SystemClock.elapsedRealtime() - millis);
        } catch (Throwable ignored) {
        }
        markMinorStudyFabricatedToday();
        log(Log.INFO, TAG, "xpad_study_duration fabricated to " + millis + " ms");
        SdcardLog.append("HookEntry", "xpad_study_duration fabricated to " + millis + " ms");
        return content;
    }
    private void fabricateUsageReports(final ClassLoader loader) {
        if (sUsageFabricated) {
            return;
        }
        sUsageFabricated = true;
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(10000L);
            } catch (InterruptedException ignored) {
            }
            Class<?> reporterClass = null;
            Class<?> sensorClass = null;
            Object instance = null;
            Method reportSensorData = null;
            Constructor<?> ctor = null;
            try {
                reporterClass = loadAppClass(
                        "com.tal.znxx.studyservice.reporter.AppUsageReporter", loader);
                sensorClass = loadAppClass(
                        "com.tal.znxx.studyservice.reporter.SensorAppUsedTime", loader);
                instance = getStaticField(reporterClass, "INSTANCE");
                reportSensorData =
                        findMethodByName(reporterClass, "reportSensorData", 0);
                ctor = sensorClass.getDeclaredConstructor(String.class, long.class);
                try {
                    ctor.setAccessible(true);
                } catch (Throwable ignored) {
                }
            } catch (Throwable t) {
                log(Log.WARN, TAG, "fabricate usage reports failed", t);
                return;
            }
            android.content.Context context = appContext();
            log(Log.INFO, TAG, "usage fabrication loop started");
            SdcardLog.append("HookEntry", "usage fabrication loop started");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                    if (CustomUsage.isEnabled(prefs)) {
                        Map<String, Long> entries = CustomUsage.appUsageEntries(prefs);
                        if (!entries.isEmpty()) {
                            fabricateUsagePass(reporterClass, sensorClass, ctor,
                                    reportSensorData, instance, context, entries);
                        }
                    }
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "fabricate usage pass failed", t);
                }
                try {
                    Thread.sleep(60000L);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "TAL-Patch-FakeUsage");
        thread.setDaemon(true);
        thread.start();
    }
    private void fabricateUsagePass(Class<?> reporterClass, Class<?> sensorClass,
                                    Constructor<?> ctor, Method reportSensorData,
                                    Object instance, android.content.Context context,
                                    Map<String, Long> entries) {
        try {
            org.json.JSONObject marker = loadUsageMarkerAll(context);
            String today = todayStr();
            if (!today.equals(marker.optString("date", ""))) {
                marker = new org.json.JSONObject();
                marker.put("date", today);
            }
            for (Map.Entry<String, Long> entry : entries.entrySet()) {
                long raw = entry.getValue();
                long target = Math.min(raw, CustomUsage.MAX_DAILY_MS);
                if (target != raw) {
                    log(Log.WARN, TAG, "cap usage for " + entry.getKey()
                            + " to 24h: " + raw + " -> " + target + " ms");
                    SdcardLog.append("HookEntry", "cap usage for " + entry.getKey()
                            + " to 24h: " + raw + " -> " + target + " ms");
                }
                if (target <= 0) {
                    continue;
                }
                if (marker.has(entry.getKey())) {
                    continue;
                }
                marker.put(entry.getKey(), target);
                saveUsageMarker(context, marker);
                saveUsageMarkerRemote(marker);
                reportChunks(reporterClass, sensorClass, ctor,
                        reportSensorData, instance, entry.getKey(), target);
                log(Log.INFO, TAG, "fabricated usage report: "
                        + entry.getKey() + " +" + target + " ms (once per day)");
                SdcardLog.append("HookEntry", "fabricated usage report: "
                        + entry.getKey() + " +" + target + " ms (once per day)");
            }
            saveUsageMarker(context, marker);
            saveUsageMarkerRemote(marker);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "fabricate usage pass failed", t);
        }
    }
    private static android.content.Context appContext() {
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            return app instanceof android.content.Context
                    ? (android.content.Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }
    private static org.json.JSONObject loadUsageMarker(android.content.Context context) {
        try {
            if (context != null) {
                String raw = context.getSharedPreferences("tal_patch_markers",
                        android.content.Context.MODE_PRIVATE)
                        .getString("usage", "");
                if (raw != null && !raw.isEmpty()) {
                    return new org.json.JSONObject(raw);
                }
            }
        } catch (Throwable ignored) {
        }
        return new org.json.JSONObject();
    }
    private static void saveUsageMarker(android.content.Context context,
                                        org.json.JSONObject marker) {
        try {
            if (context != null) {
                context.getSharedPreferences("tal_patch_markers",
                        android.content.Context.MODE_PRIVATE)
                        .edit().putString("usage", marker.toString()).commit();
            }
        } catch (Throwable ignored) {
        }
    }
    private org.json.JSONObject loadUsageMarkerAll(android.content.Context context) {
        org.json.JSONObject remote = readUsageMarkerRemote();
        org.json.JSONObject local = loadUsageMarker(context);
        String today = todayStr();
        if (remote != null && today.equals(remote.optString("date", ""))) {
            if (local != null && today.equals(local.optString("date", ""))) {
                java.util.Iterator<String> keys = local.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (!remote.has(key)) {
                        try {
                            remote.put(key, local.get(key));
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            return remote;
        }
        if (local != null && today.equals(local.optString("date", ""))) {
            return local;
        }
        return new org.json.JSONObject();
    }
    private org.json.JSONObject readUsageMarkerRemote() {
        ParcelFileDescriptor pfd = null;
        try {
            pfd = openRemoteFile("usage_fabricated_marker");
            if (pfd == null) {
                return null;
            }
            java.io.FileInputStream in =
                    new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            pfd = null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            in.close();
            String raw = out.toString(StandardCharsets.UTF_8.name()).trim();
            if (!raw.isEmpty()) {
                return new org.json.JSONObject(raw);
            }
        } catch (Throwable ignored) {
        } finally {
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }
    private void saveUsageMarkerRemote(org.json.JSONObject marker) {
        ParcelFileDescriptor pfd = null;
        try {
            pfd = openRemoteFile("usage_fabricated_marker");
            if (pfd == null) {
                return;
            }
            java.io.FileOutputStream out =
                    new ParcelFileDescriptor.AutoCloseOutputStream(pfd);
            pfd = null;
            byte[] content = marker.toString().getBytes(StandardCharsets.UTF_8);
            byte[] padded = new byte[1024];
            int len = Math.min(content.length, padded.length);
            System.arraycopy(content, 0, padded, 0, len);
            for (int i = len; i < padded.length; i++) {
                padded[i] = ' ';
            }
            out.write(padded);
            out.flush();
            out.close();
        } catch (Throwable ignored) {
        } finally {
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
    private long reportChunks(Class<?> reporterClass, Class<?> sensorClass,
                              Constructor<?> ctor, Method reportSensorData,
                              Object instance, String pkg, long totalMillis)
            throws Throwable {
        long remaining = totalMillis;
        while (remaining > 0) {
            long chunk = Math.min(remaining, 60000L);
            Object sensor = ctor.newInstance(
                    pkg, SystemClock.elapsedRealtime() - chunk);
            setStaticField(reporterClass, "currentApp", sensor);
            sFabricatingUsage = true;
            try {
                invokePrivate(reportSensorData, instance);
            } finally {
                sFabricatingUsage = false;
            }
            remaining -= chunk;
        }
        return totalMillis - remaining;
    }
    private static Object getStaticField(Class<?> clazz, String name)
            throws Exception {
        Field field = clazz.getDeclaredField(name);
        try {
            field.setAccessible(true);
        } catch (Throwable ignored) {
        }
        return field.get(null);
    }
    private static void setStaticField(Class<?> clazz, String name, Object value)
            throws Exception {
        Field field = clazz.getDeclaredField(name);
        try {
            field.setAccessible(true);
        } catch (Throwable ignored) {
        }
        field.set(null, value);
    }
    private void installDataUploadHooks(ClassLoader loader) {
        try {
            Class<?> dao = loadAppClass(
                    "com.tal.dataupload.power.appusage.PowerAppusageInfoDao", loader);
            hookMethodByName(dao, "getAllPowerAppusageInfos", 0, chain -> {
                Object result = chain.proceed();
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.isEnabled(prefs)) {
                    return filterAppUsageList(result);
                }
                return result;
            });
            hookMethodByName(dao, "readByTime", 1, chain -> {
                Object result = chain.proceed();
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.isEnabled(prefs)) {
                    zeroOrOverrideAppUsage(result);
                }
                return result;
            });
            hookMethodByName(dao, "add", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (!CustomUsage.isEnabled(prefs)) {
                    return chain.proceed();
                }
                if (hideOrOverrideAppUsage(chain.getArg(0))) {
                    return chain.proceed();
                }
                return null;
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook PowerAppusageInfoDao failed", t);
        }
        try {
            Class<?> info = loadAppClass(
                    "com.tal.dataupload.power.appusage.PowerAppUsageInfo", loader);
            hookMethodByName(info, "getDuration", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.isEnabled(prefs)) {
                    String pkg = null;
                    try {
                        pkg = (String) getField(chain.getThisObject(), "pkgName");
                    } catch (Throwable ignored) {
                    }
                    Long custom = CustomUsage.appUsageMillis(prefs, pkg);
                    return custom != null ? custom : 0L;
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook PowerAppUsageInfo failed", t);
        }
        try {
            Class<?> boardDao = loadAppClass(
                    "com.tal.dataupload.power.appboad.PowerAppusageBoardInfoDao", loader);
            hookMethodByName(boardDao, "getAllPowerAppusageBoardInfos", 0, chain -> {
                Object result = chain.proceed();
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.isEnabled(prefs)) {
                    scrubAppBoardList(result);
                }
                return result;
            });
            hookMethodByName(boardDao, "readByTime", 1, chain -> {
                Object result = chain.proceed();
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.isEnabled(prefs)) {
                    scrubAppBoard(result);
                }
                return result;
            });
            hookMethodByName(boardDao, "add", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.isEnabled(prefs)) {
                    scrubAppBoard(chain.getArg(0));
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook PowerAppusageBoardInfoDao failed", t);
        }
        try {
            Class<?> deviceState = loadAppClass(
                    "com.tal.dataupload.DeviceState", loader);
            hookMethodByName(deviceState, "isScreenOn", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.isEnabled(prefs)) {
                    return Boolean.TRUE;
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook DeviceState failed", t);
        }
    }
    private void installBackdoorHooks(ClassLoader loader) {
        try {
            Class<?> rootUtil = loadAppClass(
                    "com.aliyun.sls.android.core.utils.RootUtil", loader);
            hookMethodByName(rootUtil, "isDeviceRooted", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.blockRootLogs(prefs)) {
                    return Boolean.FALSE;
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook RootUtil failed", t);
        }
        try {
            Class<?> deviceUtils = loadAppClass(
                    "com.aliyun.sls.android.core.utils.DeviceUtils", loader);
            hookMethodByName(deviceUtils, "isRoot", 0, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.blockRootLogs(prefs)) {
                    return Boolean.FALSE;
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook DeviceUtils failed", t);
        }
        installRootTraceHooks(loader);
    }
    private void installRootTraceHooks(ClassLoader loader) {
        try {
            Class<?> sysProps = Class.forName("android.os.SystemProperties");
            hookMethodByName(sysProps, "get", 1, chain -> {
                String key = (String) chain.getArg(0);
                String value = (String) chain.proceed();
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockRootLogs(prefs)
                        ? RootTraceHider.sanitizeProp(key, value) : value;
            });
            hookMethodByName(sysProps, "get", 2, chain -> {
                String key = (String) chain.getArg(0);
                Object value = chain.proceed();
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (value == null || !CustomUsage.blockRootLogs(prefs)) {
                    return value;
                }
                return RootTraceHider.sanitizeProp(key, value.toString());
            });
            hookMethodByName(sysProps, "getBoolean", 2, chain -> {
                String key = (String) chain.getArg(0);
                Boolean value = (Boolean) chain.proceed();
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (!CustomUsage.blockRootLogs(prefs)) {
                    return value;
                }
                switch (key == null ? "" : key) {
                    case "ro.debuggable":
                    case "service.adb.root":
                    case "persist.sys.root_access":
                        return Boolean.FALSE;
                    case "ro.secure":
                    case "ro.adb.secure":
                        return Boolean.TRUE;
                    default:
                        return value;
                }
            });
            hookMethodByName(sysProps, "getInt", 2, chain -> {
                String key = (String) chain.getArg(0);
                Integer value = (Integer) chain.proceed();
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (!CustomUsage.blockRootLogs(prefs)) {
                    return value;
                }
                if ("ro.debuggable".equals(key)) {
                    return 0;
                }
                if ("ro.secure".equals(key)) {
                    return 1;
                }
                return value;
            });
            log(Log.INFO, TAG, "root trace: SystemProperties hooks installed");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook SystemProperties failed", t);
        }
        try {
            Class<?> fileClass = Class.forName("java.io.File");
            hookMethodByName(fileClass, "exists", 0, chain -> {
                File file = (File) chain.getThisObject();
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.blockRootLogs(prefs)
                        && RootTraceHider.isRootPath(file.getPath())) {
                    return Boolean.FALSE;
                }
                return chain.proceed();
            });
            hookMethodByName(fileClass, "isFile", 0, chain -> {
                File file = (File) chain.getThisObject();
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.blockRootLogs(prefs)
                        && RootTraceHider.isRootPath(file.getPath())) {
                    return Boolean.FALSE;
                }
                return chain.proceed();
            });
            hookMethodByName(fileClass, "canRead", 0, chain -> {
                File file = (File) chain.getThisObject();
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (CustomUsage.blockRootLogs(prefs)
                        && RootTraceHider.isRootPath(file.getPath())) {
                    return Boolean.FALSE;
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "root trace: File hooks installed");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook File failed", t);
        }
        try {
            Class<?> apm = Class.forName("android.app.ApplicationPackageManager");
            hookMethodByName(apm, "getInstalledPackages", 1, chain -> {
                Object result = chain.proceed();
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockRootLogs(prefs)
                        ? filterRootPackages(result) : result;
            });
            hookMethodByName(apm, "getInstalledApplications", 1, chain -> {
                Object result = chain.proceed();
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                return CustomUsage.blockRootLogs(prefs)
                        ? filterRootPackages(result) : result;
            });
            log(Log.INFO, TAG, "root trace: PackageManager hooks installed");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook PackageManager failed", t);
        }
        try {
            Class<?> commandResult = loadAppClass(
                    "com.tal.znxx.util.ShellUtils$CommandResult", loader);
            hookMethodByName(commandResult, "setSuccessMsg", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (!CustomUsage.blockRootLogs(prefs)) {
                    return chain.proceed();
                }
                Object arg = chain.getArg(0);
                String scrubbed = RootTraceHider.scrubOutput(
                        arg == null ? null : arg.toString());
                return chain.proceed(new Object[]{scrubbed});
            });
            hookMethodByName(commandResult, "setErrorMsg", 1, chain -> {
                SharedPreferences prefs = getRemotePreferences(Config.PREFS);
                if (!CustomUsage.blockRootLogs(prefs)) {
                    return chain.proceed();
                }
                Object arg = chain.getArg(0);
                String scrubbed = RootTraceHider.scrubOutput(
                        arg == null ? null : arg.toString());
                return chain.proceed(new Object[]{scrubbed});
            });
            log(Log.INFO, TAG, "root trace: ShellUtils output scrub installed");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook ShellUtils result failed", t);
        }
    }
    private static Object filterRootPackages(Object result) {
        if (!(result instanceof List)) {
            return result;
        }
        List<?> list = (List<?>) result;
        List<Object> filtered = new ArrayList<>(list.size());
        for (Object item : list) {
            String pkg = null;
            try {
                pkg = (String) getField(item, "packageName");
            } catch (Throwable ignored) {
            }
            if (!RootTraceHider.isRootPackage(pkg)) {
                filtered.add(item);
            }
        }
        return filtered;
    }
    private List<Object> filterAppUsageList(Object result) {
        if (!(result instanceof List)) {
            return java.util.Collections.emptyList();
        }
        List<?> list = (List<?>) result;
        List<Object> filtered = new ArrayList<>(list.size());
        for (Object item : list) {
            if (hideOrOverrideAppUsage(item)) {
                filtered.add(item);
            }
        }
        return filtered;
    }
    private boolean hideOrOverrideAppUsage(Object item) {
        if (item == null) {
            return false;
        }
        try {
            String pkg = (String) getField(item, "pkgName");
            SharedPreferences prefs = getRemotePreferences(Config.PREFS);
            Long custom = CustomUsage.appUsageMillis(prefs, pkg);
            if (custom != null) {
                setLongField(item, "duration", custom);
                return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }
    private void zeroOrOverrideAppUsage(Object item) {
        if (item == null) {
            return;
        }
        try {
            String pkg = (String) getField(item, "pkgName");
            SharedPreferences prefs = getRemotePreferences(Config.PREFS);
            Long custom = CustomUsage.appUsageMillis(prefs, pkg);
            setLongField(item, "duration", custom != null ? custom : 0L);
        } catch (Throwable ignored) {
        }
    }
    private void scrubAppBoardList(Object result) {
        if (!(result instanceof List)) {
            return;
        }
        for (Object item : (List<?>) result) {
            scrubAppBoard(item);
        }
    }
    private void scrubAppBoard(Object item) {
        if (item == null) {
            return;
        }
        try {
            setField(item, "cpuusageByapp", "");
            setField(item, "camerausageByapp", "");
            setField(item, "wifiusageByapp", "");
            setField(item, "userspaceWakelocks", "");
            setField(item, "powerEstimates", "");
            setField(item, "batteryCapacity", "");
            setLongField(item, "duration", 0L);
        } catch (Throwable ignored) {
        }
    }
    private static String scrubOsReportData(String raw) {
        try {
            org.json.JSONObject json = new org.json.JSONObject(raw);
            java.util.List<String> remove = new ArrayList<>();
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String lower = key.toLowerCase(java.util.Locale.US);
                if (lower.contains("app")
                        || lower.contains("usage")
                        || lower.contains("package")
                        || lower.contains("running")) {
                    remove.add(key);
                }
            }
            for (String key : remove) {
                json.remove(key);
            }
            return json.toString();
        } catch (Throwable t) {
            return raw;
        }
    }
    private void restoreActions(Object builder, Object params, RemoteViews big) throws Throwable {
        Class<?> cls = builder.getClass();
        if (getBooleanField(params, "mHideActions")) {
            return;
        }
        Method getActions = sGetNonContextualActions;
        if (getActions == null) {
            getActions = findMethodByName(cls, "getNonContextualActions", 0);
            sGetNonContextualActions = getActions;
        }
        Object listObject = invokePrivate(getActions, builder);
        if (!(listObject instanceof List)) {
            return;
        }
        List<?> actions = (List<?>) listObject;
        if (actions.isEmpty()) {
            return;
        }
        Object notification = getField(builder, "mN");
        if (notification == null) {
            return;
        }
        Object fullScreenIntent = getField(notification, "fullScreenIntent");
        boolean emphasizedMode = fullScreenIntent != null || getBooleanField(params, "mCallStyleActions");
        Method generateButton = sGenerateActionButton;
        if (generateButton == null) {
            generateButton = findMethodByName(cls, "generateActionButton", 3);
            sGenerateActionButton = generateButton;
        }
        int count = Math.min(actions.size(), 3);
        big.setViewVisibility(VIEW_ACTION_ROW, View.VISIBLE);
        big.setViewVisibility(VIEW_ACTION_PANEL, View.VISIBLE);
        big.setViewLayoutMarginDimen(VIEW_ACTION_MARGIN_VIEW, 3, 0);
        for (int i = 0; i < count; i++) {
            Object buttonObject = invokePrivate(generateButton, builder, actions.get(i), emphasizedMode, params);
            if (buttonObject instanceof RemoteViews) {
                RemoteViews button = (RemoteViews) buttonObject;
                if (emphasizedMode && i > 0) {
                    button.setViewLayoutMarginDimen(VIEW_ACTION_BUTTON, 4, 0);
                }
                big.addView(VIEW_ACTION_ROW, button);
            }
        }
    }
    private void hookMethod(Class<?> clazz, String name, XposedInterface.Hooker hooker,
                            Class<?>... parameterTypes) {
        try {
            Method method = clazz.getDeclaredMethod(name, parameterTypes);
            installHook(method, hooker);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook " + name + " failed", t);
        }
    }
    private void hookMethodByName(Class<?> clazz, String name, int parameterCount,
                                  XposedInterface.Hooker hooker) {
        try {
            Method method = findMethodByName(clazz, name, parameterCount);
            installHook(method, hooker);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook " + name + " failed", t);
        }
    }
    private void installHook(Method method, XposedInterface.Hooker hooker) {
        try {
            method.setAccessible(true);
        } catch (Throwable ignored) {
        }
        hook(method)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker);
        try {
            deoptimize(method);
        } catch (Throwable ignored) {
        }
        log(Log.INFO, TAG, "hooked " + method.getDeclaringClass().getName() + "#" + method.getName());
    }
    private Object invokePrivate(Method method, Object target, Object... args) throws Throwable {
        try {
            return getInvoker(method)
                    .setType(XposedInterface.Invoker.Type.ORIGIN)
                    .invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause == null ? e : cause;
        } catch (Throwable t) {
            try {
                method.setAccessible(true);
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                throw cause == null ? e : cause;
            }
        }
    }
    private static Method findMethodByName(Class<?> clazz, String name, int parameterCount)
            throws NoSuchMethodException {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterCount() == parameterCount
                    && !method.isSynthetic()
                    && !method.isBridge()) {
                return method;
            }
        }
        throw new NoSuchMethodException(clazz.getName() + "#" + name + "(" + parameterCount + " params)");
    }
    private Class<?> loadSystemServerClass(String name) throws ClassNotFoundException {
        ClassLoader loader = sSystemServerClassLoader;
        if (loader != null) {
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException e) {
            }
        }
        return Class.forName(name);
    }
    private Class<?> loadClass(String name) throws ClassNotFoundException {
        ClassLoader loader = sSystemServerClassLoader;
        if (loader != null) {
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException e) {
            }
        }
        return Class.forName(name);
    }
    private static Class<?> loadAppClass(String name, ClassLoader loader)
            throws ClassNotFoundException {
        if (loader != null) {
            return Class.forName(name, false, loader);
        }
        return Class.forName(name);
    }
    private static Bundle getExtras(Object builder) {
        try {
            Object notification = getField(builder, "mN");
            if (notification == null) {
                return null;
            }
            Object extras = getField(notification, "extras");
            return extras instanceof Bundle ? (Bundle) extras : null;
        } catch (Throwable t) {
            return null;
        }
    }
    private static Object getField(Object object, String name) throws Exception {
        Class<?> clazz = object.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(name);
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                }
                return field.get(object);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(object.getClass().getName() + "#" + name);
    }
    private static boolean getBooleanField(Object object, String name) {
        try {
            Object value = getField(object, name);
            return Boolean.TRUE.equals(value);
        } catch (Throwable t) {
            return false;
        }
    }
    private static CharSequence getCharSequenceField(Object object, String name) {
        try {
            Object value = getField(object, name);
            return value instanceof CharSequence ? (CharSequence) value : null;
        } catch (Throwable t) {
            return null;
        }
    }
    private CharSequence processTextSpans(Object builder, CharSequence text) {
        try {
            Method method = sProcessTextSpans;
            if (method == null) {
                method = findMethodByName(builder.getClass(), "processTextSpans", 1);
                sProcessTextSpans = method;
            }
            Object result = invokePrivate(method, builder, text);
            return result instanceof CharSequence ? (CharSequence) result : text;
        } catch (Throwable t) {
            return text;
        }
    }
    private void setHeaderlessVerticalMargins(Object builder, RemoteViews contentView,
                                              Object params, boolean hasSecondLine) {
        try {
            Method method = sSetHeaderlessVerticalMargins;
            if (method == null) {
                method = findMethodByName(builder.getClass(), "setHeaderlessVerticalMargins", 3);
                sSetHeaderlessVerticalMargins = method;
            }
            invokePrivate(method, null, contentView, params, hasSecondLine);
        } catch (Throwable ignored) {
        }
    }
    private static void setField(Object object, String name, Object value) {
        Class<?> clazz = object.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(name);
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                    return;
                }
                field.set(object, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Throwable t) {
                return;
            }
        }
    }
    private static void setLongField(Object object, String name, long value) {
        Class<?> clazz = object.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(name);
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                    return;
                }
                field.setLong(object, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Throwable t) {
                return;
            }
        }
    }
    private JSONObject readRemoteConfigFile() {
        ParcelFileDescriptor pfd = null;
        try {
            pfd = openRemoteFile(Config.REMOTE_FILE);
            FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            pfd = null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            in.close();
            byte[] bytes = out.toByteArray();
            if (bytes.length == 0) {
                return null;
            }
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return null;
        } finally {
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
    private static CharSequence safeCharSequence(CharSequence value) {
        try {
            Class<?> notificationClass = Class.forName("android.app.Notification");
            Method method = notificationClass.getMethod("safeCharSequence", CharSequence.class);
            method.setAccessible(true);
            Object result = method.invoke(null, value);
            return result instanceof CharSequence ? (CharSequence) result : value;
        } catch (Throwable t) {
            return value;
        }
    }
}
