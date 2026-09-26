import com.auth0.jwt.JWT
import com.github.nepyh.rooter.module.storage.FileStorage
import com.github.nepyh.rooter.module.storage.UploadableFile
import com.github.nepyh.rooter.module.user.AuthService
import com.github.nepyh.rooter.module.user.UserJwtValidator
import com.github.nepyh.rooter.module.user.UserRepo
import com.github.nepyh.rooter.module.user.UserService
import com.github.nepyh.rooter.module.user.dto.ChangePasswordRequest
import com.github.nepyh.rooter.module.user.dto.UserLoginRequest
import com.github.nepyh.rooter.module.user.exception.UserValidationException
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.server.auth.jwt.JWTCredential
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
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
            exec("DROP TABLE IF EXISTS users CASCADE")
            SchemaUtils.create(UserTable)
        }
    }

    beforeEach {
        transaction(db) {
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
    val userRepo = UserRepo()
    val authService = AuthService(
        userRepo,
        UserService(userRepo, noopFileStorage),
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
