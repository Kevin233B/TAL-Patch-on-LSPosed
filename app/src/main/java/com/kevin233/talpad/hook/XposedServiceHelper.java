package com.kevin233.talpad.hook;
import android.os.IBinder;
import android.util.Log;
import java.util.HashSet;
import java.util.Set;
import io.github.libxposed.service.IXposedService;
public final class XposedServiceHelper {
    private static final String TAG = "TAL-Patch-Xposed";
    private static final Set<XposedService> sCache = new HashSet<>();
    private static OnServiceListener sListener;
    public interface OnServiceListener {
        void onServiceBind(XposedService service);
        void onServiceDied(XposedService service);
    }
    private XposedServiceHelper() {
    }
    static void onBinderReceived(IBinder binder) {
        if (binder == null) {
            return;
        }
        synchronized (sCache) {
            try {
                XposedService service =
                        new XposedService(IXposedService.Stub.asInterface(binder));
                if (sListener == null) {
                    sCache.add(service);
                } else {
                    binder.linkToDeath(() -> sListener.onServiceDied(service), 0);
                    sListener.onServiceBind(service);
                }
            } catch (Throwable t) {
                Log.e(TAG, "onBinderReceived failed", t);
            }
        }
    }
    public static void registerListener(OnServiceListener listener) {
        synchronized (sCache) {
            sListener = listener;
            for (XposedService service : sCache) {
                try {
                    service.asBinder().linkToDeath(
                            () -> sListener.onServiceDied(service), 0);
                    sListener.onServiceBind(service);
                } catch (Throwable t) {
                    Log.e(TAG, "registerListener failed", t);
                }
            }
            sCache.clear();
        }
    }
}
