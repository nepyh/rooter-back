import com.github.nepyh.rooter.module.storage.FileStorage
import com.github.nepyh.rooter.module.storage.UploadableFile
import com.github.nepyh.rooter.module.user.ExposedUserRepo
import com.github.nepyh.rooter.module.user.UserService
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
    val userService = UserService(ExposedUserRepo(), noopFileStorage)

    fun seedUser(email: String): Int = transaction(db) {
        UserRow.new {
            this.email = email
            username = "tester"
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

    "getUserInfo: 존재하지 않는 유저면 UserNotFoundException" {
        shouldThrow<UserNotFoundException> {
            userService.getUserInfo(999999)
        }
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
