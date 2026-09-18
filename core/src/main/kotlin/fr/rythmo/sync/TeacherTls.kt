package fr.rythmo.sync

import java.net.URI
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.*

class TeacherTls(val context: SSLContext, val certificate: X509Certificate) {
    val fingerprint: String get() = fingerprint(certificate)
    val verificationCode: String get() = verificationCode(fingerprint)

    companion object {
        fun fromKeyStore(store: KeyStore, password: CharArray?, alias: String): TeacherTls {
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            managers.init(store, password)
            val context = SSLContext.getInstance("TLSv1.2").apply { init(managers.keyManagers, null, null) }
            return TeacherTls(context, store.getCertificate(alias) as X509Certificate)
        }
        fun fingerprint(cert: X509Certificate): String = MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded).joinToString("") { "%02x".format(it) }
        fun verificationCode(pin: String): String = pin.take(24).uppercase().chunked(4).joinToString(" ")
        fun endpoint(value: String): URI {
            val uri = URI(value.trim().trimEnd('/'))
            require(uri.scheme == "https" && uri.host != null && uri.userInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null && uri.path.isNullOrEmpty()) {
                "Adresse attendue : https://adresse-du-prof:8765"
            }
            return uri
        }
        private fun matches(cert: X509Certificate, pin: String) = MessageDigest.isEqual(
            fingerprint(cert).toByteArray(Charsets.US_ASCII), pin.toByteArray(Charsets.US_ASCII))
        private fun validate(cert: X509Certificate) { cert.checkValidity(); cert.verify(cert.publicKey) }
        private fun trustManager(check: (X509Certificate) -> Unit) = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("Authentification TLS client non prise en charge.")
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain == null || chain.size != 1) throw CertificateException("Certificat serveur inattendu.")
                validate(chain[0]); check(chain[0])
            }
        }
        /** Collect a candidate certificate by rejecting the handshake. No application data is sent. */
        fun inspect(value: String): String {
            val uri = endpoint(value)
            var candidate: X509Certificate? = null
            val manager = trustManager { candidate = it; throw CertificateException("Association requise.") }
            val context = SSLContext.getInstance("TLSv1.2").apply { init(null, arrayOf(manager), null) }
            val socket = ModernTlsSocketFactory(context.socketFactory).createSocket() as SSLSocket
            socket.use {
                it.connect(java.net.InetSocketAddress(uri.host, if (uri.port == -1) 443 else uri.port), 7000)
                it.soTimeout = 7000
                try { it.startHandshake() } catch (e: SSLException) { if (candidate == null) throw e }
            }
            return fingerprint(requireNotNull(candidate) { "Certificat indisponible." })
        }
        fun connection(value: String, path: String, pin: String): HttpsURLConnection {
            val uri = endpoint(value)
            require(pin.matches(Regex("[0-9a-f]{64}"))) { "Associez d’abord ce serveur dans l’accès professeur." }
            val manager = trustManager { cert ->
                if (!matches(cert, pin)) throw CertificateException("Identité du serveur modifiée. Nouvelle association requise.")
            }
            val context = SSLContext.getInstance("TLSv1.2").apply { init(null, arrayOf(manager), null) }
            return (URI(uri.toString() + path).toURL().openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = ModernTlsSocketFactory(context.socketFactory)
                // A pinned local identity authenticates the server independently of its changing LAN address.
                hostnameVerifier = HostnameVerifier { _, session ->
                    val cert = session.peerCertificates.firstOrNull() as? X509Certificate
                    cert != null && matches(cert, pin)
                }
                instanceFollowRedirects = false
                connectTimeout = 7000; readTimeout = 15000
            }
        }
    }
}

/** Older Android providers can enable obsolete protocols even with a TLSv1.2 context. */
internal class ModernTlsSocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
    private fun restrict(socket: Socket): Socket = (socket as SSLSocket).apply {
        enabledProtocols = supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
    }
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun createSocket(): Socket = restrict(delegate.createSocket())
    override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        restrict(delegate.createSocket(socket, host, port, autoClose))
    override fun createSocket(host: String, port: Int): Socket = restrict(delegate.createSocket(host, port))
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
        restrict(delegate.createSocket(host, port, local, localPort))
    override fun createSocket(host: InetAddress, port: Int): Socket = restrict(delegate.createSocket(host, port))
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
        restrict(delegate.createSocket(host, port, local, localPort))
}
