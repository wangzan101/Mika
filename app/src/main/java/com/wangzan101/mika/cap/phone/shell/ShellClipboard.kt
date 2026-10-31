package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import java.lang.reflect.Method

/**
 * shell 进程直接访问系统剪贴板。部分 ROM 的 ClipboardManager 会先读 Settings,
 * 但 app_process 没有普通 App 的进程登记,不能经 ActivityThread 获取 ContentProvider。
 * Binder 仍按包名、uid、用户和设备检查权限;这里只避开 Java 包装层额外的 Settings 查询。
 */
@SuppressLint("PrivateApi")
internal class ShellClipboard(private val context: Context) {
    private val manager: ClipboardManager by lazy {
        ClipboardManager::class.java.getDeclaredConstructor(Context::class.java, Handler::class.java)
            .apply { isAccessible = true }.newInstance(context, Handler(Looper.getMainLooper() ?: Looper.myLooper()!!))
    }
    private val service: Any by lazy {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "clipboard") as IBinder
        Class.forName("android.content.IClipboard\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }
    private val getClip by lazy { method("getPrimaryClip") }
    private val getDescription by lazy { method("getPrimaryClipDescription") }
    private val hasClip by lazy { method("hasPrimaryClip") }
    private val callerLabel by lazy {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(ShellContext.SHELL_PACKAGE, 0)).toString()
    }

    val primaryClip: ClipData? get() = call(getClip) as ClipData?
    val primaryClipDescription: ClipDescription? get() = call(getDescription) as ClipDescription?
    fun hasPrimaryClip(): Boolean = call(hasClip) as Boolean
    // 写入仍走框架原来的 ClipData 准备和清空流程,只替换有额外 Settings 查询的读取路径。
    fun setPrimaryClip(clip: ClipData) = manager.setPrimaryClip(clip)
    fun clearPrimaryClip() = manager.clearPrimaryClip()

    private fun method(name: String): Method {
        val type = Class.forName("android.content.IClipboard")
        if (name == "getPrimaryClip") {
            try {
                // vivo Android 16: pkg, appName, privacyStack, attributionTag, userId, deviceId, isUserOperation。
                return type.getMethod(name, String::class.java, String::class.java, String::class.java,
                    String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            } catch (_: NoSuchMethodException) {
                // 标准 Android 的接口继续按下面的签名查找。
            }
        }
        val types = listOf(String::class.java, String::class.java, Int::class.javaPrimitiveType!!)
        return try {
            // Android 14+ 多一个 deviceId;较早版本只有包名、attributionTag 和 userId。
            type.getMethod(name, *(types + Int::class.javaPrimitiveType!!).toTypedArray())
        } catch (_: NoSuchMethodException) {
            type.getMethod(name, *types.toTypedArray())
        }
    }

    private fun call(method: Method): Any? {
        if (method.parameterTypes.size == 7) {
            // 后台保存/还原不是用户点「粘贴」,不伪造用户操作;隐私栈与框架默认值一样为空。
            return method.invoke(service, ShellContext.SHELL_PACKAGE, callerLabel, "", null,
                Process.myUid() / 100_000, 0, false)
        }
        val args = mutableListOf<Any?>().apply {
            add(ShellContext.SHELL_PACKAGE)
            add(null)
            add(Process.myUid() / 100_000)
            // ShellContext 是默认设备的系统上下文,与原 ClipboardManager 使用的设备一致。
            if (method.parameterTypes.size == size + 1) add(0)
        }
        return method.invoke(service, *args.toTypedArray())
    }
}
