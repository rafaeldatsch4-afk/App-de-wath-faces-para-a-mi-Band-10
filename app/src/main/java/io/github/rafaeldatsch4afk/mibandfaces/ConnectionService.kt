package io.github.rafaeldatsch4afk.mibandfaces

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Serviço em primeiro plano que existe enquanto há conexão com a banda. Ele não faz o trabalho (a
 * conexão fica no [AppContainer]): só mantém o processo vivo e fora da lista de apps "congelados"
 * quando o app vai para segundo plano ou a tela é bloqueada, como o Gadgetbridge faz. Também é o
 * que permite um envio de face de vários minutos terminar com o app em segundo plano.
 */
class ConnectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Conexão com a banda", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Mi Band 10 Faces")
            .setContentText("Conectado à banda. Toque para abrir.")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    /** O usuário tirou o app da lista de recentes: desconecta e encerra, sem deixar a banda presa. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        (application as App).container.client.disconnect()
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1

        /** Retorna false se o Android recusou (ex.: sem permissão de Bluetooth); a conexão segue sem o serviço. */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
            true
        } catch (e: Exception) {
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
