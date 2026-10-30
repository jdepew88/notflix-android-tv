package com.notflix.tv.playback

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * What this TV can decode, in the shape the media engine's PlaybackDecider expects
 * (ClientCapabilities in services/media-engine/.../Playback/Models.cs).
 *
 * Only hardware video decoders are reported: a 4K stream on a software decoder stutters on TV
 * chips, so the engine should rather convert it. Audio counts when the TV decodes it or can pass
 * it through to an HDMI/eARC receiver.
 *
 * `direct*` = played from the original file (Direct Play); `mse*` = codecs the engine may copy
 * into its fragmented-MP4 HLS stream (Remux / audio-only conversion) because ExoPlayer plays them
 * there. DTS and TrueHD are left out of the fMP4 list: FFmpeg's MP4 muxer support for them is
 * experimental, so the engine converts those to AAC when it must repackage the file.
 */
data class DeviceCapabilities(
    val directVideo: List<String>,
    val directAudio: List<String>,
    val mseVideo: List<String>,
    val mseAudio: List<String>,
    val tenBit: List<String>,
    val hdrDisplay: Boolean,
    val hdrTypes: List<String>,
    val maxHeight: Int,
    val decoders: List<String>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putJsonArray("directVideo") { directVideo.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        putJsonArray("directAudio") { directAudio.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        putJsonArray("mseVideo") { mseVideo.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        putJsonArray("mseAudio") { mseAudio.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        putJsonArray("tenBit") { tenBit.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        put("hdrDisplay", hdrDisplay)
        put("mse", true)
        put("nativeHls", true)
        put("maxHeight", maxHeight)
    }

    companion object {
        @Volatile private var cached: DeviceCapabilities? = null

        fun get(context: Context): DeviceCapabilities = cached ?: detect(context.applicationContext).also { cached = it }

        private data class VideoProbe(val codec: String, val mime: String, val tenBitProfiles: IntArray)

        private val VIDEO = listOf(
            VideoProbe("h264", "video/avc", intArrayOf()),
            VideoProbe("hevc", "video/hevc", intArrayOf(CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCProfileMain10HDR10)),
            VideoProbe("vp9", "video/x-vnd.on2.vp9", intArrayOf(CodecProfileLevel.VP9Profile2, CodecProfileLevel.VP9Profile2HDR)),
            VideoProbe("av1", "video/av01", if (Build.VERSION.SDK_INT >= 29) intArrayOf(CodecProfileLevel.AV1ProfileMain10, CodecProfileLevel.AV1ProfileMain10HDR10) else intArrayOf()),
            VideoProbe("vp8", "video/x-vnd.on2.vp8", intArrayOf()),
        )

        /** Engine codec name → MIME type of a decoder, and the AudioFormat encoding for passthrough. */
        private val AUDIO = listOf(
            Triple("aac", "audio/mp4a-latm", null),
            Triple("mp3", "audio/mpeg", null),
            Triple("opus", "audio/opus", null),
            Triple("flac", "audio/flac", null),
            Triple("vorbis", "audio/vorbis", null),
            Triple("ac3", "audio/ac3", AudioFormat.ENCODING_AC3),
            Triple("eac3", "audio/eac3", AudioFormat.ENCODING_E_AC3),
            Triple("dts", "audio/vnd.dts", AudioFormat.ENCODING_DTS),
            Triple("truehd", "audio/true-hd", if (Build.VERSION.SDK_INT >= 28) AudioFormat.ENCODING_DOLBY_TRUEHD else null),
        )

        private val FMP4_AUDIO = setOf("aac", "mp3", "opus", "flac", "ac3", "eac3")

        private fun detect(context: Context): DeviceCapabilities {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
            val decoderNames = mutableListOf<String>()

            val video = mutableListOf<String>()
            val tenBit = mutableListOf<String>()
            var maxDecodeHeight = 0
            for (probe in VIDEO) {
                val hw = codecs.filter { info -> info.supportedTypes.any { it.equals(probe.mime, true) } && isHardware(info) }
                if (hw.isEmpty()) continue
                video += probe.codec
                hw.forEach { decoderNames += "${probe.codec}: ${it.name}" }
                val caps = hw.mapNotNull { runCatching { it.getCapabilitiesForType(probe.mime) }.getOrNull() }
                if (probe.tenBitProfiles.isNotEmpty() && caps.any { c -> c.profileLevels.any { it.profile in probe.tenBitProfiles } }) {
                    tenBit += probe.codec
                }
                val height = caps.mapNotNull { c -> c.videoCapabilities?.supportedHeights?.upper }.maxOrNull() ?: 0
                val fourK = caps.any { c -> runCatching { c.videoCapabilities?.isSizeSupported(3840, 2160) == true }.getOrDefault(false) }
                maxDecodeHeight = maxOf(maxDecodeHeight, if (fourK) 2160 else minOf(height, 2160))
            }
            if ("h264" !in video) video.add(0, "h264") // every Android TV device decodes H.264

            val audio = mutableListOf<String>()
            for ((codec, mime, encoding) in AUDIO) {
                val decodes = codecs.any { info -> info.supportedTypes.any { it.equals(mime, true) } }
                val passthrough = encoding != null && supportsPassthrough(encoding)
                if (decodes || passthrough) audio += codec
            }
            if ("aac" !in audio) audio.add(0, "aac")

            val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
            val hdrTypes = runCatching { display?.hdrCapabilities?.supportedHdrTypes?.toList() ?: emptyList() }.getOrDefault(emptyList())
            val displayHeight = runCatching {
                display?.supportedModes?.maxOfOrNull { minOf(it.physicalHeight, it.physicalWidth) } ?: 1080
            }.getOrDefault(1080)
            val maxHeight = when {
                maxDecodeHeight <= 0 -> minOf(displayHeight, 1080)
                else -> minOf(maxOf(displayHeight, 720), maxDecodeHeight)
            }.let { if (it >= 2100) 2160 else if (it >= 1400) 1440 else if (it >= 1000) 1080 else 720 }

            return DeviceCapabilities(
                directVideo = video,
                directAudio = audio,
                mseVideo = video.filter { it != "vp8" },
                mseAudio = audio.filter { it in FMP4_AUDIO },
                tenBit = tenBit,
                hdrDisplay = hdrTypes.isNotEmpty(),
                hdrTypes = hdrTypes.map(::hdrName),
                maxHeight = maxHeight,
                decoders = decoderNames,
            )
        }

        private fun isHardware(info: MediaCodecInfo): Boolean {
            if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated && !info.isAlias
            val name = info.name.lowercase()
            return !(name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.contains(".sw.") || name.endsWith(".sw"))
        }

        private fun supportsPassthrough(encoding: Int): Boolean {
            if (Build.VERSION.SDK_INT < 29) return false
            return runCatching {
                val format = AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(48_000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
                    .build()
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
                @Suppress("DEPRECATION")
                AudioTrack.isDirectPlaybackSupported(format, attrs)
            }.getOrDefault(false)
        }

        private fun hdrName(type: Int): String = when (type) {
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
            Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
            Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
            4 -> "HDR10+"
            else -> "HDR type $type"
        }
    }
}
