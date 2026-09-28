package com.kevin233.talpad.hook;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.divider.MaterialDivider;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;
import java.util.Locale;
public class ConfigActivity extends AppCompatActivity {
    private static final String[] SWATCH_COLORS = new String[]{
            "#6750A4", "#3B82F6", "#00897B", "#388E3C",
            "#F9A825", "#F57C00", "#D32F2F", "#D81B60"};
    private LinearLayout swatchRow;
    private TextInputLayout customColorInput;
    private TextInputEditText customColorEdit;
    private int selectedColor;
    private boolean applyingColor;
    private final android.os.Handler colorHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingColorRecreate;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiTheme.apply(this);
        setContentView(R.layout.activity_config);
        com.google.android.material.appbar.MaterialToolbar toolbar =
                findViewById(R.id.configToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("配置");
        }
        LinearLayout installRows = findViewById(R.id.installRows);
        addSwitchRow(installRows,
                "解除学习机系统安装限制",
                "放行任意应用安装：绕过安装白名单、未知来源与 V 型设备限制",
                Config.KEY_UNLOCK_INSTALL,
                Config.DEFAULT_UNLOCK_INSTALL);
        addDivider(installRows);
        addSwitchRow(installRows,
                "阻止用户中心检测环境并上报日志",
                "开启后用户中心的 root/框架/模拟器/调试等环境检测与设备上报日志全部屏蔽",
                Config.KEY_BLOCK_UC_ENV_DETECT,
                Config.DEFAULT_BLOCK_UC_ENV_DETECT);
        LinearLayout systemRows = findViewById(R.id.systemRows);
        addSwitchRow(systemRows,
                "恢复通知消息内容",
                "还原被隐藏的通知标题、正文、进度条和操作按钮",
                Config.KEY_RESTORE_NOTIFICATION,
                Config.DEFAULT_RESTORE_NOTIFICATION);
        addDivider(systemRows);
        addSwitchRow(systemRows,
                "阻止系统恢复默认壁纸",
                "防止系统在定制或开机时把壁纸重置成默认",
                Config.KEY_BLOCK_DEFAULT_WALLPAPER,
                Config.DEFAULT_BLOCK_DEFAULT_WALLPAPER);
        addDivider(systemRows);
        addSwitchRow(systemRows,
                "阻止系统恢复默认桌面",
                "防止每次回桌面时把默认桌面改回 TAL 自带桌面",
                Config.KEY_BLOCK_DEFAULT_LAUNCHER,
                Config.DEFAULT_BLOCK_DEFAULT_LAUNCHER);
        LinearLayout usageRows = findViewById(R.id.usageRows);
        addSwitchRow(usageRows,
                "自定义学习时长与应用使用",
                "开启后，按下面填写的时长代替真实统计",
                Config.KEY_CUSTOM_USAGE_ENABLED,
                Config.DEFAULT_CUSTOM_USAGE_ENABLED);
        addDivider(usageRows);
        addEditRow(usageRows,
                "每日学习时长（分钟）",
                "每天的学习时长显示为这个数字",
                Config.KEY_CUSTOM_STUDY_MINUTES,
                Config.DEFAULT_CUSTOM_STUDY_MINUTES,
                InputType.TYPE_CLASS_NUMBER,
                "例如：120",
                1);
        addDivider(usageRows);
        addSwitchRow(usageRows,
                "每日仅伪装一次学习时长",
                "开启：只有当天第一节课补发伪装值；关闭：每节课都补发",
                Config.KEY_STUDY_ONCE_PER_DAY,
                Config.DEFAULT_STUDY_ONCE_PER_DAY);
        addDivider(usageRows);
        addEditRow(usageRows,
                "应用使用时长",
                "每行一个：包名:分钟（空格或全角冒号也可以）",
                Config.KEY_CUSTOM_APP_USAGE,
                Config.DEFAULT_CUSTOM_APP_USAGE,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                "com.microsoft.emmx 6\ncom.tencent.mm:30",
                3);
        addDivider(usageRows);
        addSwitchRow(usageRows,
                "伪装学习时长上报（含未成年人保护）",
                "拦截学习服务的真实学习时长（xpad_study_duration）上报，"
                        + "替换为自定义学习时长；覆盖未成年人保护等应用",
                Config.KEY_BLOCK_STUDY_REPORT,
                Config.DEFAULT_BLOCK_STUDY_REPORT);
        addDivider(usageRows);
        addSwitchRow(usageRows,
                "禁用未成年人保护精细化管控",
                "让未成年人保护应用的精细化管控全部失效：所有应用均允许启动、"
                        + "不再下发或执行禁止使用指令",
                Config.KEY_BLOCK_MINOR_CONTROL,
                Config.DEFAULT_BLOCK_MINOR_CONTROL);
        addDivider(usageRows);
        addSwitchRow(usageRows,
                "限制上传 Root 痕迹日志",
                "让 backdoor 上报的日志看起来像未 Root 的正常设备",
                Config.KEY_BLOCK_ROOT_LOGS,
                Config.DEFAULT_BLOCK_ROOT_LOGS);
        buildAppearanceCard();
    }
    private void buildAppearanceCard() {
        MaterialButtonToggleGroup group = findViewById(R.id.colorModeGroup);
        MaterialButton dynamicButton = findViewById(R.id.modeDynamicButton);
        MaterialButton customButton = findViewById(R.id.modeCustomButton);
        swatchRow = findViewById(R.id.swatchRow);
        customColorInput = findViewById(R.id.customColorInput);
        customColorEdit = findViewById(R.id.customColorEdit);
        boolean customMode = UiTheme.MODE_CUSTOM.equals(ConfigStore.getString(
                Config.KEY_UI_COLOR_MODE, Config.DEFAULT_UI_COLOR_MODE));
        dynamicButton.setChecked(!customMode);
        customButton.setChecked(customMode);
        String hex = ConfigStore.getString(
                Config.KEY_UI_CUSTOM_COLOR, Config.DEFAULT_UI_CUSTOM_COLOR);
        selectedColor = UiTheme.parseColor(
                hex, Color.parseColor(Config.DEFAULT_UI_CUSTOM_COLOR));
        updateAppearanceVisibility(customMode);
        buildSwatches();
        customColorEdit.setText(hex);
        customColorEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
            @Override
            public void afterTextChanged(Editable s) {
                colorHandler.removeCallbacks(pendingColorRecreate);
                if (applyingColor) {
                    return;
                }
                final String text = s.toString().trim();
                pendingColorRecreate = () -> {
                    int color = UiTheme.parseColor(text, -1);
                    if (color == -1 || text.length() != 7) {
                        return;
                    }
                    ConfigStore.setString(
                            Config.KEY_UI_CUSTOM_COLOR,
                            text.toUpperCase(Locale.ROOT));
                    selectedColor = color;
                    buildSwatches();
                    recreate();
                };
                colorHandler.postDelayed(pendingColorRecreate, 400);
            }
        });
        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            boolean nowCustom = checkedId == R.id.modeCustomButton;
            ConfigStore.setString(Config.KEY_UI_COLOR_MODE,
                    nowCustom ? UiTheme.MODE_CUSTOM : UiTheme.MODE_DYNAMIC);
            recreate();
        });
    }
    private void updateAppearanceVisibility(boolean customMode) {
        swatchRow.setVisibility(customMode ? View.VISIBLE : View.GONE);
        customColorInput.setVisibility(customMode ? View.VISIBLE : View.GONE);
    }
    private void buildSwatches() {
        swatchRow.removeAllViews();
        int size = dp(44);
        LinearLayout.LayoutParams swatchLp = new LinearLayout.LayoutParams(size, size);
        swatchLp.leftMargin = dp(4);
        swatchLp.rightMargin = dp(4);
        swatchLp.gravity = Gravity.CENTER_VERTICAL;
        for (String hex : SWATCH_COLORS) {
            final int color = UiTheme.parseColor(
                    hex, Color.parseColor(Config.DEFAULT_UI_CUSTOM_COLOR));
            boolean isSelected = color == selectedColor;
            FrameLayout swatch = new FrameLayout(this);
            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(color);
            if (isSelected) {
                background.setStroke(dp(3), MaterialColors.getColor(
                        swatch, com.google.android.material.R.attr.colorPrimary));
            }
            swatch.setBackground(background);
            GradientDrawable rippleMask = new GradientDrawable();
            rippleMask.setShape(GradientDrawable.OVAL);
            rippleMask.setColor(Color.WHITE);
            swatch.setForeground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                    null, rippleMask));
            if (isSelected) {
                MaterialTextView check = new MaterialTextView(this);
                check.setText("\u2713");
                check.setTextSize(20);
                check.setGravity(Gravity.CENTER);
                check.setTextColor(Color.WHITE);
                check.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                swatch.addView(check, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
            swatch.setContentDescription("自定义颜色 " + hex);
            swatch.setOnClickListener(v -> applySwatchColor(color, hex));
            swatchRow.addView(swatch, swatchLp);
        }
    }
    private void applySwatchColor(int color, String hex) {
        colorHandler.removeCallbacks(pendingColorRecreate);
        String normalized = hex.toUpperCase(Locale.ROOT);
        ConfigStore.setString(Config.KEY_UI_CUSTOM_COLOR, normalized);
        selectedColor = color;
        applyingColor = true;
        customColorEdit.setText(normalized);
        applyingColor = false;
        buildSwatches();
        recreate();
    }
    private void addSwitchRow(LinearLayout parent, String titleText, String descText,
                              String key, boolean defaultValue) {
        View row = getLayoutInflater().inflate(R.layout.row_switch, parent, false);
        MaterialTextView title = row.findViewById(R.id.rowTitle);
        title.setText(titleText);
        MaterialTextView desc = row.findViewById(R.id.rowDesc);
        desc.setText(descText);
        MaterialSwitch switchView = row.findViewById(R.id.rowSwitch);
        switchView.setChecked(ConfigStore.getBoolean(key, defaultValue));
        switchView.setOnCheckedChangeListener((buttonView, isChecked) ->
                ConfigStore.setBoolean(key, isChecked));
        parent.addView(row);
    }
    private void addEditRow(LinearLayout parent, String titleText, String descText,
                            String key, String defaultValue, int inputType,
                            String hint, int minLines) {
        View row = getLayoutInflater().inflate(R.layout.row_edit, parent, false);
        MaterialTextView title = row.findViewById(R.id.rowTitle);
        title.setText(titleText);
        MaterialTextView desc = row.findViewById(R.id.rowDesc);
        desc.setText(descText);
        TextInputLayout input = row.findViewById(R.id.rowInput);
        input.setHint(hint);
        TextInputEditText editText = row.findViewById(R.id.rowEdit);
        editText.setInputType(inputType);
        editText.setMinLines(minLines);
        editText.setText(ConfigStore.getString(key, defaultValue));
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
            @Override
            public void afterTextChanged(Editable s) {
                ConfigStore.setString(key, s.toString());
            }
        });
        parent.addView(row);
    }
    private void addDivider(LinearLayout parent) {
        MaterialDivider divider = new MaterialDivider(this);
        parent.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }
    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
