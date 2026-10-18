package com.qqbq.export;

import android.os.IInterface;
import android.os.ParcelFileDescriptor;

/**
 * 跑在 Shizuku 的 shell 进程里的服务接口。
 *
 * 这里手写 Binder 而不是用 AIDL：AIDL 编译器（aidl 工具）没有 arm64 版本，
 * 手写等价代码后，这个工程在「GitHub Actions / Docker / Android Studio / 手机本地」都能编。
 * 真正的协议常量在 ShellStub 里。
 */
public interface IShell extends IInterface {

    void destroy();

    String whoami();

    String exec(String cmd);

    ParcelFileDescriptor openRead(String path);
}
