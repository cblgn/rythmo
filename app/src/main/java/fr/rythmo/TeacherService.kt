package fr.rythmo

import android.app.*
import android.content.Intent
import android.os.IBinder
import fr.rythmo.sync.*
import java.io.File
import kotlinx.coroutines.*

class TeacherService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var destroyed = false
    private var server: TeacherServer? = null
    private var admin: TeacherServer? = null
    private var discovery: DiscoveryResponder? = null
    @Volatile private var store: TeacherStore? = null
    private var advertisementJob: Job? = null
    private var nearbyHost: NearbySessionHost? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("teacher", "Synchronisation Rythmo", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java)
            .setAction(ACTION_STOP_REQUEST).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        startForeground(42, Notification.Builder(this, "teacher").setSmallIcon(R.drawable.ic_wifi)
            .setContentTitle("Rythmo · Professeur").setContentText("Partage de séance actif")
            .addAction(Notification.Action.Builder(null, "Arrêter…", stop).build())
            .setContentIntent(open).setOngoing(true).build())
        scope.launch {
            try {
                val store = AndroidTeacherRepository.get(this@TeacherService).store
                this@TeacherService.store = store
                val identity = AndroidTeacherIdentity.load()
                ensureActive()
                synchronized(this@TeacherService) {
                    if (!destroyed) {
                        admin = TeacherServer(store, identity, 8767, "127.0.0.1", localAdmin = true).also { it.start(10_000, true) }
                        server = TeacherServer(store, identity).also { it.start(10_000, true) }
                        discovery = runCatching { DiscoveryResponder(store.state.serverId, 8765) }.getOrNull()
                        status = TeacherStatus(true, store.state.adminKey, store.state.pairingCode, verificationCode = identity.verificationCode)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                status = TeacherStatus(false, error = e.message ?: "Serveur indisponible.")
                stopSelf()
            }
        }
    }
    fun advertiseNearby() {
        val current = store ?: return
        if (nearbyHost != null && nearby?.state?.value?.error == null) return
        nearbyHost?.close()
        val transport = NearbyTransport(this)
        nearbyReference = java.lang.ref.WeakReference(transport)
        nearbyHost = NearbySessionHost(transport, current).also { it.start(AndroidTeacherRepository.get(this).preparation.draft.teacherName) }
        advertisementJob?.cancel()
        advertisementJob = scope.launch {
            var previous = ""
            while (isActive && !destroyed && nearbyHost != null) {
                val name = nearbyHost?.displayName(AndroidTeacherRepository.get(this@TeacherService).preparation.draft.teacherName).orEmpty()
                if (name != previous) { withContext(Dispatchers.Main) { transport.renameHost(name) }; previous = name }
                delay(1000)
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("nearby", false) == true) {
            scope.launch { while (store == null && !destroyed) delay(100); withContext(Dispatchers.Main) { if (!destroyed) advertiseNearby() } }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        scope.cancel()
        nearbyHost?.close(); nearbyHost = null; nearbyReference.clear()
        synchronized(this) {
            destroyed = true
            discovery?.close(); server?.stop(); admin?.stop()
            status = status.copy(running = false)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        const val ACTION_STOP_REQUEST = "fr.rythmo.REQUEST_STOP_TEACHER"
        @Volatile var status = TeacherStatus(false)
        private var nearbyReference = java.lang.ref.WeakReference<NearbyTransport>(null)
        val nearby: NearbyTransport? get() = nearbyReference.get()
    }
}
data class TeacherStatus(val running: Boolean, val adminKey: String = "", val pairingCode: String = "", val error: String? = null, val verificationCode: String = "")
