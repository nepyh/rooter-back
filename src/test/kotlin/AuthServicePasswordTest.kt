import com.auth0.jwt.JWT
import com.github.nepyh.rooter.module.storage.FileStorage
import com.github.nepyh.rooter.module.storage.UploadableFile
import com.github.nepyh.rooter.module.notification.DeviceTokenService
import com.github.nepyh.rooter.module.notification.dto.DeviceTokenRegisterRequest
import com.github.nepyh.rooter.module.notification.model.DevicePlatform
import com.github.nepyh.rooter.module.notification.model.UserDeviceTokenTable
import com.github.nepyh.rooter.module.user.AuthService
import com.github.nepyh.rooter.module.user.UserJwtValidator
import com.github.nepyh.rooter.module.user.ExposedUserRepo
import com.github.nepyh.rooter.module.user.UserService
import com.github.nepyh.rooter.module.user.dto.ChangePasswordRequest
import com.github.nepyh.rooter.module.user.dto.UserLoginRequest
import com.github.nepyh.rooter.module.user.exception.UserValidationException
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.server.auth.jwt.JWTCredential
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.sql.DriverManager
import java.sql.SQLException
import java.time.OffsetDateTime

/**
 * 비밀번호 변경(AuthService.changePassword) 통합 테스트 (로컬 PostgreSQL 필요).
 * 접속 정보는 환경변수로 오버라이드 가능: TEST_JDBC_URL / TEST_DB_USER / TEST_DB_PASSWORD
 */
