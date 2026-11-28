package com.zyz4.gkme.web

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 验证自签名证书覆盖所有需要访问的地址，并在地址未变化时复用缓存。
 * 与电脑端 SelfSignedCertificate 的 IP SAN 行为保持一致。
 */
class WebTlsTest {

    @Test
    fun certificateCoversEveryLanAddressAndLoopback() {
        withTempCertificate { file ->
            val certificate = WebTls.ensureCertificate(
                file,
                listOf("192.168.1.5", "10.0.0.2"),
            )

            assertEquals("CN=GKME Web Controller", certificate.subjectX500Principal.name)
            assertTrue(certificate.notAfter.time - System.currentTimeMillis() > 365L * 24 * 3600 * 1000)

            val addresses = certificate.subjectAlternativeNames
                .orEmpty()
                .filter { it.size >= 2 && it[0] == 7 }
                .mapNotNull { it[1] as? String }
                .toSet()
            assertEquals(setOf("192.168.1.5", "10.0.0.2", "127.0.0.1"), addresses)

            val names = certificate.subjectAlternativeNames
                .orEmpty()
                .filter { it.size >= 2 && it[0] == 2 }
                .mapNotNull { it[1] as? String }
                .toSet()
            assertTrue("localhost" in names)
        }
    }

    @Test
    fun existingCertificateIsReusedAndRegeneratedForNewAddress() {
        withTempCertificate { file ->
            val first = WebTls.ensureCertificate(file, listOf("192.168.1.5"))
            val second = WebTls.ensureCertificate(file, listOf("192.168.1.5"))
            assertArrayEquals(first.publicKey.encoded, second.publicKey.encoded)

            val third = WebTls.ensureCertificate(file, listOf("192.168.1.5", "172.16.0.9"))
            assertFalse(first.publicKey.encoded.contentEquals(third.publicKey.encoded))
        }
    }

    @Test
    fun sslContextCompletesAHandshake() {
        withTempCertificate { file ->
            val serverContext = WebTls.sslContext(file, listOf("192.168.1.5"))
            val server = serverContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket
            val executor = Executors.newSingleThreadExecutor()
            try {
                val received = executor.submit<Int> {
                    server.accept().use { socket ->
                        val tls = socket as SSLSocket
                        tls.soTimeout = 5_000
                        tls.startHandshake()
                        tls.inputStream.read()
                    }
                }

                val clientContext = SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf<TrustManager>(TrustAll), SecureRandom())
                }
                (clientContext.socketFactory.createSocket("127.0.0.1", server.localPort) as SSLSocket).use { client ->
                    client.soTimeout = 5_000
                    client.startHandshake()
                    client.outputStream.write(42)
                    client.outputStream.flush()
                }

                assertEquals(42, received.get(10, TimeUnit.SECONDS).toInt())
            } finally {
                server.close()
                executor.shutdownNow()
            }
        }
    }

    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun withTempCertificate(block: (File) -> Unit) {
        val file = File.createTempFile("gkme-web-", ".p12").apply { delete() }
        try {
            block(file)
        } finally {
            file.delete()
        }
    }
}
