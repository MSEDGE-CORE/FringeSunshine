package com.wxz.sunshineserverandroid.stream

/**
 * 一次流会话的配置（来自 HTTP launch 参数 + RTSP ANNOUNCE 参数）。
 */
class StreamConfig {
    // launch 参数
    var appId = 1
    var width = 1280
    var height = 720
    var fps = 30
    var rikeyHex: String = ""
    var rikeyIdHex: String = ""

    // ANNOUNCE 参数
    var packetSize = 1392
    var bitrateKbps = 8000
    var audioChannels = 2
    var audioPacketDurationMs = 5
    var clientUniqueId: String = ""

    companion object {
        /** 解析 /launch?... 的参数：mode=WxHxFPS、rikey、appid 等 */
        fun fromLaunchArgs(args: Map<String, String>): StreamConfig {
            val config = StreamConfig()
            config.appId = args["appid"]?.toIntOrNull() ?: 1
            config.rikeyHex = args["rikey"] ?: ""
            config.rikeyIdHex = args["rikeyid"] ?: ""
            val mode = args["mode"]
            if (!mode.isNullOrEmpty()) {
                val m = mode.split("x")
                if (m.size == 3) {
                    config.width = m[0].toIntOrNull() ?: config.width
                    config.height = m[1].toIntOrNull() ?: config.height
                    config.fps = m[2].toIntOrNull() ?: config.fps
                }
            }
            return config
        }

        /** 解析 ANNOUNCE 负载里的 x-nv-* 参数（覆盖默认值） */
        fun applyAnnounceArgs(config: StreamConfig, payload: String) {
            for (line in payload.split("\n")) {
                val trimmed = line.trim()
                if (!trimmed.startsWith("a=")) continue
                val idx = trimmed.indexOf(':')
                if (idx == -1) continue
                val name = trimmed.substring(2, idx).trim()
                val value = trimmed.substring(idx + 1).trim()
                when (name) {
                    "x-nv-video[0].clientViewportWd" -> config.width = value.toIntOrNull() ?: config.width
                    "x-nv-video[0].clientViewportHt" -> config.height = value.toIntOrNull() ?: config.height
                    "x-nv-video[0].maxFPS" -> config.fps = value.toIntOrNull() ?: config.fps
                    "x-nv-video[0].packetSize" -> config.packetSize = value.toIntOrNull() ?: config.packetSize
                    "x-nv-vqos[0].bw.maximumBitrateKbps" -> config.bitrateKbps = value.toIntOrNull() ?: config.bitrateKbps
                    "x-nv-audio.surround.numChannels" -> config.audioChannels = value.toIntOrNull() ?: 2
                    "x-nv-aqos.packetDuration" -> config.audioPacketDurationMs = value.toIntOrNull() ?: 5
                    "s" -> config.clientUniqueId = value
                }
            }
        }
    }
}
