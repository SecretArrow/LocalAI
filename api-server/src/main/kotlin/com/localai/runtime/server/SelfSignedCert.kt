package com.localai.runtime.server

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date

/**
 * Self-signed TLS certificate helpers (BouncyCastle).
 *
 * - [ensureProvider] registers BC as the preferred security provider (safe to call repeatedly;
 *   called from the app process before any TLS/cert operation, e.g. from `ApiServer.generateSelfSignedCert`).
 * - [generate] creates a fresh RSA-2048 self-signed certificate valid for 3650 days
 *   (CN=LocalAI Runtime, SANs: DNS `localhost` + IP `127.0.0.1`).
 * - [buildKeyStore] turns an imported PEM pair into the in-memory PKCS12 key store used by the
 *   Ktor SSL connector.
 */
class SelfSignedCert private constructor() {

    companion object {
        const val KEY_ALIAS = "localai"
        val KEY_PASSWORD = "localai".toCharArray()
        private const val PROVIDER = "BC"
        private const val VALIDITY_DAYS = 3650L

        @Volatile
        private var providerReady = false

        fun ensureProvider() {
            if (providerReady) return
            synchronized(this) {
                if (providerReady) return
                runCatching { Security.removeProvider("BC") }
                Security.insertProviderAt(BouncyCastleProvider(), 1)
                providerReady = true
            }
        }

        /** Returns `(certPem, keyPem)`; the key is PKCS8, both wrapped at 64 chars. */
        fun generate(): Pair<String, String> {
            ensureProvider()
            val keyPair = KeyPairGenerator.getInstance("RSA").apply {
                initialize(2048, SecureRandom())
            }.generateKeyPair()

            val now = System.currentTimeMillis()
            val notBefore = Date(now - 24L * 60L * 60L * 1000L)
            val notAfter = Date(now + VALIDITY_DAYS * 24L * 60L * 60L * 1000L)
            val subject = X500Name("CN=LocalAI Runtime")

            val builder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger(160, SecureRandom()).abs(),
                notBefore,
                notAfter,
                subject,
                keyPair.public,
            )
            builder.addExtension(
                Extension.subjectAlternativeName,
                false,
                GeneralNames(
                    arrayOf(
                        GeneralName(GeneralName.dNSName, "localhost"),
                        GeneralName(GeneralName.iPAddress, "127.0.0.1"),
                    ),
                ),
            )
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            builder.addExtension(
                Extension.keyUsage,
                true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
            )

            val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(PROVIDER).build(keyPair.private)
            val certificate = JcaX509CertificateConverter().setProvider(PROVIDER).getCertificate(builder.build(signer))

            return toPem("CERTIFICATE", certificate.encoded) to toPem("PRIVATE KEY", keyPair.private.encoded)
        }

        /** Parses a PEM certificate + PKCS8 key pair into an in-memory PKCS12 key store. */
        fun buildKeyStore(certPem: String, keyPem: String): KeyStore {
            val certificate = parseCertificate(certPem)
            val privateKey = parsePrivateKey(keyPem)
            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(null, null)
            keyStore.setKeyEntry(KEY_ALIAS, privateKey, KEY_PASSWORD, arrayOf(certificate))
            return keyStore
        }

        fun parseCertificate(pem: String): X509Certificate {
            val der = Base64.getMimeDecoder().decode(pemBody(pem))
            val factory = CertificateFactory.getInstance("X.509")
            return factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }

        /** Accepts PKCS8-encoded RSA, EC or Ed25519 private keys. */
        fun parsePrivateKey(pem: String): PrivateKey {
            val der = Base64.getMimeDecoder().decode(pemBody(pem))
            val spec = PKCS8EncodedKeySpec(der)
            for (algorithm in listOf("RSA", "EC", "Ed25519")) {
                try {
                    return KeyFactory.getInstance(algorithm).generatePrivate(spec)
                } catch (t: Throwable) {
                    // try next algorithm
                }
            }
            throw IllegalArgumentException("Unsupported private key (expected PKCS8 RSA/EC/Ed25519 PEM)")
        }

        private fun pemBody(pem: String): String =
            pem.lines()
                .filter { !it.startsWith("-----") }
                .joinToString("") { it.trim() }

        private fun toPem(label: String, der: ByteArray): String {
            val base64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
            return "-----BEGIN $label-----\n$base64\n-----END $label-----\n"
        }
    }
}
