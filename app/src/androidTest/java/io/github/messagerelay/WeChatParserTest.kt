package io.github.messagerelay

import android.app.Notification
import android.app.Person
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WeChatParserTest {
    @Test fun messagingStylePersonProvidesPrivateSender() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        val person = Person.Builder().setName("张三").build()
        val notification = Notification.Builder(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext, "test")
            .setStyle(Notification.MessagingStyle(person).addMessage("你好", 1_000, person))
            .build()
        val info = WeChatNotificationParser.info(notification.extras)
        assertEquals("张三", info.senderName)
        assertEquals(WeChatFieldSource.MESSAGING_PERSON, info.senderSource)
    }

    @Test fun conversationTitleProvidesGroupNameAndPrefixFallbackProvidesSender() {
        val notification = Notification.Builder(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext, "test")
            .setContentTitle("测试群")
            .setContentText("李四：你好")
            .setSubText("测试群")
            .build()
        val info = WeChatNotificationParser.info(notification.extras)
        assertEquals("测试群", info.conversationName)
        assertEquals("李四", info.senderName)
        assertTrue(info.body.orEmpty().contains("李四：你好"))
        assertEquals(WeChatFieldSource.BODY_PREFIX, info.senderSource)
    }

    @Test fun emptyNotificationIsTreatedAsBlankAndRejected() {
        val notification = Notification.Builder(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        org.junit.Assert.assertNull(WeChatNotificationParser.parse(notification.extras))
        org.junit.Assert.assertNull(NotificationContentExtractor.extract(notification.extras))
    }

    @Test fun titleOnlyNotificationKeepsPlaceholderBody() {
        val notification = Notification.Builder(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("系统通知")
            .build()
        val content = NotificationContentExtractor.extract(notification.extras)
        org.junit.Assert.assertNotNull(content)
        assertEquals("系统通知", content?.title)
        assertEquals("该通知未提供正文", content?.body)
    }
}
