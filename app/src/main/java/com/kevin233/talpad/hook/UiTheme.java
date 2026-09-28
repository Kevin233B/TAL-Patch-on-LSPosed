package com.kevin233.talpad.hook;
import android.app.Activity;
import android.graphics.Color;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;
public final class UiTheme {
    public static final String MODE_DYNAMIC = "dynamic";
    public static final String MODE_CUSTOM = "custom";
    private UiTheme() {
    }
    public static void apply(Activity activity) {
        DynamicColorsOptions.Builder builder = new DynamicColorsOptions.Builder();
        if (MODE_CUSTOM.equals(ConfigStore.getString(
                Config.KEY_UI_COLOR_MODE, Config.DEFAULT_UI_COLOR_MODE))) {
            String hex = ConfigStore.getString(
                    Config.KEY_UI_CUSTOM_COLOR, Config.DEFAULT_UI_CUSTOM_COLOR);
            builder.setContentBasedSource(
                    parseColor(hex, Color.parseColor(Config.DEFAULT_UI_CUSTOM_COLOR)));
        }
        DynamicColors.applyToActivityIfAvailable(activity, builder.build());
    }
    public static int parseColor(String hex, int fallback) {
        try {
            return Color.parseColor(hex.trim());
        } catch (Throwable t) {
            return fallback;
        }
    }
}
