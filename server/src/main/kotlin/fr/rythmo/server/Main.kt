package fr.rythmo.server

import fr.rythmo.sync.JvmTeacherIdentity
import fr.rythmo.sync.TeacherServer
import fr.rythmo.sync.TeacherStore
import fr.rythmo.sync.DiscoveryResponder
import java.io.File
import java.util.concurrent.CountDownLatch

fun main(args: Array<String>) {
    val directory = File(args.getOrNull(0) ?: "server-data")
    val port = args.getOrNull(1)?.toInt() ?: 8765
    val store = TeacherStore(directory)
    val identity = JvmTeacherIdentity.load(File(directory, "tls"))
    val server = TeacherServer(store, identity, port)
    val admin = TeacherServer(store, identity, args.getOrNull(2)?.toInt() ?: 8767, "127.0.0.1", localAdmin = true)
    admin.start(10_000, false)
    try {
        server.start(10_000, false)
    } catch (error: Exception) {
        admin.stop()
        throw error
    }
    val discovery = runCatching { DiscoveryResponder(server.store.state.serverId, port) }
        .onFailure { println("Découverte réseau indisponible : utilisez l’adresse du PC.") }.getOrNull()
    Runtime.getRuntime().addShutdownHook(Thread { discovery?.close(); server.stop(); admin.stop() })
    println("Rythmo enseignant : http://127.0.0.1:${admin.listeningPort}/#${server.store.state.adminKey}")
    println("Vérification du serveur : ${identity.verificationCode}")
    println("Synchronisation sécurisée : https://adresse-du-prof:$port")
    println("Code d’association des clients : ${server.store.state.pairingCode}")
    println("Données locales : ${directory.absolutePath}")
    CountDownLatch(1).await()
}
