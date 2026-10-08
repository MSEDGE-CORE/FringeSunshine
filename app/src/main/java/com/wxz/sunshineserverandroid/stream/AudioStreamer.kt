package com.wxz.sunshineserverandroid.stream

import android.annotation.SuppressLint
import android.media.*
import android.media.projection.MediaProjection
import com.wxz.sunshineserverandroid.ServerCore
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 音频通道：AudioPlaybackCapture（需 MediaProjection）-> MediaCodec Opus -> RTP -> UDP 48000。
 *
 * 包格式：[RTP 12B: 0x80, ptype 97, seq BE, ts BE(48kHz 采样数), ssrc 0][Opus 帧]
 */
class AudioStreamer(
    private val session: StreamSession,
    private val projection: MediaProjection
) : Thread("audio-streamer") {

    private var audioRecord: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private val stopped = AtomicBoolean(false)

    private var sequence = 0
    private var timestamp = 0
    private var encryptionWarningLogged = false

    /** 客户端离开即停：暂停 AudioPlaybackCapture（编码器保持存活等 resume） */
    @Volatile
    private var capturePaused = false

    /** 客户端离开：停录音；音频线程进入 200ms 轮询等待，避免 read<=0 热转 */
    fun pauseCapture() {
        capturePaused = true
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
    }

    /** resume：重新开录（AudioRecord stop 后可反复 startRecording） */
    fun resumeCapture() {
        capturePaused = false
        try {
            audioRecord?.startRecording()
        } catch (e: Exception) {
            ServerCore.log("恢复音频采集失败: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
    }

    override fun run() {
        try {
            streamLoop()
        } catch (e: Exception) {
            if (!stopped.get()) ServerCore.log("音频线程异常退出: ${e.javaClass.simpleName}: ${e.message}")
        }
        release()
    }

    @SuppressLint("MissingPermission")
    private fun streamLoop() {
        if (ServerCore.appContext.checkPermission(
                android.Manifest.permission.RECORD_AUDIO, android.os.Process.myPid(), android.os.Process.myUid()
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            ServerCore.log("未授予麦克风权限，跳过音频采集（视频不受影响）")
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE * CHANNELS * 2))
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        val encoderFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        encoder = codec
        codec.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        audioRecord?.startRecording()
        ServerCore.log("音频编码器就绪：Opus 48kHz 立体声")

        val pcmBuf = ByteArray(SAMPLE_RATE * CHANNELS * 2 / 10) // 100ms 缓冲读取
        val info = MediaCodec.BufferInfo()

        while (!stopped.get()) {
            if (capturePaused) {
                drainOutput(codec, info)
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }
            // 读 PCM
            val read = audioRecord?.read(pcmBuf, 0, pcmBuf.size) ?: -1
            if (read <= 0) {
                drainOutput(codec, info)
                continue
            }

            // 分块喂给编码器：输入缓冲容量可能小于一次读取量（如 4KB），
            // 整块 put 会 BufferOverflowException（message 为 null）
            var offset = 0
            val basePts = System.nanoTime() / 1000
            while (offset < read && !stopped.get()) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex < 0) break
                val inBuf: ByteBuffer? = codec.getInputBuffer(inIndex)
                if (inBuf == null) break
                inBuf.clear()
                val chunk = minOf(read - offset, inBuf.remaining())
                inBuf.put(pcmBuf, offset, chunk)
                codec.queueInputBuffer(
                    inIndex, 0, chunk,
                    basePts + offset * 1_000_000L / (SAMPLE_RATE * CHANNELS * 2), 0
                )
                offset += chunk
                drainOutput(codec, info)
            }
        }
    }

    private fun drainOutput(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (index < 0) continue

            val buffer = codec.getOutputBuffer(index) ?: run {
                codec.releaseOutputBuffer(index, false)
                continue
            }
            val size = info.size
            if (size > 0) {
                val opus = ByteArray(size).also { buffer.get(it, 0, size) }
                sendPacket(opus, size)
                // 由编码器 pts 推进 RTP 时间戳（us -> 48kHz 采样数）
                timestamp = ((info.presentationTimeUs * 48) / 1000).toInt()
            }
            codec.releaseOutputBuffer(index, false)
        }
    }

    private fun sendPacket(opus: ByteArray, length: Int) {
        val peer: InetSocketAddress = session.audioPeer ?: return
        val socket = session.audioSocket ?: return
        val audioPayload = if (session.audioEncryptionEnabled) {
            encryptAudio(opus) ?: return
        } else {
            opus
        }
        val packet = ByteArray(12 + audioPayload.size)
        packet[0] = 0x80.toByte()
        packet[1] = 97.toByte()
        packet[2] = ((sequence shr 8) and 0xFF).toByte()
        packet[3] = (sequence and 0xFF).toByte()
        packet[4] = ((timestamp shr 24) and 0xFF).toByte()
        packet[5] = ((timestamp shr 16) and 0xFF).toByte()
        packet[6] = ((timestamp shr 8) and 0xFF).toByte()
        packet[7] = (timestamp and 0xFF).toByte()
        System.arraycopy(audioPayload, 0, packet, 12, audioPayload.size)
        sequence = (sequence + 1) and 0xFFFF
        try {
            socket.send(DatagramPacket(packet, packet.size, peer.address, peer.port))
        } catch (_: Exception) {
        }
    }

    private fun encryptAudio(opus: ByteArray): ByteArray? {
        val key = session.riKey
        val idHex = session.config.rikeyIdHex
        if (key == null || idHex.length < 8) {
            if (!encryptionWarningLogged) {
                encryptionWarningLogged = true
                ServerCore.log("音频加密已协商但缺少 rikey/rikeyid，跳过音频包")
            }
            return null
        }
        return try {
            val riKeyId = idHex.substring(0, 8).toLong(16).toInt()
            val counter = riKeyId + sequence
            val iv = ByteArray(16)
            iv[0] = (counter ushr 24).toByte()
            iv[1] = (counter ushr 16).toByte()
            iv[2] = (counter ushr 8).toByte()
            iv[3] = counter.toByte()
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv)
            )
            cipher.doFinal(opus)
        } catch (e: Exception) {
            if (!encryptionWarningLogged) {
                encryptionWarningLogged = true
                ServerCore.log("音频加密失败: ${e.javaClass.simpleName}: ${e.message}")
            }
            null
        }
    }

    fun shutdown() {
        if (!stopped.compareAndSet(false, true)) return
        interrupt()
    }

    private fun release() {
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        try {
            encoder?.stop()
        } catch (_: Exception) {
        }
        try {
            encoder?.release()
        } catch (_: Exception) {
        }
    }
}
