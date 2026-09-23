package io.github.messagerelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateRenderTest {
    private val message = RelayMessage("com.tencent.mm", "微信", "张三", "明天 10 点开会", 0)

    @Test fun `unknown variables are kept as-is and reported instead of crashing`() {
        val template = MessageTemplate("{{bad}}", "{{body}}\n{{alsoBad}}")
        val rendered = template.renderDetailed("{{bad}}", message)
        assertEquals("{{bad}}", rendered.text)
        assertEquals(listOf("bad"), rendered.unsupportedVariables)
        assertEquals(listOf("bad", "alsoBad"), template.unsupportedVariables())
    }

    @Test fun `four generic presets have distinct bodies`() {
        val bodies = listOf("simple", TemplateCatalog.STANDARD_ID, "privacy", "raw")
            .map { TemplateCatalog.byId(it).body }
        assertEquals(4, bodies.distinct().size)
    }

    @Test fun `privacy preset hides notification content`() {
        val rendered = TemplateCatalog.byId("privacy").template().renderBody(message)
        assertFalse(rendered.contains("明天 10 点开会"))
        assertTrue(rendered.contains("隐藏"))
    }

    @Test fun `raw preset includes package name`() {
        val template = TemplateCatalog.byId("raw").template()
        assertTrue(template.renderBody(message).contains("com.tencent.mm"))
        assertTrue(template.unsupportedVariables().isEmpty())
    }

    @Test fun `supported variable set matches template data keys`() {
        val sample = RelayMessage("pkg", "app", "title", "body", 0)
        val probe = MessageTemplate()
        SUPPORTED_TEMPLATE_VARIABLES.forEach { key ->
            val rendered = probe.renderDetailed("{{$key}}", sample)
            assertTrue("变量 $key 应受支持，实际：${rendered.unsupportedVariables}", rendered.unsupportedVariables.isEmpty())
            assertFalse("变量 $key 不应原样保留", rendered.text.contains("{{$key}}"))
        }
    }

    @Test fun `canonical maps legacy aliases to standard and keeps custom ids`() {
        assertEquals(TemplateCatalog.STANDARD_ID, TemplateCatalog.canonical("wechat"))
        assertEquals(TemplateCatalog.STANDARD_ID, TemplateCatalog.canonical("general"))
        assertEquals(TemplateCatalog.STANDARD_ID, TemplateCatalog.canonical("qq"))
        assertEquals("custom_1", TemplateCatalog.canonical("custom_1"))
        assertEquals("标准模板", TemplateCatalog.displayName("qq"))
        assertEquals("隐私模板", TemplateCatalog.displayName("privacy"))
        assertEquals("custom_1", TemplateCatalog.displayName("custom_1"))
    }

    @Test fun `preset list covers generic and communication templates`() {
        assertEquals(listOf("simple", "standard", "privacy", "raw", "phone", "sms"), TemplateCatalog.presetIds)
        assertEquals(TemplateCatalog.presetIds, TemplateCatalog.presets().map { it.id })
    }
}