class AuthServicePasswordTest : StringSpec({

    val testDbUrl = System.getenv("TEST_JDBC_URL") ?: "jdbc:postgresql://localhost:5432/planboard_test"
    val testDbUser = System.getenv("TEST_DB_USER") ?: "rooter_dev"
    val testDbPassword = System.getenv("TEST_DB_PASSWORD") ?: "wapapyrus"

    ensureTestDatabase(testDbUrl, testDbUser, testDbPassword)

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

    // FileStorage 는 이 테스트 범위 밖 — 호출되지 않으므로 아무 동작도 안 하는 더미면 충분
    val noopFileStorage = object : FileStorage {
        override suspend fun upload(file: UploadableFile, directory: String) = error("사용되지 않아야 함")
        override suspend fun <T> readFile(
            fileKey: String,
            block: suspend (java.io.InputStream, String?, Long?) -> T
        ): T? = error("사용되지 않아야 함")
        override suspend fun getUrl(fileKey: String): String? = error("사용되지 않아야 함")
        override suspend fun delete(fileKey: String) = error("사용되지 않아야 함")
    }
    val userRepo = ExposedUserRepo()
    val deviceTokenService = DeviceTokenService(noPushSender)
    val authService = AuthService(
        userRepo,
        UserService(userRepo, noopFileStorage, testAgreementCatalog),
        deviceTokenService,
        jwtSecret = "test-secret",
        jwtIssuer = "rooter",
        googleClientId = "",
        appleClientId = ""
    )
    val validator = UserJwtValidator(userRepo)

    fun isValid(token: String) = validator.validate(JWTCredential(JWT.decode(token))) != null

    fun seedUser(email: String, rawPassword: String): Int = transaction(db) {
        UserRow.new {
            this.email = email
            username = "tester"
            password = BCrypt.hashpw(rawPassword, BCrypt.gensalt())
            createdAt = OffsetDateTime.now()
        }.id.value
    }

    fun pushTokensOf(userId: Int): List<String> = transaction(db) {
        UserDeviceTokenTable.selectAll()
            .where { UserDeviceTokenTable.userId eq userId }
            .map { it[UserDeviceTokenTable.token] }
    }

    fun registerPushToken(userId: Int, token: String) =
        deviceTokenService.register(userId, DeviceTokenRegisterRequest(token, DevicePlatform.IOS))

    fun tokenVersionOf(userId: Int) = transaction(db) { UserRow.findById(userId)!!.tokenVersion }

    // 운영 DB 에 user_device_tokens 가 없거나 삭제가 실패하는 상황을 테이블 이름을 잠시 바꿔 재현한다
    fun <T> withoutDeviceTokenTable(block: () -> T): T {
        transaction(db) { exec("ALTER TABLE user_device_tokens RENAME TO user_device_tokens_off") }
        try {
            return block()
        } finally {
            transaction(db) { exec("ALTER TABLE user_device_tokens_off RENAME TO user_device_tokens") }
        }
    }

    "changePassword: 비밀번호를 바꾸면 기존 토큰은 무효화되고, 응답의 새 토큰은 통과한다" {
        val userId = seedUser("pw@test.com", "Passw0rd!")
        val oldToken = authService.login(UserLoginRequest("pw@test.com", "Passw0rd!")).token
        isValid(oldToken) shouldBe true

        val response = authService.changePassword(userId, ChangePasswordRequest("Passw0rd!", "NewPassw0rd!"))

        isValid(oldToken) shouldBe false
        isValid(response.token) shouldBe true
        JWT.decode(response.token).getClaim("userId").asInt() shouldBe userId
    }

    "changePassword: 바뀐 비밀번호로 로그인되고, 이전 비밀번호로는 안 된다" {
        val userId = seedUser("pw2@test.com", "Passw0rd!")

        authService.changePassword(userId, ChangePasswordRequest("Passw0rd!", "NewPassw0rd!"))

        authService.login(UserLoginRequest("pw2@test.com", "NewPassw0rd!")).token.shouldNotBeNull()
        shouldThrow<UserValidationException.BadCredentialsException> {
            authService.login(UserLoginRequest("pw2@test.com", "Passw0rd!"))
        }
    }

    "changePassword: 현재 비밀번호가 틀리면 WrongCurrentPasswordException 이고 기존 토큰은 그대로 유효하다" {
        val userId = seedUser("pw3@test.com", "Passw0rd!")
        val token = authService.login(UserLoginRequest("pw3@test.com", "Passw0rd!")).token

        shouldThrow<UserValidationException.WrongCurrentPasswordException> {
            authService.changePassword(userId, ChangePasswordRequest("wrong-pass1", "NewPassw0rd!"))
        }
        isValid(token) shouldBe true
    }

    "changePassword: 새 비밀번호 형식이 틀리면 WrongPasswordFormatException 이고 tokenVersion 은 그대로다" {
        val userId = seedUser("pw4@test.com", "Passw0rd!")

        shouldThrow<UserValidationException.WrongPasswordFormatException> {
            authService.changePassword(userId, ChangePasswordRequest("Passw0rd!", "short"))
        }
        transaction(db) { UserRow.findById(userId)!!.tokenVersion } shouldBe 0
    }

    "changePassword: 그 사용자의 푸시 토큰은 전부 지우고, 다른 사용자 토큰은 남긴다" {
        val userId = seedUser("pw5@test.com", "Passw0rd!")
        val other = seedUser("other@test.com", "Passw0rd!")
        registerPushToken(userId, "ExponentPushToken[lost-phone]")
        registerPushToken(userId, "ExponentPushToken[my-tablet]")
        registerPushToken(other, "ExponentPushToken[other]")

        authService.changePassword(userId, ChangePasswordRequest("Passw0rd!", "NewPassw0rd!"))

        pushTokensOf(userId).shouldBeEmpty()
        pushTokensOf(other) shouldContainExactly listOf("ExponentPushToken[other]")
    }

    "changePassword: 현재 비밀번호가 틀려 실패하면 푸시 토큰은 그대로다" {
        val userId = seedUser("pw6@test.com", "Passw0rd!")
        registerPushToken(userId, "ExponentPushToken[phone]")

        shouldThrow<UserValidationException.WrongCurrentPasswordException> {
            authService.changePassword(userId, ChangePasswordRequest("wrong-pass1", "NewPassw0rd!"))
        }
        pushTokensOf(userId) shouldContainExactly listOf("ExponentPushToken[phone]")
    }

    "changePassword: 푸시 토큰 삭제가 실패하면 비밀번호·tokenVersion 변경도 되돌린다" {
        val userId = seedUser("pw7@test.com", "Passw0rd!")
        val oldToken = authService.login(UserLoginRequest("pw7@test.com", "Passw0rd!")).token

        withoutDeviceTokenTable {
            shouldThrow<Exception> {
                authService.changePassword(userId, ChangePasswordRequest("Passw0rd!", "NewPassw0rd!"))
            }
        }

        tokenVersionOf(userId) shouldBe 0
        isValid(oldToken) shouldBe true
        authService.login(UserLoginRequest("pw7@test.com", "Passw0rd!")).token.shouldNotBeNull()
    }

    "logout: 푸시 토큰 삭제가 실패하면 tokenVersion 도 그대로다 (JWT 만 풀리는 상태 방지)" {
        val userId = seedUser("pw8@test.com", "Passw0rd!")
        val token = authService.login(UserLoginRequest("pw8@test.com", "Passw0rd!")).token

        withoutDeviceTokenTable {
            shouldThrow<Exception> { authService.logout(userId) }
        }

        tokenVersionOf(userId) shouldBe 0
        isValid(token) shouldBe true
    }
})

private fun ensureTestDatabase(url: String, user: String, password: String) {
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
