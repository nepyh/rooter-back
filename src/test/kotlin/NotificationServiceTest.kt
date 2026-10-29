import com.github.nepyh.rooter.module.notification.DeviceTokenService
import com.github.nepyh.rooter.module.notification.NotificationService
import com.github.nepyh.rooter.module.notification.NotificationSettingService
import com.github.nepyh.rooter.module.notification.dto.DeviceTokenRegisterRequest
import com.github.nepyh.rooter.module.notification.dto.NotificationSettingsResponse
import com.github.nepyh.rooter.module.notification.dto.NotificationSettingsUpdateRequest
import com.github.nepyh.rooter.module.notification.exception.NotificationException
import com.github.nepyh.rooter.module.notification.model.DevicePlatform
import com.github.nepyh.rooter.module.notification.model.NotificationSettingTable
import com.github.nepyh.rooter.module.notification.model.NotificationTable
import com.github.nepyh.rooter.module.notification.model.NotificationType
import com.github.nepyh.rooter.module.notification.model.UserDeviceTokenTable
import com.github.nepyh.rooter.module.notification.push.PushMessage
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.sql.DriverManager
import java.sql.SQLException
import java.time.OffsetDateTime

/**
 * 알림함·notify()·알림 설정 통합 테스트 (로컬 PostgreSQL 필요).
 * 접속 정보는 환경변수로 오버라이드 가능: TEST_JDBC_URL / TEST_DB_USER / TEST_DB_PASSWORD
 */
