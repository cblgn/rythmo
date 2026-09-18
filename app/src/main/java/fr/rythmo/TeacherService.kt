package fr.rythmo

import android.app.*
import android.content.Intent
import android.os.IBinder
import fr.rythmo.sync.*
import java.io.File

class TeacherService : Service() {
    private var server: TeacherServer? = null
    private var discovery: DiscoveryResponder? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("teacher", "Synchronisation Rythmo", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(42, Notification.Builder(this, "teacher").setSmallIcon(R.drawable.ic_rythmo)
            .setContentTitle("Rythmo enseignant actif").setContentText("Les appareils peuvent synchroniser leur séance et leurs bilans.")
            .setContentIntent(open).setOngoing(true).build())
        try {
            val store = TeacherStore(File(filesDir, "teacher"))
            server = TeacherServer(store).also { it.start(10_000, true) }
            discovery = runCatching { DiscoveryResponder(store.state.serverId, 8765) }.getOrNull()
            status = TeacherStatus(true, store.state.adminKey, store.state.pairingCode)
        } catch (e: Exception) {
            status = TeacherStatus(false, error = e.message ?: "Serveur indisponible.")
            stopSelf()
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onDestroy() {
        discovery?.close(); server?.stop()
        status = status.copy(running = false)
        super.onDestroy()
    }
    companion object { @Volatile var status = TeacherStatus(false) }
}
data class TeacherStatus(val running: Boolean, val adminKey: String = "", val pairingCode: String = "", val error: String? = null)
