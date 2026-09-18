package fr.rythmo.sync

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/** Discovery contains no pupil data or credentials. Pairing still requires the teacher's code. */
class DiscoveryResponder(private val serverId: String, private val httpPort: Int, private val discoveryPort: Int = 8766) : AutoCloseable {
    private val socket = DatagramSocket(discoveryPort)
    init {
        thread(name = "rythmo-discovery", isDaemon = true) {
            while (!socket.isClosed) {
                try {
                    val packet = DatagramPacket(ByteArray(256), 256)
                    socket.receive(packet)
                    if (String(packet.data, 0, packet.length, Charsets.UTF_8) == "RYTHMO/1?") {
                        val bytes = "RYTHMO/1 $serverId $httpPort".toByteArray()
                        socket.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
                    }
                } catch (_: java.io.IOException) { if (!socket.isClosed) close() }
            }
        }
    }
    override fun close() = socket.close()
}

fun discoverTeachers(): List<String> {
    val found = linkedSetOf<String>()
    DatagramSocket().use { socket ->
        socket.broadcast = true; socket.soTimeout = 300
        val addresses = mutableSetOf(InetAddress.getByName("255.255.255.255"))
        NetworkInterface.getNetworkInterfaces()?.toList()?.filter { it.isUp && !it.isLoopback }?.forEach { network ->
            network.interfaceAddresses.mapNotNullTo(addresses) { it.broadcast }
        }
        val query = "RYTHMO/1?".toByteArray()
        addresses.forEach { address -> runCatching { socket.send(DatagramPacket(query, query.size, address, 8766)) } }
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            try {
                val packet = DatagramPacket(ByteArray(256), 256)
                socket.receive(packet)
                val fields = String(packet.data, 0, packet.length).split(' ')
                if (fields.size == 3 && fields[0] == "RYTHMO/1" && fields[2].toIntOrNull() in 1..65535) {
                    found += "http://${packet.address.hostAddress}:${fields[2]}"
                }
            } catch (_: SocketTimeoutException) { /* Collect all teachers on this LAN. */ }
        }
    }
    return found.toList()
}
