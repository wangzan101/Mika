package com.wangzan101.mika.core.shell

/**
 * 助手进程启动时的降权。无线调试路径的助手由 adbd 以 shell(uid 2000)执行,而 Shizuku 的 server 由 root 启动时,
 * Shizuku.newProcess 起出来的助手是 uid 0。真机 A/B 已证明 root 身份会让「在 Mika 自己的屏上打开别的 App」到不了前台
 * (自检第 5 项 app_not_in_front,见 plan/device-test-report.md 第五节),其余能力不受影响。
 * 所以助手一进进程就把自己降到 shell(2000),让 Shizuku(root) 路径与无线调试路径在系统眼里完全一致。
 *
 * 降权必须在起 ShellService、交付 Binder 之前完成;失败就明确报错、让交接失败,
 * 不许静默以 root 继续——那会得到「看着能用、实际开不了 App」的半残状态。
 */
object HelperPrivileges {
    /** 降权要落到的 uid/gid:与无线调试路径(adbd exec)一致。 */
    const val SHELL_UID = HelperHandoff.SHELL_UID

    /**
     * 实际执行 setgroups/setgid/setuid 的底层。真机用 android.system.Os 的静态方法;
     * core 层不依赖 Android,单测传假实现覆盖成功与失败路径。
     */
    interface Drop {
        /** 当前真实 uid(降权后用来核对是否真的到了 [SHELL_UID])。 */
        fun currentUid(): Int
        /** 清空补充组,防止带着 root 的组降权。 */
        fun setGroups(gids: IntArray)
        fun setGid(gid: Int)
        fun setUid(uid: Int)
    }

    /** 降权失败。调用方要把它当成「这次启动作废」:记日志、退出,绝不以 root 继续跑。 */
    class DropFailedException(cause: Throwable) :
        Exception("cannot drop helper from root to shell uid $SHELL_UID", cause)

    /**
     * [uid] 为 0(root)时把自己降到 [SHELL_UID],否则什么都不做(无线调试/USB 路径本来就是 shell)。
     * 顺序:先清补充组、再降 gid、最后降 uid(setuid 之后就不能再动组了)。
     * 任何一步抛错、或降完当前 uid 还不是 shell,都抛 [DropFailedException]。
     */
    fun dropToShellIfRoot(uid: Int, drop: Drop) {
        if (uid != 0) return
        try {
            drop.setGroups(IntArray(0))
            drop.setGid(SHELL_UID)
            drop.setUid(SHELL_UID)
        } catch (t: Throwable) {
            throw DropFailedException(t)
        }
        if (drop.currentUid() != SHELL_UID) {
            throw DropFailedException(IllegalStateException("uid after drop is ${drop.currentUid()}, expected $SHELL_UID"))
        }
    }
}
