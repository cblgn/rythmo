package fr.rythmo.session

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

@Serializable
data class TeacherAccess(val salt: String, val verifier: String) {
    fun accepts(code: String): Boolean {
        if (!code.matches(Regex("[0-9]{6}"))) return false
        return MessageDigest.isEqual(Base64.getDecoder().decode(verifier), derive(code, salt))
    }

    companion object {
        fun newCode(): String = (SecureRandom().nextInt(900_000) + 100_000).toString()
        fun fromCode(code: String): TeacherAccess {
            require(code.matches(Regex("[0-9]{6}")))
            val salt = Base64.getEncoder().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
            return TeacherAccess(salt, Base64.getEncoder().encodeToString(derive(code, salt)))
        }
        private fun derive(code: String, salt: String): ByteArray {
            val spec = PBEKeySpec(code.toCharArray(), Base64.getDecoder().decode(salt), 120_000, 256)
            return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        }
    }
}

@Serializable
data class TeacherAttempts(val failures: Int = 0, val blockedUntilMs: Long = 0) {
    fun blocked(nowMs: Long): Boolean = nowMs < blockedUntilMs
    fun rejected(nowMs: Long): TeacherAttempts = if (failures >= 4) TeacherAttempts(0, nowMs + 30_000)
        else copy(failures = failures + 1)
}
