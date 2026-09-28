package com.kevin233.talpad.hook;
import android.app.Application;
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        SdcardLog.init(this);
        ConfigStore.init(this);
    }
}
