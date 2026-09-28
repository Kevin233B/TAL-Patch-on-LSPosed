package io.github.libxposed.service;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;
public interface IXposedService extends IInterface {
    String AUTHORITY_SUFFIX = ".XposedService";
    String SEND_BINDER = "SendBinder";
    String DESCRIPTOR = "io.github.libxposed.service.IXposedService";
    int API_101 = 101;
    int LIB_API = 101;
    long PROP_CAP_SYSTEM = 1L;
    long PROP_CAP_REMOTE = 1L << 1;
    long PROP_RT_API_PROTECTION = 1L << 2;
    int getApiVersion() throws RemoteException;
    String getFrameworkName() throws RemoteException;
    String getFrameworkVersion() throws RemoteException;
    long getFrameworkVersionCode() throws RemoteException;
    long getFrameworkProperties() throws RemoteException;
    Bundle requestRemotePreferences(String group) throws RemoteException;
    void updateRemotePreferences(String group, Bundle diff) throws RemoteException;
    void deleteRemotePreferences(String group) throws RemoteException;
    ParcelFileDescriptor openRemoteFile(String name) throws RemoteException;
    abstract class Stub extends android.os.Binder implements IXposedService {
        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }
        public static IXposedService asInterface(IBinder binder) {
            if (binder == null) {
                return null;
            }
            IInterface iin = binder.queryLocalInterface(DESCRIPTOR);
            if (iin != null && iin instanceof IXposedService) {
                return (IXposedService) iin;
            }
            return new Proxy(binder);
        }
        @Override
        public IBinder asBinder() {
            return this;
        }
    }
    final class Proxy implements IXposedService {
        private static final String TAG = "TAL-Patch-Xposed";
        private final IBinder mRemote;
        private final int mApiTx;
        private final int mPrefsTxBase;
        Proxy(IBinder remote) {
            mRemote = remote;
            if (probeInt(1) >= 0) {
                mApiTx = 1;
                mPrefsTxBase = 20;
                Log.i(TAG, "IXposedService protocol: official (api=1, prefs=20)");
            } else {
                mApiTx = 2;
                mPrefsTxBase = 21;
                Log.i(TAG, "IXposedService protocol: shifted (api=2, prefs=21)");
            }
        }
        private int probeInt(int code) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                if (!mRemote.transact(code, data, reply, 0)) {
                    return -1;
                }
                reply.readException();
                return reply.readInt();
            } catch (Throwable t) {
                return -1;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
        @Override
        public IBinder asBinder() {
            return mRemote;
        }
        @Override
        public int getApiVersion() throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                if (!mRemote.transact(mApiTx, data, reply, 0)) {
                    throw new RemoteException("getApiVersion not implemented");
                }
                reply.readException();
                return reply.readInt();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
        @Override
        public String getFrameworkName() throws RemoteException {
            return readString(mApiTx + 1, "getFrameworkName");
        }
        @Override
        public String getFrameworkVersion() throws RemoteException {
            return readString(mApiTx + 2, "getFrameworkVersion");
        }
        @Override
        public long getFrameworkVersionCode() throws RemoteException {
            return readLong(mApiTx + 3, "getFrameworkVersionCode");
        }
        @Override
        public long getFrameworkProperties() throws RemoteException {
            return readLong(mApiTx + 4, "getFrameworkProperties");
        }
        @Override
        public Bundle requestRemotePreferences(String group) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeString(group);
                if (!mRemote.transact(mPrefsTxBase, data, reply, 0)) {
                    throw new RemoteException("requestRemotePreferences not implemented");
                }
                reply.readException();
                Bundle result = null;
                if (reply.readInt() != 0) {
                    result = Bundle.CREATOR.createFromParcel(reply);
                }
                return result;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
        @Override
        public void updateRemotePreferences(String group, Bundle diff)
                throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeString(group);
                data.writeTypedObject(diff, 0);
                if (!mRemote.transact(mPrefsTxBase + 1, data, reply, 0)) {
                    throw new RemoteException("updateRemotePreferences not implemented");
                }
                reply.readException();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
            @Override
            public void deleteRemotePreferences(String group) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeString(group);
                if (!mRemote.transact(mPrefsTxBase + 2, data, reply, 0)) {
                    throw new RemoteException("deleteRemotePreferences not implemented");
                }
                reply.readException();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
        private String readString(int code, String name) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                if (!mRemote.transact(code, data, reply, 0)) {
                    throw new RemoteException(name + " not implemented");
                }
                reply.readException();
                return reply.readString();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
        private long readLong(int code, String name) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                if (!mRemote.transact(code, data, reply, 0)) {
                    throw new RemoteException(name + " not implemented");
                }
                reply.readException();
                return reply.readLong();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
        @Override
        public ParcelFileDescriptor openRemoteFile(String name) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeString(name);
                if (!mRemote.transact(mPrefsTxBase + 11, data, reply, 0)) {
                    throw new RemoteException("openRemoteFile not implemented");
                }
                reply.readException();
                ParcelFileDescriptor result = null;
                if (reply.readInt() != 0) {
                    result = ParcelFileDescriptor.CREATOR.createFromParcel(reply);
                }
                return result;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
    }
}
