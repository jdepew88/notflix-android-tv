package com.notflix.tv.playback

/** One subtitle cue, in seconds on the source file's timeline. */
data class Cue(val start: Double, val end: Double, val text: String)

/**
 * Minimal WebVTT parser for the engine's subtitle extraction output (same rules as parseVtt() in
 * src/lib/playback-client.ts): timings, multi-line text, tags stripped.
 */
object Vtt {
    fun parse(text: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        for (block in text.replace("\r", "").split(Regex("\n\n+"))) {
            val lines = block.split("\n")
            val idx = lines.indexOfFirst { "-->" in it }
            if (idx < 0) continue
            val (a, b) = lines[idx].split("-->", limit = 2)
            val start = time(a)
            val end = time(b.trim().split(Regex("\\s+"))[0])
            val body = lines.drop(idx + 1).joinToString("\n").replace(Regex("<[^>]+>"), "").trim()
            if (body.isNotEmpty() && start != null && end != null) cues += Cue(start, end, decodeEntities(body))
        }
        return cues.sortedBy { it.start }
    }

    private fun time(t: String): Double? {
        val parts = t.trim().split(":").map { it.toDoubleOrNull() ?: return null }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> null
        }
    }

    private fun decodeEntities(s: String) = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&amp;", "&")

    /**
     * Text to show at [position] (seconds on the displayed timeline). Engine cues are on the source
     * timeline, which starts at [sourceStartTime] (non-zero for some MPEG-TS files).
     */
    fun active(cues: List<Cue>, position: Double, sourceStartTime: Double, offsetSeconds: Double = 0.0): String? {
        val t = position + sourceStartTime - offsetSeconds
        val hits = cues.filter { t >= it.start && t < it.end }
        return hits.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.text }
    }
}
