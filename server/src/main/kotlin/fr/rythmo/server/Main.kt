package fr.rythmo.server

import fr.rythmo.sync.TeacherServer
import fr.rythmo.sync.TeacherStore
import fr.rythmo.sync.DiscoveryResponder
import java.io.File
import java.util.concurrent.CountDownLatch

fun main(args: Array<String>) {
    val directory = File(args.getOrNull(0) ?: "server-data")
    val port = args.getOrNull(1)?.toInt() ?: 8765
    val server = TeacherServer(TeacherStore(directory), port)
    server.start(10_000, false)
    val discovery = runCatching { DiscoveryResponder(server.store.state.serverId, port) }
        .onFailure { println("Découverte réseau indisponible : utilisez l’adresse du PC.") }.getOrNull()
    Runtime.getRuntime().addShutdownHook(Thread { discovery?.close(); server.stop() })
    println("Rythmo enseignant : http://localhost:$port/#${server.store.state.adminKey}")
    println("Code d’association des clients : ${server.store.state.pairingCode}")
    println("Données locales : ${directory.absolutePath}")
    CountDownLatch(1).await()
}
