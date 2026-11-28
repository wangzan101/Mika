package com.wangzan101.mika.device

import com.wangzan101.mika.core.device.CredentialKind

/** 锁屏识别诊断只保留系统控件的技术属性;资源名和类名也限制字符与长度,不接纳屏上文字。 */
internal object KeyguardDiagnostics {
    fun name(value: CharSequence?): String = value?.toString().orEmpty().take(160).map { c ->
        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "_.\$:/-") c else '_'
    }.joinToString("").ifEmpty { "unknown" }

    fun window(
        index: Int,
        focused: Boolean,
        passwordNodes: Int,
        digits: Int,
        padKeys: Int,
        pinLayout: Boolean,
    ): String = "window[index=$index,focused=$focused,password_nodes=$passwordNodes,digits=$digits," +
        "pad_keys=$padKeys,pin_layout=$pinLayout]"

    /** 凭据页取自哪份列表,以及各份列表的大小:分辨「整份窗口列表为空」「屏号不对」「活动窗口不是 SystemUI」。 */
    fun source(source: String, displayIds: List<Int>, legacyWindows: Int, activeRootPackage: CharSequence?): String =
        "keyguard_source[source=$source,display_ids=${displayIds.joinToString("/").ifEmpty { "none" }}," +
            "legacy_windows=$legacyWindows,active_root=${activeRootPackage?.let(::name) ?: "none"}]"

    fun windowInfo(
        index: Int,
        type: Int,
        packageName: CharSequence?,
        focused: Boolean,
        active: Boolean,
    ): String = "window_info[index=$index,type=$type,package=${name(packageName)},focused=$focused,active=$active]"

    fun field(
        id: CharSequence?,
        className: CharSequence?,
        editable: Boolean,
        numeric: Boolean,
        untyped: Boolean,
        digitPad: Boolean,
        focused: Boolean,
        match: CredentialKind?,
    ): String = "field[id=${name(id)},class=${name(className)},editable=$editable,numeric=$numeric," +
        "untyped=$untyped,digit_pad=$digitPad,focused=$focused,match=${match?.name?.lowercase() ?: "none"}]"
}
