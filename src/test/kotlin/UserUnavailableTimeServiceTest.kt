import com.github.nepyh.rooter.module.storage.FileStorage
import com.github.nepyh.rooter.module.storage.UploadableFile
import com.github.nepyh.rooter.module.user.UserRepo
import com.github.nepyh.rooter.module.user.UserService
import com.github.nepyh.rooter.module.user.dto.UnavailableTimeRequest
import com.github.nepyh.rooter.module.user.exception.UnavailableTimeNotFoundException
import com.github.nepyh.rooter.module.user.exception.UserNotFoundException
import com.github.nepyh.rooter.module.user.model.UnavailableTimeTable
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.http.content.PartData
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import java.sql.SQLException
import java.time.OffsetDateTime

/**
 * 유저 불가능 시간(unavailable-times) 삭제 통합 테스트 (로컬 PostgreSQL 필요).
 * 접속 정보는 환경변수로 오버라이드 가능: TEST_JDBC_URL / TEST_DB_USER / TEST_DB_PASSWORD
 */
class UserUnavailableTimeServiceTest : StringSpec({

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
            exec("DROP TABLE IF EXISTS user_unavailable_times CASCADE")
            exec("DROP TABLE IF EXISTS users CASCADE")
            SchemaUtils.create(UserTable, UnavailableTimeTable)
        }
    }

    beforeEach {
        transaction(db) {
            UnavailableTimeTable.deleteAll()
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
    val userService = UserService(UserRepo(), noopFileStorage)

    fun seedUser(email: String): Int = transaction(db) {
        UserRow.new {
            this.email = email
            username = "tester"
            password = "x"
            createdAt = OffsetDateTime.now()
        }.id.value
    }

    "deleteUnavailableTime: 존재하지 않는 유저면 UserNotFoundException" {
        shouldThrow<UserNotFoundException> {
            userService.deleteUnavailableTime(999, 1)
        }
    }

    "deleteUnavailableTime: 존재하지 않는 timeId 면 UnavailableTimeNotFoundException" {
        val userId = seedUser("delete-notfound@test.com")
        shouldThrow<UnavailableTimeNotFoundException> {
            userService.deleteUnavailableTime(userId, 999)
        }
    }

    "deleteUnavailableTime: 본인 소유가 아니면 UnavailableTimeNotFoundException (존재 여부를 노출하지 않음)" {
        val ownerId = seedUser("delete-owner@test.com")
        val otherId = seedUser("delete-other@test.com")
        val time = userService.addUnavailableTime(ownerId, UnavailableTimeRequest(dayOfWeek = 1, startTime = "22:00", endTime = "23:00"))

        shouldThrow<UnavailableTimeNotFoundException> {
            userService.deleteUnavailableTime(otherId, time.id)
        }
        // 본인 소유 목록엔 여전히 남아있어야 함 (삭제 안 됐는지 확인)
        userService.getUnavailableTimes(ownerId).map { it.id } shouldBe listOf(time.id)
    }

    "deleteUnavailableTime: 본인 소유면 정상 삭제되고, 목록 조회에서 사라진다" {
        val userId = seedUser("delete-ok@test.com")
        val time = userService.addUnavailableTime(userId, UnavailableTimeRequest(dayOfWeek = 1, startTime = "22:00", endTime = "23:00"))

        userService.deleteUnavailableTime(userId, time.id)

        userService.getUnavailableTimes(userId).shouldBeEmpty()
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
