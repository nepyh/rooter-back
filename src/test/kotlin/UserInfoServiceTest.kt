import com.github.nepyh.rooter.module.storage.FileStorage
import com.github.nepyh.rooter.module.storage.UploadableFile
import com.github.nepyh.rooter.module.user.ExposedUserRepo
import com.github.nepyh.rooter.module.user.UserService
import com.github.nepyh.rooter.module.user.dto.StudentProfileRequest
import com.github.nepyh.rooter.module.user.dto.UpdateProfileRequest
import com.github.nepyh.rooter.module.user.dto.UserRegisterRequest
import com.github.nepyh.rooter.module.user.exception.UserValidationException
import com.github.nepyh.rooter.module.user.exception.UserNotFoundException
import com.github.nepyh.rooter.module.user.model.StudentProfileRow
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import java.sql.SQLException
import java.time.OffsetDateTime

/**
 * 유저 정보 조회(getUserInfo) 통합 테스트 (로컬 PostgreSQL 필요).
 * 접속 정보는 환경변수로 오버라이드 가능: TEST_JDBC_URL / TEST_DB_USER / TEST_DB_PASSWORD
 */
class UserInfoServiceTest : StringSpec({

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
            exec("DROP TABLE IF EXISTS student_profiles CASCADE")
            exec("DROP TABLE IF EXISTS users CASCADE")
            SchemaUtils.create(UserTable, StudentProfileTable)
        }
    }

    beforeEach {
        transaction(db) {
            StudentProfileTable.deleteAll()
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
    val userService = UserService(ExposedUserRepo(), noopFileStorage, testAgreementCatalog)

    fun seedUser(email: String, username: String = "tester"): Int = transaction(db) {
        UserRow.new {
            this.email = email
            this.username = username
            password = "x"
            createdAt = OffsetDateTime.now()
        }.id.value
    }

    "getUserInfo: 학생 프로필 등록 전이면 기본 정보만 주고 프로필 필드는 null" {
        val userId = seedUser("noprofile@test.com")

        val info = userService.getUserInfo(userId)

        info.id shouldBe userId
        info.email shouldBe "noprofile@test.com"
        info.schoolId shouldBe null
        info.grade shouldBe null
        info.classNumber shouldBe null
    }

    "getUserInfo: 학생 프로필이 있으면 함께 준다" {
        val userId = seedUser("profile@test.com")
        transaction(db) {
            StudentProfileRow.new {
                user = UserRow[userId]
                schoolId = "B107132131"
                grade = 1
                classNumber = 3
            }
        }

        val info = userService.getUserInfo(userId)

        info.schoolId shouldBe "B107132131"
        info.grade shouldBe 1
        info.classNumber shouldBe 3
    }

    "createStudentProfile: 두 번 등록하면 새 행 없이 같은 프로필을 덮어쓰고 조회에도 바뀐 값이 나온다" {
        val userId = seedUser("twice@test.com")

        val first = userService.createStudentProfile(userId, StudentProfileRequest("B107132131", 1, 3))
        val second = userService.createStudentProfile(userId, StudentProfileRequest("C107181084", 2, 5))

        second.id shouldBe first.id
        transaction(db) { StudentProfileRow.all().count() } shouldBe 1L
        val info = userService.getUserInfo(userId)
        info.schoolId shouldBe "C107181084"
        info.grade shouldBe 2
        info.classNumber shouldBe 5
    }

    "getUserInfo: 존재하지 않는 유저면 UserNotFoundException" {
        shouldThrow<UserNotFoundException> {
            userService.getUserInfo(999999)
        }
    }

    "updateProfile: 공백이 든 새 이름은 INVALID_USERNAME (#407)" {
        val userId = seedUser("rename@test.com")

        val exception = shouldThrow<UserValidationException.WrongUsernameException> {
            userService.updateProfile(userId, UpdateProfileRequest(username = "김 철수"))
        }

        exception.message shouldBe "이름에 띄어쓰기를 넣을 수 없습니다."
        transaction(db) { UserRow[userId].username } shouldBe "tester"
    }

    "updateProfile: 규칙 전에 공백 이름으로 가입한 사용자도 이름을 그대로 보내며 소개만 고칠 수 있다 (#407)" {
        val userId = seedUser("legacy-name@test.com", username = "김 철수")

        val updated = userService.updateProfile(userId, UpdateProfileRequest(username = "김 철수", bio = "안녕"))

        updated.username shouldBe "김 철수"
        updated.bio shouldBe "안녕"
    }

    "registerUser: 공백이 든 이름은 가입 전에 INVALID_USERNAME (#407)" {
        val exception = shouldThrow<UserValidationException.WrongUsernameException> {
            userService.registerUser(UserRegisterRequest(email = "space@test.com", username = "김 철수", password = "abcd1234"))
        }

        exception.code shouldBe "INVALID_USERNAME"
        transaction(db) { UserRow.count() } shouldBe 0L
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
