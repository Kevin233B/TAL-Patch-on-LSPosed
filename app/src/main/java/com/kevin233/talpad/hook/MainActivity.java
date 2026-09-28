package com.kevin233.talpad.hook;
import android.Manifest;
import android.app.ActivityManager;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textview.MaterialTextView;
public class MainActivity extends AppCompatActivity {
    private static final String TEST_CHANNEL_ID = "tal_patch_test";
    private static final String TEST_REMOTE_INPUT_KEY = "test_answer";
    private static final String TEST_ACTION =
            "com.kevin233.talpad.hook.TEST_ACTION";
    private static final String TEST_SEND_ACTION =
            "com.kevin233.talpad.hook.TEST_SEND";
    private static final String HOOK_PROP = "sys.tal_patch.install.hook";
    private static final String PREF_NAME = "tal_patch_dialogs";
    private static final String KEY_DONATION_NOT_AGAIN = "donation_not_again";
    private static final String KEY_TUTORIAL_NOT_AGAIN = "tutorial_not_again";
    private static final int REQUEST_POST_NOTIFICATIONS = 1001;
    private static final int TEST_NOTIFICATION_ID = 1001;
    private LinearLayout deviceInfoContainer;
    private SharedPreferences prefs;
    private MaterialButton donationButton;
    private MaterialButton tutorialButton;
    private boolean donationDialogDismissed;
    private boolean tutorialDialogDismissed;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiTheme.apply(this);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        deviceInfoContainer = findViewById(R.id.deviceInfoContainer);
        donationButton = findViewById(R.id.donationButton);
        tutorialButton = findViewById(R.id.tutorialButton);
        findViewById(R.id.configButton).setOnClickListener(v ->
                startActivity(new Intent(this, ConfigActivity.class)));
        donationButton.setOnClickListener(v -> showDonationDialog(true));
        tutorialButton.setOnClickListener(v -> showTutorialDialog(true));
        findViewById(R.id.testNotificationButton).setOnClickListener(v ->
                sendTestNotification());
        findViewById(R.id.testInstallButton).setOnClickListener(v ->
                showInstallStatus());
        buildDeviceInfo();
        handleTestResult(getIntent());
        if (TEST_SEND_ACTION.equals(getIntent().getAction())) {
            sendTestNotification();
        }
        showStartupDialogs();
        ensureSdcardLogCollection();
    }
    @Override
    protected void onResume() {
        super.onResume();
        ensureSdcardLogCollection();
    }
    private void ensureSdcardLogCollection() {
        if (Environment.isExternalStorageManager()) {
            SdcardLog.clearAndCollect(this);
        } else {
            Toast.makeText(this,
                    "需要“所有文件访问”权限才能写入 /sdcard/TAL-Patch-Log",
                    Toast.LENGTH_LONG).show();
            try {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Throwable t) {
                try {
                    startActivity(new Intent(
                            Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Throwable ignored) {
                }
            }
        }
    }
    private void showStartupDialogs() {
        boolean tutorialDone = prefs.getBoolean(KEY_TUTORIAL_NOT_AGAIN, false);
        boolean donationDone = prefs.getBoolean(KEY_DONATION_NOT_AGAIN, false);
        if (tutorialDone) {
            tutorialDialogDismissed = true;
        }
        if (donationDone) {
            donationDialogDismissed = true;
        }
        updatePopupButtons();
        if (!tutorialDone) {
            showTutorialDialog(false);
        } else if (!donationDone) {
            showDonationDialog(false);
        } else {
            updatePopupButtons();
        }
    }
    private void showTutorialDialog(boolean fromButton) {
        Dialog dialog = buildDialog(
                "使用教程",
                "模块装好后，打开 LSPosed 管理器：\n\n"
                        + "1. 进入「模块」，打开 TAL-Patch；\n"
                        + "2. 在作用域里勾选「系统框架」和 com.android.systemui；\n"
                        + "3. 勾选 com.android.shell 与 com.android.packageinstaller（解除安装限制）；\n"
                        + "4. 勾选学习相关应用：studyservice、dataupload、onlineclass、backdoor；\n"
                        + "5. 把平时要看通知的 App 也勾上；\n"
                        + "6. 强停相关 App，或者直接重启设备。\n\n"
                        + "伪造学习时长：配置页开启「自定义学习时长与应用使用」，"
                        + "在「每日学习时长（分钟）」里填数值；默认「每日仅伪装一次」，"
                        + "即当天第一节课进入时补发，之后不再补发（可在配置里关闭该限制）。"
                        + "开启后真实课程上报会被拦截，父端只能看到伪装值。\n\n"
                        + "伪造应用使用情况：在「应用使用时长」里每行填一个"
                        + "「包名:分钟」（空格或全角冒号也行）；真实使用不会上传，"
                        + "父端只能看到伪装记录，每个包每天只补发一次。\n\n"
                        + "不勾作用域的进程，模块不会加载，通知也就不会恢复。",
                false,
                KEY_TUTORIAL_NOT_AGAIN,
                fromButton);
        dialog.setOnDismissListener(d -> {
            tutorialDialogDismissed = true;
            updatePopupButtons();
            if (!fromButton && !prefs.getBoolean(KEY_DONATION_NOT_AGAIN, false)) {
                showDonationDialog(false);
            }
        });
        dialog.show();
    }
    private void showDonationDialog(boolean fromButton) {
        Dialog dialog = buildDialog(
                "捐赠",
                "如果你觉得这个 Xposed 模块好用，就请给我创作的动力吧（小财迷）",
                true,
                KEY_DONATION_NOT_AGAIN,
                fromButton);
        dialog.setOnDismissListener(d -> {
            donationDialogDismissed = true;
            updatePopupButtons();
        });
        dialog.show();
    }
    private Dialog buildDialog(String titleText, String messageText, boolean showImage,
                               String prefKey, boolean fromButton) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(4), dp(8), dp(4), 0);
        if (showImage) {
            ImageView qr = new ImageView(this);
            qr.setImageResource(R.drawable.donate_qr);
            qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
            qr.setAdjustViewBounds(true);
            LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(
                    dp(220), dp(220));
            qrLp.gravity = Gravity.CENTER;
            content.addView(qr, qrLp);
        }
        MaterialTextView message = new MaterialTextView(this);
        message.setText(messageText);
        message.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        message.setLineSpacing(dp(2), 1.0f);
        message.setPadding(0, dp(8), 0, dp(4));
        content.addView(message, matchWrap());
        MaterialCheckBox notAgain = new MaterialCheckBox(this);
        notAgain.setText("不再提示");
        content.addView(notAgain, matchWrap());
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(titleText);
        builder.setView(content);
        builder.setCancelable(true);
        builder.setNegativeButton(fromButton ? "关闭" : "知道了",
                (dialog, which) -> {
                    if (notAgain.isChecked()) {
                        prefs.edit().putBoolean(prefKey, true).apply();
                    }
                    dialog.dismiss();
                });
        return builder.create();
    }
    private void updatePopupButtons() {
        if (donationButton != null) {
            boolean show = donationDialogDismissed
                    || prefs.getBoolean(KEY_DONATION_NOT_AGAIN, false);
            donationButton.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (tutorialButton != null) {
            boolean show = tutorialDialogDismissed
                    || prefs.getBoolean(KEY_TUTORIAL_NOT_AGAIN, false);
            tutorialButton.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }
    private void showInstallStatus() {
        String hookValue = systemPropertyGet(HOOK_PROP);
        boolean vType = isUserV();
        String whitelist = Settings.Global.getString(
                getContentResolver(), "install_package_whitelist");
        String whitelistText = TextUtils.isEmpty(whitelist)
                ? "仅系统内置白名单"
                : whitelist;
        String moduleStatus;
        if ("1".equals(hookValue)) {
            moduleStatus = "模块已生效：安装限制已解除";
        } else if (hookValue != null) {
            moduleStatus = "模块标记异常（值：" + hookValue + "）";
        } else {
            moduleStatus = "未检测到模块标记：请确认已在 LSPosed 启用模块并勾选“系统框架”，然后重启设备。";
        }
        String message = "安装限制状态\n" + moduleStatus
                + "\nV 型设备：" + (vType ? "是" : "否")
                + "\n全局安装白名单：" + whitelistText;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
    private static boolean isUserV() {
        try {
            Class<?> buildClass = Class.forName("android.os.Build");
            java.lang.reflect.Field field = buildClass.getDeclaredField("IS_USER_V");
            field.setAccessible(true);
            return Boolean.TRUE.equals(field.get(null));
        } catch (Throwable t) {
            return false;
        }
    }
    private static String systemPropertyGet(String key) {
        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = clazz.getMethod("get", String.class);
            Object value = get.invoke(null, key);
            return value instanceof String ? (String) value : null;
        } catch (Throwable t) {
            return null;
        }
    }
    private void sendTestNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_POST_NOTIFICATIONS);
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                TEST_CHANNEL_ID,
                "模块测试",
                NotificationManager.IMPORTANCE_HIGH);
        manager.createNotificationChannel(channel);
        Intent resultIntent = new Intent(this, MainActivity.class)
                .setAction(TEST_ACTION)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                resultIntent,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        RemoteInput remoteInput = new RemoteInput.Builder(TEST_REMOTE_INPUT_KEY)
                .setLabel("请输入 yes")
                .build();
        Notification.Action action = new Notification.Action.Builder(
                android.R.drawable.ic_menu_send,
                "输入 yes",
                pendingIntent)
                .addRemoteInput(remoteInput)
                .build();
        Notification notification = new Notification.Builder(this, TEST_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("TAL-Patch 测试")
                .setContentText("这是TAL-Patch的一项测试消息，它有一个类似于shizuku配对时的输入框，请在输入框输入yes，以测试模块是否Hook成功。")
                .setPriority(Notification.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .addAction(action)
                .build();
        notification.extras.putCharSequence(
                Notification.EXTRA_TITLE, "TAL-Patch 测试");
        notification.extras.putCharSequence(
                Notification.EXTRA_TEXT,
                "这是TAL-Patch的一项测试消息，它有一个类似于shizuku配对时的输入框，请在输入框输入yes，以测试模块是否Hook成功。");
        manager.notify(TEST_NOTIFICATION_ID, notification);
        Toast.makeText(this, "测试通知已发送，下拉通知栏输入 yes", Toast.LENGTH_SHORT).show();
    }
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleTestResult(intent);
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_POST_NOTIFICATIONS
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            sendTestNotification();
        }
    }
    private void handleTestResult(Intent intent) {
        if (intent == null || !TEST_ACTION.equals(intent.getAction())) {
            return;
        }
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        if (results == null) {
            return;
        }
        CharSequence answer = results.getCharSequence(TEST_REMOTE_INPUT_KEY);
        String text;
        if (answer == null) {
            text = "没有收到输入";
        } else if ("yes".equalsIgnoreCase(answer.toString().trim())) {
            text = "测试通过：模块 Hook 成功";
        } else {
            text = "收到回复：" + answer;
        }
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
    private void buildDeviceInfo() {
        deviceInfoContainer.removeAllViews();
        String[][] info = getDeviceInfo();
        for (String[] row : info) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(0, dp(5), 0, dp(5));
            MaterialTextView label = createRowLabel(row[0]);
            label.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.35f));
            line.addView(label);
            MaterialTextView value = createRowValue(row[1]);
            value.setGravity(Gravity.END);
            value.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.65f));
            line.addView(value);
            deviceInfoContainer.addView(line, matchWrap());
        }
    }
    private String[][] getDeviceInfo() {
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        StatFs statFs = new StatFs(Environment.getDataDirectory().getAbsolutePath());
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        return new String[][]{
                {"品牌", emptyToUnknown(Build.BRAND)},
                {"型号", emptyToUnknown(Build.MODEL)},
                {"设备代号", emptyToUnknown(Build.DEVICE)},
                {"Android 版本", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"},
                {"内核版本", emptyToUnknown(System.getProperty("os.version"))},
                {"Build 号", emptyToUnknown(Build.DISPLAY)},
                {"总内存", formatSize(memoryInfo.totalMem)},
                {"可用内存", formatSize(memoryInfo.availMem)},
                {"存储可用", formatSize(statFs.getAvailableBytes())},
                {"屏幕", metrics.widthPixels + " × " + metrics.heightPixels},
                {"DPI", metrics.densityDpi + " (" + metrics.density + "x)"},
                {"ANDROID_ID", emptyToUnknown(Settings.Secure.getString(
                        getContentResolver(), Settings.Secure.ANDROID_ID))},
        };
    }
    private MaterialTextView createRowLabel(String text) {
        MaterialTextView label = new MaterialTextView(this);
        label.setText(text);
        label.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        label.setTextColor(MaterialColors.getColor(
                label, com.google.android.material.R.attr.colorOnSurfaceVariant));
        return label;
    }
    private MaterialTextView createRowValue(String text) {
        MaterialTextView value = new MaterialTextView(this);
        value.setText(text);
        value.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        value.setTextColor(MaterialColors.getColor(
                value, com.google.android.material.R.attr.colorOnSurface));
        value.setTypeface(Typeface.DEFAULT_BOLD);
        return value;
    }
    private static String emptyToUnknown(String value) {
        return TextUtils.isEmpty(value) ? "未知" : value;
    }
    private static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "未知";
        }
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = bytes;
        int index = 0;
        while (value >= 1024 && index < units.length - 1) {
            value /= 1024;
            index++;
        }
        return String.format("%.1f %s", value, units[index]);
    }
    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
