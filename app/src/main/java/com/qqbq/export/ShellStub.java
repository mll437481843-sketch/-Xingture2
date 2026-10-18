package com.qqbq.export;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.RemoteException;

/**
 * IShell 的 Binder 实现骨架（等价于 AIDL 生成的 Stub + Proxy）。
 * 事务号与 Shizuku 服务端约定一致：16777114 = destroy（保留号），1/2/3 = 自己的方法。
 */
public abstract class ShellStub extends Binder implements IShell {

    private static final String DESCRIPTOR = "com.qqbq.export.IShell";

    private static final int TX_DESTROY = 16777114;
    private static final int TX_WHOAMI = 1;
    private static final int TX_EXEC = 2;
    private static final int TX_OPEN_READ = 3;

    public ShellStub() {
        attachInterface(this, DESCRIPTOR);
    }

    public static IShell asInterface(IBinder binder) {
        if (binder == null) {
            return null;
        }
        IInterface local = binder.queryLocalInterface(DESCRIPTOR);
        if (local instanceof IShell) {
            return (IShell) local;
        }
        return new Proxy(binder);
    }

    @Override
    public IBinder asBinder() {
        return this;
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == TX_DESTROY) {
            data.enforceInterface(DESCRIPTOR);
            destroy();
            if (reply != null) {
                reply.writeNoException();
            }
            return true;
        }
        if (code == TX_WHOAMI) {
            data.enforceInterface(DESCRIPTOR);
            String result = whoami();
            if (reply != null) {
                reply.writeNoException();
                reply.writeString(result);
            }
            return true;
        }
        if (code == TX_EXEC) {
            data.enforceInterface(DESCRIPTOR);
            String cmd = data.readString();
            String result = exec(cmd);
            if (reply != null) {
                reply.writeNoException();
                reply.writeString(result);
            }
            return true;
        }
        if (code == TX_OPEN_READ) {
            data.enforceInterface(DESCRIPTOR);
            String path = data.readString();
            ParcelFileDescriptor fd = openRead(path);
            if (reply != null) {
                reply.writeNoException();
                if (fd != null) {
                    reply.writeInt(1);
                    fd.writeToParcel(reply, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                } else {
                    reply.writeInt(0);
                }
            }
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }

    private static final class Proxy implements IShell {

        private final IBinder remote;

        Proxy(IBinder remote) {
            this.remote = remote;
        }

        @Override
        public IBinder asBinder() {
            return remote;
        }

        @Override
        public void destroy() {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                remote.transact(TX_DESTROY, data, reply, 0);
                reply.readException();
            } catch (RemoteException ignored) {
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        @Override
        public String whoami() {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                remote.transact(TX_WHOAMI, data, reply, 0);
                reply.readException();
                return reply.readString();
            } catch (RemoteException e) {
                return "remote-exception";
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        @Override
        public String exec(String cmd) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeString(cmd);
                remote.transact(TX_EXEC, data, reply, 0);
                reply.readException();
                return reply.readString();
            } catch (RemoteException e) {
                return "ERR remote-exception";
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        @Override
        public ParcelFileDescriptor openRead(String path) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeString(path);
                remote.transact(TX_OPEN_READ, data, reply, 0);
                reply.readException();
                if (reply.readInt() == 0) {
                    return null;
                }
                return ParcelFileDescriptor.CREATOR.createFromParcel(reply);
            } catch (RemoteException e) {
                return null;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
    }
}
