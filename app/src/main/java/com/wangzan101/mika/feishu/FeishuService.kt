package com.wangzan101.mika.feishu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.MainActivity
import kotlinx.coroutines.*

/** User-enabled, visible persistent notification; no periodic worker or PC is required. */
class FeishuService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runtime=(application as MikaApp).feishu
        if (!runtime.config().enabled) { stopSelf(); return START_NOT_STICKY }
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,getString(R.string.feishu_title),NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.feishu_title)).setContentText(getString(R.string.feishu_service))
            .setContentIntent(open).setOngoing(true).build()
        startForeground(ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!runtime.start()) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }
    override fun onDestroy() {
        (application as MikaApp).feishu.cancel()
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL="feishu-connection"
        private const val ID=7601
        fun startIfEnabled(context: Context) {
            if ((context.applicationContext as MikaApp).feishu.config().enabled)
                context.startForegroundService(Intent(context,FeishuService::class.java))
        }
    }
}

class FeishuBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) runCatching { FeishuService.startIfEnabled(context) }
    }
}
