import com.github.nepyh.rooter.module.notification.DeviceTokenService
import com.github.nepyh.rooter.module.notification.dto.DeviceTokenRegisterRequest
import com.github.nepyh.rooter.module.notification.exception.DeviceTokenValidationException
import com.github.nepyh.rooter.module.notification.model.DevicePlatform
import com.github.nepyh.rooter.module.notification.model.UserDeviceTokenTable
import com.github.nepyh.rooter.module.notification.push.PushMessage
import com.github.nepyh.rooter.module.notification.push.PushResult
import com.github.nepyh.rooter.module.storage.FileStorage
import com.github.nepyh.rooter.module.storage.UploadableFile
import com.github.nepyh.rooter.module.user.AuthService
import com.github.nepyh.rooter.module.user.ExposedUserRepo
import com.github.nepyh.rooter.module.user.UserService
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import java.sql.SQLException
import java.time.OffsetDateTime

/**
 * 기기 푸시 토큰 등록·삭제·발송 통합 테스트 (로컬 PostgreSQL 필요).
 * 접속 정보는 환경변수로 오버라이드 가능: TEST_JDBC_URL / TEST_DB_USER / TEST_DB_PASSWORD
 */
class DeviceTokenServiceTest : StringSpec({

    val testDbUrl = System.getenv("TEST_JDBC_URL") ?: "jdbc:postgresql://localhost:5432/planboard_test"
    val testDbUser = System.getenv("TEST_DB_USER") ?: "rooter_dev"
    val testDbPassword = System.getenv("TEST_DB_PASSWORD") ?: "wapapyrus"

    ensureDeviceTokenTestDatabase(testDbUrl, testDbUser, testDbPassword)

    val db = Database.connect(
        url = testDbUrl,
        driver = "org.postgresql.Driver",
        user = testDbUser,
        password = testDbPassword
    )

    beforeSpec {
        transaction(db) {
            exec("DROP TABLE IF EXISTS user_device_tokens CASCADE")
            exec("DROP TABLE IF EXISTS users CASCADE")
            SchemaUtils.create(UserTable, UserDeviceTokenTable)
        }
    }

    beforeEach {
        transaction(db) {
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

    fun tokensOf(userId: Int): List<String> = transaction(db) {
        UserDeviceTokenTable.selectAll()
            .where { UserDeviceTokenTable.userId eq userId }
            .map { it[UserDeviceTokenTable.token] }
    }

    fun request(token: String, platform: DevicePlatform = DevicePlatform.IOS) = DeviceTokenRegisterRequest(token, platform)

    "등록: 새 토큰을 저장하고 등록한 토큰·플랫폼을 돌려준다" {
        val userId = newUser("a@test.com")

        val response = DeviceTokenService(noPushSender).register(userId, request("ExponentPushToken[aaa]"))

        response.token shouldBe "ExponentPushToken[aaa]"
        response.platform shouldBe DevicePlatform.IOS
        tokensOf(userId) shouldContainExactly listOf("ExponentPushToken[aaa]")
    }

    "등록: 같은 토큰을 다시 보내면 행이 늘지 않고 마지막 사용 시각·플랫폼만 갱신된다" {
        val userId = newUser("a@test.com")
        val service = DeviceTokenService(noPushSender)
        service.register(userId, request("ExponentPushToken[aaa]"))
        val (createdBefore, seenBefore) = transaction(db) {
            UserDeviceTokenTable.selectAll().single().let { it[UserDeviceTokenTable.createdAt] to it[UserDeviceTokenTable.lastSeenAt] }
        }

        Thread.sleep(20)
        service.register(userId, request("ExponentPushToken[aaa]", DevicePlatform.ANDROID))

        transaction(db) {
            val row = UserDeviceTokenTable.selectAll().single()
            row[UserDeviceTokenTable.platform] shouldBe DevicePlatform.ANDROID
            row[UserDeviceTokenTable.createdAt].toInstant() shouldBe createdBefore.toInstant()
            (row[UserDeviceTokenTable.lastSeenAt] > seenBefore) shouldBe true
        }
    }

    "등록: 다른 계정이 쓰던 토큰이면 지금 계정으로 옮긴다 (같은 폰에서 계정 변경)" {
        val before = newUser("before@test.com")
        val after = newUser("after@test.com")
        val service = DeviceTokenService(noPushSender)
        service.register(before, request("ExponentPushToken[shared]"))

        service.register(after, request("ExponentPushToken[shared]"))

        tokensOf(before).shouldBeEmpty()
        tokensOf(after) shouldContainExactly listOf("ExponentPushToken[shared]")
    }

    "등록: ExpoPushToken 표기와 앞뒤 공백도 받는다" {
        val userId = newUser("a@test.com")

        DeviceTokenService(noPushSender).register(userId, request("  ExpoPushToken[bbb]  "))

        tokensOf(userId) shouldContainExactly listOf("ExpoPushToken[bbb]")
    }

    "등록: Expo 토큰 형식이 아니면 INVALID_PUSH_TOKEN" {
        val userId = newUser("a@test.com")
        val service = DeviceTokenService(noPushSender)
        val invalid = listOf(
            "",
            "abc",
            "ExponentPushToken[]",
            "ExponentPushToken[a b]",
            "FcmToken[aaa]",
            "ExponentPushToken[" + "a".repeat(240) + "]" // 258자
        )

        invalid.forEach { token ->
            val e = shouldThrow<DeviceTokenValidationException.InvalidPushTokenException> {
                service.register(userId, request(token))
            }
            e.code shouldBe "INVALID_PUSH_TOKEN"
        }
        tokensOf(userId).shouldBeEmpty()
    }

    "로그아웃: 그 사용자의 토큰만 전부 지운다" {
        val me = newUser("me@test.com")
        val other = newUser("other@test.com")
        val deviceTokenService = DeviceTokenService(noPushSender)
        deviceTokenService.register(me, request("ExponentPushToken[phone]"))
        deviceTokenService.register(me, request("ExponentPushToken[tablet]"))
        deviceTokenService.register(other, request("ExponentPushToken[other]"))
        val userRepo = ExposedUserRepo()
        val authService = AuthService(
            userRepo,
            UserService(userRepo, unusedFileStorage, testAgreementCatalog),
            deviceTokenService,
            "test-secret", "test-issuer", "", ""
        )

        authService.logout(me)

        tokensOf(me).shouldBeEmpty()
        tokensOf(other) shouldContainExactly listOf("ExponentPushToken[other]")
    }

    "발송: 사용자의 모든 기기로 보내고, 죽은 토큰은 지운다" {
        val userId = newUser("a@test.com")
        val sender = RecordingPushSender(invalidTokens = setOf("ExponentPushToken[deleted-app]"))
        val service = DeviceTokenService(sender)
        service.register(userId, request("ExponentPushToken[phone]"))
        service.register(userId, request("ExponentPushToken[deleted-app]"))
        val message = PushMessage(title = "곧 시작", body = "수학 공부 5분 전", data = mapOf("planTaskId" to "12"))

        val result = service.sendToUser(userId, message)

        sender.sent.single().first shouldContainExactlyInAnyOrder listOf("ExponentPushToken[phone]", "ExponentPushToken[deleted-app]")
        sender.sent.single().second shouldBe message
        result shouldBe PushResult(sent = 1, invalidTokens = listOf("ExponentPushToken[deleted-app]"))
        tokensOf(userId) shouldContainExactly listOf("ExponentPushToken[phone]")
    }

    "발송: 토큰이 없으면 발송기를 부르지 않는다" {
        val userId = newUser("a@test.com")
        val sender = RecordingPushSender()

        val result = DeviceTokenService(sender).sendToUser(userId, PushMessage("제목", "본문"))

        result shouldBe PushResult.EMPTY
        sender.sent.shouldBeEmpty()
    }

    "탈퇴: 유저를 지우면 토큰도 cascade 로 함께 지워진다" {
        val userId = newUser("a@test.com")
        DeviceTokenService(noPushSender).register(userId, request("ExponentPushToken[aaa]"))

        transaction(db) { UserRow[userId].delete() }

        transaction(db) { UserDeviceTokenTable.selectAll().count() } shouldBe 0L
    }
})

private val unusedFileStorage = object : FileStorage {
    override suspend fun upload(file: UploadableFile, directory: String) = error("사용되지 않아야 함")
    override suspend fun <T> readFile(
        fileKey: String,
        block: suspend (java.io.InputStream, String?, Long?) -> T
    ): T? = error("사용되지 않아야 함")
    override suspend fun getUrl(fileKey: String): String? = error("사용되지 않아야 함")
    override suspend fun delete(fileKey: String) = error("사용되지 않아야 함")
}

private fun ensureDeviceTokenTestDatabase(url: String, user: String, password: String) {
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
