package com.zyz4.gkme.web

import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.ContentSigner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * 网页手柄的 TLS 材料，逻辑对齐电脑端 `Web/SelfSignedCertificate.cs` +
 * `Web/WebTls.cs`：
 *
 * - 证书完全在设备本地生成，SAN 覆盖当前全部局域网 IPv4 与回环地址，因此
 *   `https://192.168.x.x:37285` 不会出现“名称不匹配”，只需首次点一次
 *   “继续访问”即可进入安全上下文（浏览器只在该上下文暴露陀螺仪等传感器）。
 * - 证书有效期两年；地址变化或临近过期（30 天）时自动重新生成。
 * - PKCS#12 存放在应用私有目录，密码固定且不导出，仅用于本机 KeyManager。
 *
 * 该类不依赖 Android API，便于在 JVM 单元测试里验证证书内容。
 */
internal object WebTls {

    private val PASSWORD = "gkme-web".toCharArray()
    private const val ALIAS = "gkme-web"
    private const val SUBJECT = "CN=GKME Web Controller"
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val LIFETIME_MS = 730 * DAY_MS
    private const val RENEW_BEFORE_MS = 30 * DAY_MS
    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /** 保证 [file] 中的证书可用，并据此建立服务端 SSLContext。 */
    fun sslContext(file: File, addresses: Collection<String>): SSLContext {
        val store = loadOrCreate(file, addresses)
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(store, PASSWORD)
        return SSLContext.getInstance("TLS").apply {
            init(factory.keyManagers, null, null)
        }
    }

    /** 确保证书存在并返回它；单元测试与 [sslContext] 共用同一条生成路径。 */
    internal fun ensureCertificate(file: File, addresses: Collection<String>): X509Certificate {
        val store = loadOrCreate(file, addresses)
        return store.getCertificate(ALIAS) as X509Certificate
    }

    private fun loadOrCreate(file: File, addresses: Collection<String>): KeyStore {
        val required = requiredAddresses(addresses)
        load(file)?.let { store ->
            val certificate = store.getCertificate(ALIAS) as? X509Certificate
            if (certificate != null &&
                certificate.notAfter.time - System.currentTimeMillis() > RENEW_BEFORE_MS &&
                covers(certificate, required)
            ) {
                return store
            }
        }

        val created = create(required)
        file.parentFile?.mkdirs()
        file.outputStream().use { created.store(it, PASSWORD) }
        return created
    }

    private fun load(file: File): KeyStore? {
        if (!file.isFile) return null
        return try {
            KeyStore.getInstance("PKCS12").apply {
                file.inputStream().use { load(it, PASSWORD) }
            }.takeIf { it.getKey(ALIAS, PASSWORD) != null }
        } catch (_: Exception) {
            null
        }
    }

    private fun requiredAddresses(addresses: Collection<String>): List<String> =
        (addresses + "127.0.0.1")
            .filter { IPV4.matches(it) }
            .distinct()

    /** 已缓存的证书是否覆盖全部必需地址。缺少任意一个都会触发重新生成。 */
    private fun covers(certificate: X509Certificate, required: List<String>): Boolean {
        val present = try {
            certificate.subjectAlternativeNames
                ?.filter { it.size >= 2 && it[0] == GeneralName.iPAddress }
                ?.mapNotNull { ipAddress(it[1]) }
                ?.toSet()
                ?: return false
        } catch (_: Exception) {
            return false
        }
        return required.all { it in present }
    }

    /** 不同 Provider 的 SAN 可能把 IP 表示为字符串或 4 字节数组。 */
    private fun ipAddress(value: Any?): String? = when (value) {
        is String -> value
        is ByteArray -> if (value.size == 4) {
            value.joinToString(".") { (it.toInt() and 0xFF).toString() }
        } else {
            null
        }
        else -> null
    }

    private fun create(addresses: List<String>): KeyStore {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val keyPair = generator.generateKeyPair()

        val now = System.currentTimeMillis()
        val subject = X500Name(SUBJECT)
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(64, SecureRandom()),
            Date(now - DAY_MS),
            Date(now + LIFETIME_MS),
            subject,
            keyPair.public,
        )

        val names = addresses.map { GeneralName(GeneralName.iPAddress, it) } +
            GeneralName(GeneralName.dNSName, "localhost")
        builder.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(names.toTypedArray()),
        )
        builder.addExtension(Extension.basicConstraints, false, BasicConstraints(false))
        builder.addExtension(
            Extension.keyUsage,
            false,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
        )
        builder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth),
        )

        val holder = builder.build(RsaSha256Signer(keyPair.private))
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(holder.encoded)) as X509Certificate

        return KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(ALIAS, keyPair.private, PASSWORD, arrayOf<Certificate>(certificate))
        }
    }

    /**
     * 用默认的 JCA RSA 签名实现签发证书，避免注册 BouncyCastle Provider——
     * Android 运行时自带的 Provider 名称可能与本依赖冲突。
     */
    private class RsaSha256Signer(private val key: PrivateKey) : ContentSigner {
        private val buffer = ByteArrayOutputStream()

        override fun getAlgorithmIdentifier(): AlgorithmIdentifier =
            AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)

        override fun getOutputStream(): OutputStream = buffer

        override fun getSignature(): ByteArray {
            val signature = Signature.getInstance("SHA256withRSA")
            signature.initSign(key)
            signature.update(buffer.toByteArray())
            return signature.sign()
        }
    }
}
