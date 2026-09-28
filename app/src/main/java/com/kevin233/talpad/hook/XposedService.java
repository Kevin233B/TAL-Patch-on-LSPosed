package com.kevin233.talpad.hook;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import java.util.HashMap;
import java.util.Map;
import io.github.libxposed.service.IXposedService;
public final class XposedService {
    private final IXposedService mService;
    private final Map<String, RemotePreferences> mRemotePrefs = new HashMap<>();
    XposedService(IXposedService service) {
        mService = service;
    }
    IBinder asBinder() {
        return mService.asBinder();
    }
    public int getApiVersion() {
        try {
            return mService.getApiVersion();
        } catch (RemoteException e) {
            throw new IllegalStateException("xposed service error", e);
        }
    }
    public long getFrameworkProperties() {
        try {
            return mService.getFrameworkProperties();
        } catch (RemoteException e) {
            throw new IllegalStateException("xposed service error", e);
        }
    }
    public synchronized SharedPreferences getRemotePreferences(String group) {
        RemotePreferences prefs = mRemotePrefs.get(group);
        if (prefs == null) {
            prefs = RemotePreferences.newInstance(this, group);
            mRemotePrefs.put(group, prefs);
        }
        return prefs;
    }
    public synchronized void deleteRemotePreferences(String group) {
        RemotePreferences prefs = mRemotePrefs.remove(group);
        if (prefs != null) {
            prefs.onDelete();
        }
        try {
            mService.deleteRemotePreferences(group);
        } catch (RemoteException e) {
            throw new IllegalStateException("xposed service error", e);
        }
    }
    Bundle requestRemotePreferences(String group) {
        try {
            return mService.requestRemotePreferences(group);
        } catch (RemoteException e) {
            throw new IllegalStateException("xposed service error", e);
        }
    }
    void updateRemotePreferences(String group, Bundle diff) {
        try {
            mService.updateRemotePreferences(group, diff);
        } catch (RemoteException e) {
            throw new IllegalStateException("xposed service error", e);
        }
    }
    public ParcelFileDescriptor openRemoteFile(String name) {
        try {
            return mService.openRemoteFile(name);
        } catch (RemoteException e) {
            throw new IllegalStateException("xposed service error", e);
        }
    }
}
