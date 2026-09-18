package fr.rythmo.sync

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/** PC only; uses the JDK's certificate tooling, without an additional crypto provider. */
object JvmTeacherIdentity {
    fun load(directory: File): TeacherTls {
        directory.mkdirs()
        Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString("rwx------"))
        val passwordFile = File(directory, "tls-password")
        val storeFile = File(directory, "identity.p12")
        if (!passwordFile.exists()) {
            check(!storeFile.exists()) { "Mot de passe de l’identité TLS manquant. Restaurez la sauvegarde du serveur." }
            Files.createFile(passwordFile.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            passwordFile.writeText(Base64.getEncoder().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes)))
        }
        val password = passwordFile.readText().trim()
        require(password.length >= 32) { "Identité TLS incomplète." }
        if (!storeFile.exists()) {
            val pending = File(directory, "identity.pending.p12")
            // Recover only our incomplete certificate generation, never a published identity.
            if (pending.exists()) Files.delete(pending.toPath())
            val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
            val builder = ProcessBuilder(keytool, "-genkeypair", "-alias", "rythmo", "-keyalg", "RSA", "-keysize", "3072",
                "-sigalg", "SHA256withRSA", "-validity", "3650", "-dname", "CN=Rythmo local", "-storetype", "PKCS12",
                "-keystore", pending.absolutePath, "-storepass:env", "RYTHMO_TLS_STORE_PASSWORD", "-noprompt")
            builder.environment()["RYTHMO_TLS_STORE_PASSWORD"] = password
            builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            val process = builder.start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Création de l’identité TLS interrompue.") }
            check(process.exitValue() == 0) { "Création de l’identité TLS impossible." }
            Files.setPosixFilePermissions(pending.toPath(), PosixFilePermissions.fromString("rw-------"))
            Files.move(pending.toPath(), storeFile.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
        val store = KeyStore.getInstance("PKCS12").apply { storeFile.inputStream().use { load(it, password.toCharArray()) } }
        return TeacherTls.fromKeyStore(store, password.toCharArray(), "rythmo").also {
            File(directory, "fingerprint.sha256").writeText(it.fingerprint)
        }
    }
}
