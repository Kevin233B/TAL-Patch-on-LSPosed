package com.kevin233.talpad.hook;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
@SuppressWarnings("unchecked")
public final class RemotePreferences implements SharedPreferences {
    private static final String TAG = "TAL-Patch-RemotePrefs";
    private static final Object CONTENT = new Object();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private final XposedService mService;
    private final String mGroup;
    private final Map<OnSharedPreferenceChangeListener, Object> mListeners =
            Collections.synchronizedMap(new WeakHashMap<>());
    private volatile Map<String, Object> mMap;
    private RemotePreferences(XposedService service, String group) {
        mService = service;
        mGroup = group;
    }
    static RemotePreferences newInstance(XposedService service, String group) {
        Bundle output = service.requestRemotePreferences(group);
        RemotePreferences prefs = new RemotePreferences(service, group);
        Object map = output == null ? null : output.getSerializable("map");
        prefs.mMap = map instanceof Map
                ? Collections.unmodifiableMap((Map<String, Object>) map)
                : Collections.emptyMap();
        return prefs;
    }
    void onDelete() {
        mMap = Collections.emptyMap();
    }
    @Override
    public Map<String, ?> getAll() {
        return new TreeMap<>(mMap);
    }
    @Override
    public String getString(String key, String defValue) {
        Object v = mMap.get(key);
        return v instanceof String ? (String) v : defValue;
    }
    @Override
    public Set<String> getStringSet(String key, Set<String> defValues) {
        Object v = mMap.get(key);
        return v instanceof Set ? (Set<String>) v : defValues;
    }
    @Override
    public int getInt(String key, int defValue) {
        Object v = mMap.get(key);
        return v instanceof Integer ? (Integer) v : defValue;
    }
    @Override
    public long getLong(String key, long defValue) {
        Object v = mMap.get(key);
        return v instanceof Long ? (Long) v : defValue;
    }
    @Override
    public float getFloat(String key, float defValue) {
        Object v = mMap.get(key);
        return v instanceof Float ? (Float) v : defValue;
    }
    @Override
    public boolean getBoolean(String key, boolean defValue) {
        Object v = mMap.get(key);
        return v instanceof Boolean ? (Boolean) v : defValue;
    }
    @Override
    public boolean contains(String key) {
        return mMap.containsKey(key);
    }
    @Override
    public void registerOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {
        mListeners.put(listener, CONTENT);
    }
    @Override
    public void unregisterOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {
        mListeners.remove(listener);
    }
    @Override
    public Editor edit() {
        return new Editor();
    }
    public class Editor implements SharedPreferences.Editor {
        private final Set<String> mDelete = new HashSet<>();
        private final Map<String, Object> mPut = new HashMap<>();
        private boolean mClear;
        @Override
        public SharedPreferences.Editor putString(String key, String value) {
            if (value == null) {
                remove(key);
            } else {
                mDelete.remove(key);
                mPut.put(key, value);
            }
            return this;
        }
        @Override
        public SharedPreferences.Editor putStringSet(String key, Set<String> values) {
            if (values == null) {
                remove(key);
            } else {
                mDelete.remove(key);
                mPut.put(key, values);
            }
            return this;
        }
        @Override
        public SharedPreferences.Editor putInt(String key, int value) {
            mDelete.remove(key);
            mPut.put(key, value);
            return this;
        }
        @Override
        public SharedPreferences.Editor putLong(String key, long value) {
            mDelete.remove(key);
            mPut.put(key, value);
            return this;
        }
        @Override
        public SharedPreferences.Editor putFloat(String key, float value) {
            mDelete.remove(key);
            mPut.put(key, value);
            return this;
        }
        @Override
        public SharedPreferences.Editor putBoolean(String key, boolean value) {
            mDelete.remove(key);
            mPut.put(key, value);
            return this;
        }
        @Override
        public SharedPreferences.Editor remove(String key) {
            mDelete.add(key);
            mPut.remove(key);
            return this;
        }
        @Override
        public SharedPreferences.Editor clear() {
            mClear = true;
            mDelete.clear();
            mPut.clear();
            return this;
        }
        private void doUpdate() {
            synchronized (RemotePreferences.this) {
                Map<String, Object> newMap = new HashMap<>(mMap);
                if (mClear) {
                    newMap.clear();
                }
                for (String key : mDelete) {
                    newMap.remove(key);
                }
                newMap.putAll(mPut);
                mMap = Collections.unmodifiableMap(newMap);
            }
            List<OnSharedPreferenceChangeListener> listeners;
            synchronized (mListeners) {
                listeners = new ArrayList<>(mListeners.keySet());
            }
            for (OnSharedPreferenceChangeListener listener : listeners) {
                if (mClear) {
                    listener.onSharedPreferenceChanged(RemotePreferences.this, null);
                }
                for (String key : mDelete) {
                    listener.onSharedPreferenceChanged(RemotePreferences.this, key);
                }
                for (String key : mPut.keySet()) {
                    listener.onSharedPreferenceChanged(RemotePreferences.this, key);
                }
            }
        }
        private Bundle buildCommitBundle() {
            if (!mClear && mDelete.isEmpty() && mPut.isEmpty()) {
                return null;
            }
            Bundle bundle = new Bundle();
            bundle.putBoolean("clear", mClear);
            bundle.putSerializable("delete", new HashSet<>(mDelete));
            bundle.putSerializable("put", new HashMap<>(mPut));
            return bundle;
        }
        private boolean doCommit(Bundle bundle) {
            if (bundle != null) {
                try {
                    mService.updateRemotePreferences(mGroup, bundle);
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to commit changes to framework", t);
                    return false;
                }
            }
            return true;
        }
        @Override
        public boolean commit() {
            Bundle bundle = buildCommitBundle();
            if (bundle == null) {
                return true;
            }
            doUpdate();
            return doCommit(bundle);
        }
        @Override
        public void apply() {
            Bundle bundle = buildCommitBundle();
            if (bundle == null) {
                return;
            }
            doUpdate();
            EXECUTOR.execute(() -> doCommit(bundle));
        }
    }
}
