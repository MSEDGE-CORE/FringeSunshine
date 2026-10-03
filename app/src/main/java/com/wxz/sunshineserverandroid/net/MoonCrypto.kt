package com.wxz.sunshineserverandroid.net

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Moonlight/Sunshine 配对协议所需的基础加密原语：
 * - AES-128-ECB（NoPadding，与 Moonlight PairingManager 一致）：配对四阶段加密
 * - SHA256：派生密钥 = SHA256(salt+pin)[0..15] 与各阶段哈希
 * - SHA256withRSA：serversecret 签名与客户端证书校验
 */
object MoonCrypto {

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    /** 配对 AES 密钥：SHA256(salt16 + pin) 前 16 字节 */
    fun genAesKey(salt: ByteArray, pin: String): ByteArray =
        sha256(salt + pin.toByteArray(Charsets.UTF_8)).copyOf(16)

    fun aesEcbEncrypt(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    fun aesEcbDecrypt(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    fun signSha256Rsa(privateKey: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }

    fun verifySha256Rsa(certificate: X509Certificate, data: ByteArray, signature: ByteArray): Boolean =
        try {
            Signature.getInstance("SHA256withRSA").run {
                initVerify(certificate.publicKey)
                update(data)
                verify(signature)
            }
        } catch (_: Exception) {
            false
        }

    fun parseCert(pem: String): X509Certificate? = try {
        CertificateFactory.getInstance("X.509")
            .generateCertificate(pem.toByteArray(Charsets.UTF_8).inputStream()) as X509Certificate
    } catch (_: Exception) {
        null
    }

    fun parsePrivateKey(pem: String): PrivateKey? = try {
        val base64 = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val der = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    } catch (_: Exception) {
        null
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(clean[i * 2], 16) shl 4) or Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    fun bytesToHex(bytes: ByteArray, upper: Boolean = true): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0xF])
        }
        return if (upper) sb.toString().uppercase() else sb.toString()
    }
}
