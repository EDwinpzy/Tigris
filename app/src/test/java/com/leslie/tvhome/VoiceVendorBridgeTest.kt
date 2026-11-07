package com.leslie.tvhome

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceVendorBridgeTest {
    @Test
    fun latestTranscriptUsesLastNonEmptyVendorResult() {
        val trace = """
            2026-10-31 17:00:00_asr识别结果:旧问题
            2026-10-31 17:01:00_asr识别结果:
            2026-10-31 17:02:00_asr识别结果:今天天气怎么样
        """.trimIndent()

        assertEquals("今天天气怎么样", VoiceVendorBridge.latestTranscript(trace))
    }

    @Test
    fun parsesVendorAsrLogLine() {
        val line =
            "I/[VoiceService\$initVoiceListener\$1](26266): " +
                "onAsr asr is 今天天气怎么样- isFinished is false,mAsrText=今天天气怎么样"

        assertEquals("今天天气怎么样", VoiceVendorBridge.parseAsrText(line))
    }

    @Test
    fun writeTextProbeIsAcceptedForAutomatedVendorTests() {
        val line = "I/[JGVoiceImpl](26266): writeText: “音量调到50”"

        assertEquals("音量调到50", VoiceVendorBridge.parseVendorText(line))
    }
}
