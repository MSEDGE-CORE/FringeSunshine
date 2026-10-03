package com.wxz.sunshineserverandroid.stream

import com.wxz.sunshineserverandroid.ServerCore
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 控制流加密（Sunshine SS_ENC_CONTROL_V2）：
 *
 * 明文 V2 头：{u16 LE type, u16 LE payloadLength, payload}
 * 加密帧：{u16 LE 0x0001, u16 LE length, u32 LE seq, tag[16], ciphertext}
 *   length = 4(seq) + 16(tag) + 明文长度
 * AES-GCM，密钥 = launch 的 rikey(16B)；
 * IV(12B) = seq 小端 4 字节 + 0 填充 + [10]='C'/'H' + [11]='C'
 *   （'C'=客户端发出，'H'=服务端发出，双方各自独立计数 seq）。
 */
object ControlCrypto {

    private const val TAG_LENGTH = 16

    /** 服务端 → 客户端。返回完整加密帧；seq 由调用方单调递增 */
    fun encrypt(riKey: ByteArray, seq: Int, type: Int, payload: ByteArray): ByteArray? {
        if (riKey.size != 16) return null
        val plain = ByteArray(4 + payload.size)
        put16(plain, 0, type)
        put16(plain, 2, payload.size)
        System.arraycopy(payload, 0, plain, 4, payload.size)

        val length = 4 + TAG_LENGTH + plain.size
        val out = ByteArray(8 + TAG_LENGTH + plain.size)
        put16(out, 0, TYPE_ENCRYPTED)
        put16(out, 2, length)
        put32(out, 4, seq)

        return try {
            val iv = ByteArray(12)
            iv[0] = (seq and 0xFF).toByte()
            iv[1] = ((seq shr 8) and 0xFF).toByte()
            iv[2] = ((seq shr 16) and 0xFF).toByte()
            iv[3] = ((seq shr 24) and 0xFF).toByte()
            iv[10] = 'H'.code.toByte()
            iv[11] = 'C'.code.toByte()

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(riKey, "AES"), GCMParameterSpec(128, iv))
            val sealed = cipher.doFinal(plain) // ciphertext || tag
            val cipherLength = sealed.size - TAG_LENGTH
            // Sunshine wire format is tag || ciphertext, while JCA returns ciphertext || tag.
            System.arraycopy(sealed, cipherLength, out, 8, TAG_LENGTH)
            System.arraycopy(sealed, 0, out, 8 + TAG_LENGTH, cipherLength)
            out
        } catch (_: Exception) {
            null
        }
    }

    /** 客户端 → 服务端。输入为完整加密帧，返回 (type, payload)；失败返回 null */
    fun decrypt(riKey: ByteArray, frame: ByteArray): Pair<Int, ByteArray>? {
        if (riKey.size != 16 || frame.size < 8 + TAG_LENGTH + 4) return null
        if (le16(frame, 0) != TYPE_ENCRYPTED) return null
        val length = le16(frame, 2)
        val seq = le32(frame, 4)
        if (length < 4 + TAG_LENGTH + 4) return null
        // length 覆盖 seq(4) + tag(16) + 明文，不含头部 type/length 4 字节
        // （moonlight ControlStream.c: encPacket->length = sizeof(seq)+TAG+sizeof(V2头)+paylen）
        if (4 + length != frame.size) {
            ServerCore.log("控制解密帧长不符：declared=$length frame=${frame.size} seq=$seq")
            return null
        }

        return try {
            val iv = ByteArray(12)
            iv[0] = (seq and 0xFF).toByte()
            iv[1] = ((seq shr 8) and 0xFF).toByte()
            iv[2] = ((seq shr 16) and 0xFF).toByte()
            iv[3] = ((seq shr 24) and 0xFF).toByte()
            iv[10] = 'C'.code.toByte()
            iv[11] = 'C'.code.toByte()

            val cipherLen = length - 4 - TAG_LENGTH
            if (cipherLen < 4) return null
            val jcaInput = ByteArray(cipherLen + TAG_LENGTH)
            // Sunshine wire format is tag || ciphertext; JCA expects ciphertext || tag.
            System.arraycopy(frame, 8 + TAG_LENGTH, jcaInput, 0, cipherLen)
            System.arraycopy(frame, 8, jcaInput, cipherLen, TAG_LENGTH)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(riKey, "AES"), GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(jcaInput) // {type, payloadLen, payload}
            if (plain.size < 4) return null
            val type = le16(plain, 0)
            val payloadLen = le16(plain, 2)
            if (4 + payloadLen != plain.size) return null
            Pair(type, plain.copyOfRange(4, plain.size))
        } catch (e: Exception) {
            ServerCore.log("控制解密 GCM 失败：seq=$seq len=${frame.size} ${e.javaClass.simpleName}")
            null
        }
    }

    private fun le16(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or
            ((buf[off + 1].toInt() and 0xFF) shl 8) or
            ((buf[off + 2].toInt() and 0xFF) shl 16) or
            ((buf[off + 3].toInt() and 0xFF) shl 24)

    private fun put16(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun put32(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v shr 8) and 0xFF).toByte()
        buf[off + 2] = ((v shr 16) and 0xFF).toByte()
        buf[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private const val TYPE_ENCRYPTED = 0x0001
}
