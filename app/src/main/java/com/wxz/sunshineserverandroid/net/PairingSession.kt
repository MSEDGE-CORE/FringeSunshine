package com.wxz.sunshineserverandroid.net

import com.wxz.sunshineserverandroid.ServerCore
import java.security.SecureRandom

/**
 * 一次配对流程的状态（对应 Sunshine nvhttp.cpp 的 pair_session_t）。
 *
 * 四阶段：getservercert -> clientchallenge -> serverchallengeresp -> clientpairingsecret
 */
class PairingSession {
    enum class Phase { NONE, GETSERVERCERT, CLIENTCHALLENGE, SERVERCHALLENGERESP, CLIENTPAIRINGSECRET }

    var phase = Phase.NONE
    var saltHex: String = ""
    var clientUniqueId: String = ""
    var clientCertPem: String = ""
    var clientCert: java.security.cert.X509Certificate? = null

    var aesKey: ByteArray? = null
    var serverSecret: ByteArray? = null
    var serverChallenge: ByteArray? = null

    /** serverchallengeresp 阶段收到的客户端哈希，最后阶段比对 */
    var clientHash: ByteArray? = null

    val cert: java.security.cert.X509Certificate
        get() = ServerCore.cert ?: error("server cert not loaded")

    val privateKey: java.security.PrivateKey
        get() = ServerCore.privateKey ?: error("server key not loaded")

    fun reset(saltHex: String, clientUniqueId: String, clientCertHex: String) {
        phase = Phase.NONE
        this.saltHex = saltHex
        this.clientUniqueId = clientUniqueId
        this.clientCertPem = if (clientCertHex.isBlank()) "" else String(MoonCrypto.hexToBytes(clientCertHex), Charsets.UTF_8)
        this.clientCert = if (clientCertPem.isBlank()) null else MoonCrypto.parseCert(clientCertPem)
        aesKey = null
        serverSecret = null
        serverChallenge = null
        clientHash = null
    }

    /** 阶段一：用用户输入的 PIN（Moonlight 客户端显示）派生 AES 密钥，返回服务端证书 PEM 的 hex（大写，与 Sunshine plaincert 一致） */
    fun getServerCert(pin: String): String {
        require(saltHex.length >= 32) { "salt too short" }
        val salt = MoonCrypto.hexToBytes(saltHex.substring(0, 32))
        aesKey = MoonCrypto.genAesKey(salt, pin)
        phase = Phase.GETSERVERCERT
        return MoonCrypto.bytesToHex(ServerCore.certPem.toByteArray(Charsets.UTF_8))
    }

    /**
     * 阶段二：解密客户端 challenge，附加服务端证书签名 + serversecret 后哈希，
     * 再拼接随机 serverchallenge 加密返回（hex 大写）。
     */
    fun clientChallenge(challengeHex: String): String {
        require(phase == Phase.GETSERVERCERT) { "out of order" }
        val key = aesKey ?: error("no aes key")
        val decrypted = MoonCrypto.aesEcbDecrypt(key, MoonCrypto.hexToBytes(challengeHex))
        val certSignature = cert.signature
        val secret = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = MoonCrypto.sha256(decrypted + certSignature + secret)
        val challenge = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val encrypted = MoonCrypto.aesEcbEncrypt(key, hash + challenge)
        serverSecret = secret
        serverChallenge = challenge
        phase = Phase.CLIENTCHALLENGE
        return MoonCrypto.bytesToHex(encrypted)
    }

    /** 阶段三：记录客户端哈希，返回 serversecret + SHA256withRSA(serversecret)（hex 大写） */
    fun serverChallengeResp(encryptedResponseHex: String): String {
        require(phase == Phase.CLIENTCHALLENGE) { "out of order" }
        val key = aesKey ?: error("no aes key")
        clientHash = MoonCrypto.aesEcbDecrypt(key, MoonCrypto.hexToBytes(encryptedResponseHex))
        val secret = serverSecret ?: error("no server secret")
        val sign = MoonCrypto.signSha256Rsa(privateKey, secret)
        phase = Phase.SERVERCHALLENGERESP
        return MoonCrypto.bytesToHex(secret + sign)
    }

    /**
     * 阶段四：校验客户端 pairingsecret = clientsecret(16) + RSA 签名。
     * 哈希比对 serverchallenge + 客户端证书签名 + clientsecret；签名用客户端证书公钥验证。
     */
    fun clientPairingSecret(pairingSecretHex: String): Boolean {
        require(phase == Phase.SERVERCHALLENGERESP) { "out of order" }
        val secretAndSign = MoonCrypto.hexToBytes(pairingSecretHex)
        require(secretAndSign.size > 16) { "pairing secret too short" }
        val clientSecret = secretAndSign.copyOfRange(0, 16)
        val sign = secretAndSign.copyOfRange(16, secretAndSign.size)
        val clientX509 = clientCert ?: return false
        val clientCertSignature = clientX509.signature
        val challenge = serverChallenge ?: return false
        val expectedHash = MoonCrypto.sha256(challenge + clientCertSignature + clientSecret)
        val receivedHash = clientHash ?: return false
        val sameHash = expectedHash.contentEquals(receivedHash)
        val verified = MoonCrypto.verifySha256Rsa(clientX509, clientSecret, sign)
        phase = Phase.CLIENTPAIRINGSECRET
        return sameHash && verified
    }
}