class NotificationServiceTest : StringSpec({

    val testDbUrl = System.getenv("TEST_JDBC_URL") ?: "jdbc:postgresql://localhost:5432/planboard_test"
    val testDbUser = System.getenv("TEST_DB_USER") ?: "rooter_dev"
    val testDbPassword = System.getenv("TEST_DB_PASSWORD") ?: "wapapyrus"

    ensureNotificationTestDatabase(testDbUrl, testDbUser, testDbPassword)

    val db = Database.connect(
        url = testDbUrl,
        driver = "org.postgresql.Driver",
        user = testDbUser,
        password = testDbPassword
    )

    beforeSpec {
        transaction(db) {
            exec("DROP TABLE IF EXISTS notifications CASCADE")
            exec("DROP TABLE IF EXISTS notification_settings CASCADE")
            exec("DROP TABLE IF EXISTS user_device_tokens CASCADE")
            exec("DROP TABLE IF EXISTS users CASCADE")
            SchemaUtils.create(UserTable, UserDeviceTokenTable, NotificationSettingTable, NotificationTable)
        }
    }

    beforeEach {
        transaction(db) {
            NotificationTable.deleteAll()
            NotificationSettingTable.deleteAll()
            UserDeviceTokenTable.deleteAll()
            UserTable.deleteAll()
        }
    }

    fun newUser(email: String): Int = transaction(db) {
        UserRow.new {
            this.email = email
            username = "tester"
            password = "x"
            createdAt = OffsetDateTime.now()
        }.id.value
    }

    val settingService = NotificationSettingService()

    fun serviceWith(sender: RecordingPushSender = RecordingPushSender()): Pair<NotificationService, DeviceTokenService> {
        val deviceTokenService = DeviceTokenService(sender)
        return NotificationService(deviceTokenService, settingService) to deviceTokenService
    }

    fun DeviceTokenService.registerPhone(userId: Int, token: String = "ExponentPushToken[phone-$userId]") =
        register(userId, DeviceTokenRegisterRequest(token, DevicePlatform.IOS))

    suspend fun NotificationService.remind(userId: Int, title: String = "곧 시작", planTaskId: Int = 12) =
        notify(userId, NotificationType.TASK_REMINDER, title, "수학 공부 5분 전", mapOf("planTaskId" to planTaskId.toString()))

    "notify: 알림함에 저장하고, 모든 기기로 notificationId 를 실은 푸시를 보낸다" {
        val userId = newUser("a@test.com")
        val sender = RecordingPushSender()
        val (service, devices) = serviceWith(sender)
        devices.registerPhone(userId)

        val id = service.remind(userId)

        val saved = service.list(userId).notifications.single()
        saved.id shouldBe id
        saved.type shouldBe NotificationType.TASK_REMINDER
        saved.title shouldBe "곧 시작"
        saved.data shouldBe mapOf("planTaskId" to "12")
        saved.read shouldBe false
        sender.sent.single().second shouldBe PushMessage(
            "곧 시작", "수학 공부 5분 전", mapOf("planTaskId" to "12", "notificationId" to id.toString())
        )
    }

    "notify: 할일 알림을 끈 사용자는 알림함에만 남고 푸시는 보내지 않는다" {
        val userId = newUser("a@test.com")
        val sender = RecordingPushSender()
        val (service, devices) = serviceWith(sender)
        devices.registerPhone(userId)
        settingService.update(userId, NotificationSettingsUpdateRequest(taskReminderEnabled = false))

        service.remind(userId)

        service.list(userId).notifications.size shouldBe 1
        sender.sent.shouldBeEmpty()
    }

    "notify: 등록된 기기가 없어도 알림함에는 남는다" {
        val userId = newUser("a@test.com")
        val sender = RecordingPushSender()

        serviceWith(sender).first.remind(userId)

        serviceWith().first.unreadCount(userId).unreadCount shouldBe 1L
        sender.sent.shouldBeEmpty()
    }

    "notify: 제목·본문이 DDL 길이를 넘으면 잘라서 저장한다" {
        val userId = newUser("a@test.com")
        val (service, _) = serviceWith()

        service.notify(userId, NotificationType.TASK_REMINDER, "가".repeat(150), "나".repeat(600))

        val saved = service.list(userId).notifications.single()
        saved.title.length shouldBe 100
        saved.body.length shouldBe 500
    }

    "목록: 본인 알림만 최신순으로, cursor 로 다음 페이지를 이어 받는다" {
        val me = newUser("me@test.com")
        val other = newUser("other@test.com")
        val (service, _) = serviceWith()
        val ids = (1..5).map { service.remind(me, title = "알림 $it") }
        service.remind(other)

        val first = service.list(me, limit = 2)
        first.notifications.map { it.id } shouldContainExactly listOf(ids[4], ids[3])
        first.nextCursor shouldBe ids[3]

        val second = service.list(me, cursor = first.nextCursor, limit = 2)
        second.notifications.map { it.id } shouldContainExactly listOf(ids[2], ids[1])

        val last = service.list(me, cursor = second.nextCursor, limit = 2)
        last.notifications.map { it.id } shouldContainExactly listOf(ids[0])
        last.nextCursor.shouldBeNull()
    }

    "목록: data 가 깨진 행이 있어도 목록은 빈 data 로 내려간다" {
        val userId = newUser("a@test.com")
        val (service, _) = serviceWith()
        val id = service.remind(userId)
        transaction(db) { NotificationTable.update({ NotificationTable.id eq id }) { it[data] = "not json" } }

        service.list(userId).notifications.single().data shouldBe emptyMap()
    }

    "읽음: 하나만 읽음 처리하고, 다시 불러도 처음 읽은 시각을 유지한다" {
        val userId = newUser("a@test.com")
        val (service, _) = serviceWith()
        val first = service.remind(userId)
        service.remind(userId)

        val read = service.markRead(userId, first)
        read.read shouldBe true
        read.readAt.shouldNotBeNull()
        service.unreadCount(userId).unreadCount shouldBe 1L

        service.markRead(userId, first).readAt shouldBe read.readAt
    }

    "읽음: 남의 알림이거나 없는 알림이면 NOTIFICATION_NOT_FOUND 이고 상대 알림은 그대로다" {
        val me = newUser("me@test.com")
        val other = newUser("other@test.com")
        val (service, _) = serviceWith()
        val othersId = service.remind(other)

        shouldThrow<NotificationException.NotFoundException> { service.markRead(me, othersId) }.code shouldBe "NOTIFICATION_NOT_FOUND"
        shouldThrow<NotificationException.NotFoundException> { service.markRead(me, othersId + 1000) }
        service.unreadCount(other).unreadCount shouldBe 1L
    }

    "모두 읽음: 본인의 안 읽은 알림만 바꾸고 바뀐 개수를 돌려준다" {
        val me = newUser("me@test.com")
        val other = newUser("other@test.com")
        val (service, _) = serviceWith()
        val alreadyRead = service.remind(me)
        service.markRead(me, alreadyRead)
        service.remind(me)
        service.remind(me)
        service.remind(other)

        service.markAllRead(me).updatedCount shouldBe 2
        service.unreadCount(me).unreadCount shouldBe 0L
        service.unreadCount(other).unreadCount shouldBe 1L
    }

    "설정: 행이 없으면 기본값(켜짐, 5분 전)이고 행도 만들지 않는다" {
        val userId = newUser("a@test.com")

        settingService.get(userId) shouldBe NotificationSettingsResponse(taskReminderEnabled = true, taskReminderLeadMinutes = 5)
        settingService.update(userId, NotificationSettingsUpdateRequest()) shouldBe NotificationSettingService.DEFAULT
        transaction(db) { NotificationSettingTable.selectAll().count() } shouldBe 0L
    }

    "설정: 보낸 필드만 바꾸고 나머지는 유지한다" {
        val userId = newUser("a@test.com")

        settingService.update(userId, NotificationSettingsUpdateRequest(taskReminderLeadMinutes = 10)) shouldBe
            NotificationSettingsResponse(taskReminderEnabled = true, taskReminderLeadMinutes = 10)
        settingService.update(userId, NotificationSettingsUpdateRequest(taskReminderEnabled = false)) shouldBe
            NotificationSettingsResponse(taskReminderEnabled = false, taskReminderLeadMinutes = 10)
        settingService.get(userId) shouldBe NotificationSettingsResponse(taskReminderEnabled = false, taskReminderLeadMinutes = 10)
        transaction(db) { NotificationSettingTable.selectAll().count() } shouldBe 1L
    }

    "설정: 알림 시각이 0~60분 밖이면 INVALID_LEAD_MINUTES 이고 저장하지 않는다" {
        val userId = newUser("a@test.com")

        listOf(-1, 61).forEach { minutes ->
            shouldThrow<NotificationException.InvalidLeadMinutesException> {
                settingService.update(userId, NotificationSettingsUpdateRequest(taskReminderLeadMinutes = minutes))
            }.code shouldBe "INVALID_LEAD_MINUTES"
        }
        settingService.update(userId, NotificationSettingsUpdateRequest(taskReminderLeadMinutes = 0)).taskReminderLeadMinutes shouldBe 0
        settingService.update(userId, NotificationSettingsUpdateRequest(taskReminderLeadMinutes = 60)).taskReminderLeadMinutes shouldBe 60
    }

    "탈퇴: 유저를 지우면 알림·설정도 cascade 로 함께 지워진다" {
        val userId = newUser("a@test.com")
        serviceWith().first.remind(userId)
        settingService.update(userId, NotificationSettingsUpdateRequest(taskReminderEnabled = false))

        transaction(db) { UserRow[userId].delete() }

        transaction(db) {
            NotificationTable.selectAll().count() shouldBe 0L
            NotificationSettingTable.selectAll().count() shouldBe 0L
        }
    }
})

private fun ensureNotificationTestDatabase(url: String, user: String, password: String) {
    val dbName = url.substringAfterLast("/")
    val adminUrl = url.substringBeforeLast("/") + "/postgres"
    try {
        DriverManager.getConnection(adminUrl, user, password).use { conn ->
            conn.createStatement().use { it.execute("CREATE DATABASE $dbName") }
        }
    } catch (e: SQLException) {
        // 42P04: duplicate_database — 이미 존재하면 통과
        if (e.sqlState != "42P04") throw e
    }
}
