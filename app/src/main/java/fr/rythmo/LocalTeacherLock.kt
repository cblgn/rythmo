package fr.rythmo

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Local credentials only. Never reads or modifies session or PDF storage. */
class LocalTeacherLock(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    private var state = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }
    val configured: Boolean get() = state.containsKey("pin.hash")

    val recoveryAcknowledged: Boolean get() = state.getProperty("acknowledged") == "true"

    init {
        if (file.exists()) {
            require(configured && state.containsKey("recovery.hash")) { "Protection locale illisible. Les données sont conservées." }
        }
    }

    @Synchronized fun initialize(pin: String, confirmation: String): String {
        check(!configured)
        validatePin(pin, confirmation)
        return replaceCredentials(pin)
    }

    @Synchronized fun unlock(pin: String): Boolean = verify("pin", pin)

    @Synchronized fun recover(code: String, pin: String, confirmation: String): String {
        validatePin(pin, confirmation)
        check(verify("recovery", code.filterNot { it.isWhitespace() || it == '-' }.uppercase())) {
            "Code de secours incorrect."
        }
        return replaceCredentials(pin)
    }

    @Synchronized fun acknowledgeRecovery() {
        save(copyState().apply { setProperty("acknowledged", "true") })
    }

    @Synchronized fun renewUnconfirmedRecovery(): String {
        check(!recoveryAcknowledged)
        val recovery = randomRecovery()
        val next = copyState()
        putVerifier(next, "recovery", recovery)
        save(next)
        return recovery.chunked(4).joinToString("-")
    }

    private fun validatePin(pin: String, confirmation: String) {
        require(pin.matches(Regex("[0-9]{6}"))) { "Le PIN doit contenir six chiffres." }
        require(pin == confirmation) { "Les deux PIN ne correspondent pas." }
    }

    private fun verify(kind: String, secret: String): Boolean {
        check(configured)
        val time = now()
        val until = state.getProperty("blockedUntil", "0").toLong()
        check(time >= until) { "Trop de tentatives. Réessayez dans ${((until - time + 999) / 1000)} secondes." }
        val valid = MessageDigest.isEqual(Base64.getDecoder().decode(state.getProperty("$kind.hash")),
            derive(secret, state.getProperty("$kind.salt")))
        val next = copyState()
        val failures = if (valid) 0 else state.getProperty("failures", "0").toInt() + 1
        next.setProperty("failures", (if (failures >= 5) 0 else failures).toString())
        next.setProperty("blockedUntil", (if (failures >= 5) time + 30_000 else 0).toString())
        save(next)
        return valid
    }

    private fun replaceCredentials(pin: String): String {
        val recovery = randomRecovery()
        val next = Properties()
        for ((kind, secret) in listOf("pin" to pin, "recovery" to recovery)) {
            putVerifier(next, kind, secret)
        }
        save(next)
        return recovery.chunked(4).joinToString("-")
    }

    private fun randomRecovery() = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02X".format(it) }
    private fun putVerifier(next: Properties, kind: String, secret: String) {
        val salt = Base64.getEncoder().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
        next.setProperty("$kind.salt", salt)
        next.setProperty("$kind.hash", Base64.getEncoder().encodeToString(derive(secret, salt)))
    }

    private fun derive(secret: String, salt: String): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), Base64.getDecoder().decode(salt), 120_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    private fun copyState() = Properties().apply { putAll(state) }
    private fun save(next: Properties) {
        file.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        val pending = File(file.parentFile, "${file.name}.pending")
        FileOutputStream(pending).use { next.store(it, null); it.fd.sync() }
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        state = next
    }
}
