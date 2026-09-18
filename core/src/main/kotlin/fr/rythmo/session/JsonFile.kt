package fr.rythmo.session

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.KSerializer

/** Writes a complete snapshot and syncs it before atomically replacing the previous one. */
class JsonFile<T>(private val file: File, private val serializer: KSerializer<T>, private val initial: () -> T) {
    @Synchronized fun read(): T = if (file.exists()) sessionJson.decodeFromString(serializer, file.readText()) else initial()
    @Synchronized fun write(value: T) {
        file.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "Dossier de sauvegarde inaccessible." } }
        val temp = File(file.parentFile, "${file.name}.pending")
        FileOutputStream(temp).use { stream ->
            stream.write(sessionJson.encodeToString(serializer, value).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
