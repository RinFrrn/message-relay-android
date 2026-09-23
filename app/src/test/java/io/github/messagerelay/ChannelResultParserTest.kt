package io.github.messagerelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelResultParserTest {
    @Test fun `filtered record exposes its reason`() {
        val raw = """[{"reason":"规则未命中或命中排除关键词"}]"""
        assertEquals("规则未命中或命中排除关键词", ChannelResultParser.filterReason(raw))
    }

    @Test fun `sent and failed records have no filter reason`() {
        val sent = """[{"channel":"Bark","success":true,"httpStatus":200,"retryable":false,"error":""}]"""
        val failed = """[{"reason":"网络连接失败","success":false}]"""
        assertNull(ChannelResultParser.filterReason(sent))
        assertNull(ChannelResultParser.filterReason(failed))
        assertNull(ChannelResultParser.filterReason("[]"))
        assertNull(ChannelResultParser.filterReason("not json"))
    }

    @Test fun `detail text includes channel status and reason`() {
        val raw = """[{"channel":"Bark","success":false,"httpStatus":500,"retryable":true,"error":"HTTP 500"}]"""
        val detail = ChannelResultParser.detailText(raw)
        assertTrue("渠道：Bark" in detail)
        assertTrue("状态：发送失败" in detail)
        assertTrue("原因：HTTP 500" in detail)
    }
}
