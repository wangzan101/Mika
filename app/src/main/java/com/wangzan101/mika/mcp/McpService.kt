package com.wangzan101.mika.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.mcp.McpHttpServer
import com.wangzan101.mika.core.mcp.McpTaskProtocol
import com.wangzan101.mika.ui.MainActivity
import java.security.SecureRandom

/** 用户亲手打开的 MCP 连接,有常驻通知,不用时可从通知或设置里关闭。 */
class McpService : Service() {
    private var server: McpHttpServer? = null
    private var failure: String? = null
    private val app get() = application as MikaApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.mcp_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0, Intent(this, McpService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        startForeground(NOTIFICATION, Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.mcp_notification))
            .setContentText(getString(R.string.mcp_subtitle))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.mcp_turn_off), stop).build())
            .build())
        if (server == null) {
            try {
                val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                server = McpHttpServer(McpTaskProtocol(app.mcpTasks, BuildConfig.VERSION_NAME), token)
                app.mcp.opened(token)
            } catch (e: Exception) {
                failure = e.message ?: e::class.simpleName
                app.mcp.closed(failure)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server?.close()
        server = null
        app.mcp.closed(failure)
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "mcp_connection"
        const val NOTIFICATION = 20_001
        const val STOP = "com.wangzan101.mika.mcp.STOP"
    }
}
