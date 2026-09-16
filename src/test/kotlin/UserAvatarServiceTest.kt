import com.github.nepyh.rooter.module.storage.FileStorage
import com.github.nepyh.rooter.module.user.UserRepo
import com.github.nepyh.rooter.module.user.UserService
import com.github.nepyh.rooter.module.user.exception.UserValidationException
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.PartData
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import java.sql.SQLException
import java.time.OffsetDateTime

/**
 * 아바타 업로드 용량/포맷 제한 통합 테스트 (로컬 PostgreSQL 필요).
 * 접속 정보는 환경변수로 오버라이드 가능: TEST_JDBC_URL / TEST_DB_USER / TEST_DB_PASSWORD
 */
class UserAvatarServiceTest : StringSpec({

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
        transaction(db) { UserTable.deleteAll() }
    }

    fun seedUser(email: String): Int = transaction(db) {
        UserRow.new {
            this.email = email
            username = "tester"
            password = "x"
            createdAt = OffsetDateTime.now()
        }.id.value
    }

    fun fileItem(fileName: String, bytes: ByteArray): PartData.FileItem = PartData.FileItem(
        provider = { ByteReadChannel(bytes) },
        dispose = {},
        partHeaders = Headers.build {
            append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"$fileName\"")
        }
    )

    // 실제로 업로드된 바이트를 그대로 기록해두는 테스트용 스토리지 — 디스크/네트워크 없이 검증
    class RecordingFileStorage : FileStorage {
        var uploadedBytes: ByteArray? = null
        override suspend fun upload(file: PartData.FileItem, directory: String): String {
            val channel = file.provider()
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val read = channel.readAvailable(chunk)
                if (read == -1) break
                buffer.write(chunk, 0, read)
            }
            uploadedBytes = buffer.toByteArray()
            return "avatars/fake-key.png"
        }
        override suspend fun getFile(fileKey: String): PartData.FileItem? = error("사용되지 않아야 함")
        override suspend fun getUrl(fileKey: String): String? = error("사용되지 않아야 함")
        override suspend fun delete(fileKey: String): Boolean = error("사용되지 않아야 함")
    }

    "updateAvatar: 허용된 확장자(png)면 정상 업로드되고 바이트가 그대로 전달된다" {
        val userId = seedUser("avatar-ok@test.com")
        val storage = RecordingFileStorage()
        val userService = UserService(UserRepo(), storage)
        val bytes = "fake-png-bytes".toByteArray()

        val response = userService.updateAvatar(userId, fileItem("photo.png", bytes))

        response.userId shouldBe userId
        storage.uploadedBytes shouldBe bytes
    }

    "updateAvatar: 허용되지 않는 확장자(gif)면 UnsupportedAvatarFileTypeException" {
        val userId = seedUser("avatar-badtype@test.com")
        val userService = UserService(UserRepo(), RecordingFileStorage())

        shouldThrow<UserValidationException.UnsupportedAvatarFileTypeException> {
            userService.updateAvatar(userId, fileItem("photo.gif", "aaa".toByteArray()))
        }
    }

    "updateAvatar: 확장자가 없으면 UnsupportedAvatarFileTypeException" {
        val userId = seedUser("avatar-noext@test.com")
        val userService = UserService(UserRepo(), RecordingFileStorage())

        shouldThrow<UserValidationException.UnsupportedAvatarFileTypeException> {
            userService.updateAvatar(userId, fileItem("photo", "aaa".toByteArray()))
        }
    }

    "updateAvatar: 5MB 를 초과하면 AvatarFileTooLargeException" {
        val userId = seedUser("avatar-toolarge@test.com")
        val storage = RecordingFileStorage()
        val userService = UserService(UserRepo(), storage)
        val oversized = ByteArray(5 * 1024 * 1024 + 1)

        shouldThrow<UserValidationException.AvatarFileTooLargeException> {
            userService.updateAvatar(userId, fileItem("photo.png", oversized))
        }
        storage.uploadedBytes shouldBe null // 상한을 넘으면 fileStorage.upload 자체가 호출되면 안 됨
    }

    "updateAvatar: 정확히 5MB 면 통과한다 (경계값)" {
        val userId = seedUser("avatar-exactsize@test.com")
        val storage = RecordingFileStorage()
        val userService = UserService(UserRepo(), storage)
        val exactSize = ByteArray(5 * 1024 * 1024)

        userService.updateAvatar(userId, fileItem("photo.png", exactSize))

        storage.uploadedBytes?.size shouldBe exactSize.size
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
