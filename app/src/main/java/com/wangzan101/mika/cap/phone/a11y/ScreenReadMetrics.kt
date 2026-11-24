package com.wangzan101.mika.cap.phone.a11y

import android.util.Log

/** 一次读屏中几个可分别定位的耗时区间。 */
internal data class ScreenReadMetrics(
    val windowsMs: Long,
    val rootsMs: Long,
    val propertiesMs: Long,
    val childrenMs: Long,
    val totalMs: Long,
    val nodes: Int,
    val maxDepth: Int,
) {
    fun shouldLog(detailedTraces: Boolean): Boolean = detailedTraces || totalMs >= SLOW_READ_MS

    companion object {
        const val SLOW_READ_MS = 100L
    }
}

internal object ScreenReadLog {
    fun record(metrics: ScreenReadMetrics, detailedTraces: Boolean) {
        if (!metrics.shouldLog(detailedTraces)) return
        Log.w(
            "MikaScreenRead",
            "total=${metrics.totalMs}ms windows=${metrics.windowsMs}ms roots=${metrics.rootsMs}ms " +
                "props=${metrics.propertiesMs}ms children=${metrics.childrenMs}ms " +
                "nodes=${metrics.nodes} depth=${metrics.maxDepth}",
        )
    }
}