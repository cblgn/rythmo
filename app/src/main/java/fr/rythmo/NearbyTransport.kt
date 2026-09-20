package fr.rythmo

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import fr.rythmo.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Google callbacks are marshalled to Main; parsing and durable writes run on IO. */
@SuppressLint("MissingPermission")
class NearbyTransport internal constructor(context: Context, private val client: ConnectionsClient,
    private val availabilityProblem: () -> String?) : LocalTransport {
    constructor(context: Context) : this(context, Nearby.getConnectionsClient(context), { NearbyRequirements.problem(context) })
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(TransportState())
    override val state = mutable.asStateFlow()
    private val pending = ConcurrentHashMap<String, CompletableFuture<SyncMessage>>()
    private data class Incoming(val endpoint: String, val connectionId: String, val stream: InputStream, var bytes: ByteArray? = null, var succeeded: Boolean = false)
    private val incoming = mutableMapOf<Long, Incoming>()
    private val outgoing = mutableMapOf<Long, InputStream>()
    private val accepted = mutableSetOf<String>()
    private var requestedEndpoint: String? = null
    private val names = mutableMapOf<String, Endpoint>()
    private var receiver: ((String, SyncMessage) -> SyncMessage)? = null
    private var host: String? = null
    private var closed = false
    private var active = false
    private var generation = 0
    private val serviceId = context.packageName + ".session.v1"

    override fun discover(name: String) { scope.launch {
        disconnectInternal()
        if (!available()) return@launch
        active = true
        val cycle = generation
        mutable.value = TransportState(phase = ConnectionPhase.SEARCHING)
        client.startDiscovery(serviceId, discovery, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build())
            .addOnFailureListener { fail(it) }
        delay(2500)
        if (cycle != generation || mutable.value.phase != ConnectionPhase.SEARCHING) return@launch
        val found = mutable.value.endpoints
        if (found.size == 1) connect(found.single(), name)
        else if (found.isEmpty()) {
            repeat(25) {
                delay(500)
                if (cycle != generation || mutable.value.phase != ConnectionPhase.SEARCHING) return@launch
                val available = mutable.value.endpoints
                if (available.isNotEmpty()) {
                    if (available.size == 1) connect(available.single(), name)
                    return@launch
                }
            }
            client.stopDiscovery(); fail(IllegalStateException("Aucune séance trouvée. Rapprochez-vous du professeur et réessayez."))
        }
    } }
    override fun connect(endpoint: Endpoint, name: String) { scope.launch {
        if (!available()) return@launch
        if (mutable.value.connected.isNotEmpty() || mutable.value.associations.isNotEmpty() || mutable.value.phase == ConnectionPhase.CONNECTING) return@launch
        active = true
        requestedEndpoint = endpoint.id
        names[endpoint.id] = endpoint
        mutable.value = mutable.value.copy(phase = ConnectionPhase.CONNECTING, error = null)
        client.stopDiscovery()
        client.requestConnection(name.take(80), endpoint.id, lifecycle).addOnFailureListener { fail(it) }
    } }
    override fun advertise(name: String, receive: (String, SyncMessage) -> SyncMessage) { scope.launch {
        disconnectInternal()
        if (!available()) return@launch
        active = true
        receiver = receive
        client.startAdvertising(name.take(100), serviceId, lifecycle,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build())
            .addOnSuccessListener { mutable.value = mutable.value.copy(phase = ConnectionPhase.IDLE, advertising = true) }
            .addOnFailureListener { fail(it) }
    } }
    /** Refresh discovery text without disconnecting authenticated endpoints. */
    fun renameHost(name: String) { scope.launch {
        if (!active || receiver == null || !mutable.value.advertising) return@launch
        client.stopAdvertising()
        client.startAdvertising(name.take(100), serviceId, lifecycle,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build())
            .addOnFailureListener { fail(it) }
    } }
    override fun confirm(endpointId: String, accept: Boolean) { scope.launch {
        if (mutable.value.associations.none { it.endpoint.id == endpointId }) return@launch
        mutable.value = mutable.value.copy(associations = mutable.value.associations.filterNot { it.endpoint.id == endpointId })
        if (accept) { accepted.add(endpointId); client.acceptConnection(endpointId, payloads).addOnFailureListener { fail(it) } }
        else client.rejectConnection(endpointId).addOnCompleteListener {
            mutable.value = mutable.value.copy(phase = if (receiver == null) ConnectionPhase.IDLE else mutable.value.phase)
        }
    } }
    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) { scope.launch {
            if (mutable.value.phase != ConnectionPhase.SEARCHING) return@launch
            val endpoint = Endpoint(id, info.endpointName)
            mutable.value = mutable.value.copy(endpoints = mutable.value.endpoints.filterNot { it.id == id } + endpoint)
        } }
        override fun onEndpointLost(id: String) { scope.launch {
            mutable.value = mutable.value.copy(endpoints = mutable.value.endpoints.filterNot { it.id == id })
        } }
    }
    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) { scope.launch {
            val endpoint = Endpoint(id, info.endpointName)
            names[id] = endpoint
            if (receiver == null && (requestedEndpoint != id || mutable.value.connected.isNotEmpty())) { client.rejectConnection(id); return@launch }
            mutable.value = mutable.value.copy(phase = ConnectionPhase.ASSOCIATING,
                associations = mutable.value.associations.filterNot { it.endpoint.id == id } + Association(endpoint, info.authenticationDigits))
            delay(60_000)
            if (mutable.value.associations.any { it.endpoint.id == id }) confirm(id, false)
        } }
        override fun onConnectionResult(id: String, result: ConnectionResolution) { scope.launch {
            mutable.value = mutable.value.copy(associations = mutable.value.associations.filterNot { it.endpoint.id == id })
            if (result.status.isSuccess) {
                if (!accepted.remove(id)) { client.disconnectFromEndpoint(id); return@launch }
                if (receiver == null) host = id
                mutable.value = mutable.value.copy(phase = ConnectionPhase.CONNECTED, error = null,
                    connected = mutable.value.connected.filterNot { it.id == id } + (names[id] ?: Endpoint(id, "Rythmo")).copy(connectionId = fr.rythmo.session.newId()))
            } else fail(IllegalStateException("Association refusée ou interrompue. Réessayez."))
        } }
        override fun onDisconnected(id: String) { scope.launch {
            if (host == id) { host = null; failPending("Connexion perdue. Les données locales sont conservées.") }
            mutable.value = mutable.value.copy(connected = mutable.value.connected.filterNot { it.id == id },
                associations = mutable.value.associations.filterNot { it.endpoint.id == id },
                phase = if (receiver == null) ConnectionPhase.LOST else ConnectionPhase.IDLE)
            incoming.filterValues { it.endpoint == id }.keys.toList().forEach { cleanupIncoming(it) }
        } }
    }
    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) { scope.launch {
            if (mutable.value.connected.none { it.id == id }) { client.cancelPayload(payload.id); return@launch }
            when (payload.type) {
                Payload.Type.BYTES -> payload.asBytes()?.let { receive(id, it) }
                Payload.Type.STREAM -> {
                    val stream = payload.asStream()?.asInputStream() ?: return@launch
                    if (incoming.size >= 8) { client.cancelPayload(payload.id); stream.close(); return@launch }
                    val transfer = Incoming(id, mutable.value.connected.first { it.id == id }.connectionId, stream)
                    incoming[payload.id] = transfer
                    launch {
                        delay(45_000)
                        if (incoming[payload.id] === transfer) { client.cancelPayload(payload.id); cleanupIncoming(payload.id) }
                    }
                    try {
                        transfer.bytes = withContext(Dispatchers.IO) { stream.use { SyncCodec.readBounded(it) } }
                        completeIncoming(payload.id)
                    } catch (e: Exception) { cleanupIncoming(payload.id); fail(e) }
                }
                else -> client.cancelPayload(payload.id)
            }
        } }
        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) { scope.launch {
            if (update.bytesTransferred > SyncCodec.MAX_BYTES || update.totalBytes > SyncCodec.MAX_BYTES) {
                client.cancelPayload(update.payloadId); cleanupIncoming(update.payloadId); return@launch
            }
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                incoming[update.payloadId]?.succeeded = true
                completeIncoming(update.payloadId)
            } else if (update.status != PayloadTransferUpdate.Status.IN_PROGRESS) {
                cleanupIncoming(update.payloadId)
                if (host == id) failPending("Transfert interrompu. Réessayez l’envoi.")
            }
            if (update.status != PayloadTransferUpdate.Status.IN_PROGRESS) outgoing.remove(update.payloadId)?.close()
        } }
    }
    private fun completeIncoming(id: Long) {
        val transfer = incoming[id] ?: return
        val bytes = transfer.bytes ?: return
        if (!transfer.succeeded) return
        incoming.remove(id)
        if (mutable.value.connected.any { it.id == transfer.endpoint && it.connectionId == transfer.connectionId }) receive(transfer.endpoint, bytes)
    }
    private fun receive(id: String, bytes: ByteArray) { scope.launch {
        val connection = mutable.value.connected.firstOrNull { it.id == id }?.connectionId ?: return@launch
        try {
            val request = withContext(Dispatchers.IO) { SyncCodec.decode(bytes) }
            val handler = receiver
            if (handler != null) {
                val response = withContext(Dispatchers.IO) { handler(connection, request) }
                if (mutable.value.connected.any { it.connectionId == connection }) send(id, response)
            } else if (host == id) pending.remove(request.requestId)?.complete(request)
        } catch (e: Exception) { fail(e) }
    } }
    private suspend fun send(id: String, message: SyncMessage) {
        val bytes = withContext(Dispatchers.IO) { SyncCodec.encode(message) }
        // FILE payloads use public Downloads on the receiver. STREAM keeps reports private.
        val payload = if (bytes.size <= 32_000) Payload.fromBytes(bytes) else {
            val stream = ByteArrayInputStream(bytes)
            Payload.fromStream(stream).also { outgoing[it.id] = stream }
        }
        client.sendPayload(id, payload).addOnFailureListener { outgoing.remove(payload.id)?.close(); fail(it) }
        scope.launch {
            delay(45_000)
            outgoing.remove(payload.id)?.let { client.cancelPayload(payload.id); it.close() }
        }
    }

    override fun exchange(request: SyncMessage): SyncMessage {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        val result = CompletableFuture<SyncMessage>()
        pending[request.requestId] = result
        scope.launch {
            try { send(requireNotNull(host) { "Récupérez la séance pour reconnecter le professeur." }, request) }
            catch (e: Exception) { result.completeExceptionally(e) }
        }
        return try { result.get(45, TimeUnit.SECONDS) }
        catch (e: java.util.concurrent.ExecutionException) { throw IllegalStateException(e.cause?.message ?: "Échange interrompu.", e.cause) }
        catch (e: java.util.concurrent.TimeoutException) { throw IllegalStateException("Le professeur ne répond pas. Réessayez, vos données sont conservées.", e) }
        finally { pending.remove(request.requestId) }
    }
    private fun cleanupIncoming(id: Long) { incoming.remove(id)?.stream?.let { runCatching { it.close() } } }
    private fun failPending(message: String) { pending.values.forEach { it.completeExceptionally(IllegalStateException(message)) }; pending.clear() }
    private fun fail(error: Exception) {
        if (closed) return
        mutable.value = mutable.value.copy(phase = ConnectionPhase.ERROR, error = error.message ?: "Nearby indisponible. Vérifiez les autorisations et réessayez.")
        failPending(mutable.value.error!!)
    }
    private fun available(): Boolean {
        val error = availabilityProblem()
        if (error != null) { fail(IllegalStateException(error)); return false }
        return true
    }
    override fun disconnect() { scope.launch { disconnectInternal() } }
    private fun disconnectInternal() {
        generation++
        if (active) { client.stopDiscovery(); client.stopAdvertising(); client.stopAllEndpoints() }
        active = false
        host = null; receiver = null; names.clear(); accepted.clear(); requestedEndpoint = null
        failPending("Connexion fermée. Les données locales sont conservées.")
        incoming.keys.toList().forEach(::cleanupIncoming)
        outgoing.values.forEach { it.close() }; outgoing.clear()
        mutable.value = TransportState()
    }
    override fun close() { disconnectInternal(); closed = true; scope.cancel() }
}

object NearbyRequirements {
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.NEARBY_WIFI_DEVICES)
        Build.VERSION.SDK_INT >= 31 -> arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        else -> arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    }
    @SuppressLint("MissingPermission")
    fun problem(context: Context): String? = when {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS -> "Services Google Play indisponibles. Utilisez Réseau local / PC."
        permissions().any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED } -> "Autorisez les appareils à proximité pour utiliser Nearby."
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled != true -> "Activez le Bluetooth puis réessayez."
        !context.applicationContext.getSystemService(WifiManager::class.java).isWifiEnabled -> "Activez le Wi-Fi, sans rejoindre de réseau, puis réessayez."
        Build.VERSION.SDK_INT <= 32 && !androidx.core.location.LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java)) -> "Activez la localisation Android pour rechercher les appareils à proximité."
        else -> null
    }
}
