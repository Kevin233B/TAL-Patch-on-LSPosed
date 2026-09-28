package com.kevin233.talpad.hook;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
public class XposedServiceProvider extends ContentProvider {
    private static final String TAG = "TAL-Patch-Xposed";
    @Override
    public boolean onCreate() {
        return true;
    }
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }
    @Override
    public String getType(Uri uri) {
        return null;
    }
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }
    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (io.github.libxposed.service.IXposedService.SEND_BINDER.equals(method)
                && extras != null) {
            IBinder binder = extras.getBinder("binder");
            if (binder != null) {
                Log.i(TAG, "xposed service binder received");
                ConfigStore.appendDiag("provider: SendBinder received");
                XposedServiceHelper.onBinderReceived(binder);
            } else {
                ConfigStore.appendDiag("provider: SendBinder with null binder");
            }
            return new Bundle();
        }
        if (method != null) {
            ConfigStore.appendDiag("provider: call " + method);
        }
        return null;
    }
}
