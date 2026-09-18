package fr.rythmo

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import fr.rythmo.sync.TeacherTls
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal

object AndroidTeacherIdentity {
    fun load(): TeacherTls {
        val alias = "rythmo-teacher-tls-ec"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    // Conscrypt signs TLS digests it has already computed; SHA-256 signs the certificate.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                    .setCertificateSubject(X500Principal("CN=Rythmo local"))
                    .setCertificateSerialNumber(BigInteger(128, SecureRandom()).add(BigInteger.ONE))
                    .setCertificateNotBefore(Date(System.currentTimeMillis() - 86_400_000))
                    .setCertificateNotAfter(Date(System.currentTimeMillis() + 3_650L * 86_400_000))
                    .build())
                generateKeyPair()
            }
        }
        return TeacherTls.fromKeyStore(store, null, alias)
    }
}
