package io.github.messagerelay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlankNotificationGuardTest {
    @Test fun `both title and body blank is a blank notification`() {
        assertTrue(isBlankNotificationContent(null, null))
        assertTrue(isBlankNotificationContent("", ""))
        assertTrue(isBlankNotificationContent("   ", ""))
        assertTrue(isBlankNotificationContent(null, "  "))
    }

    @Test fun `notification with any real content is kept`() {
        assertFalse(isBlankNotificationContent("微信 · 张三", null))
        assertFalse(isBlankNotificationContent(null, "你好"))
        assertFalse(isBlankNotificationContent("下载完成", ""))
        assertFalse(isBlankNotificationContent("", "该通知未提供正文"))
    }
}
