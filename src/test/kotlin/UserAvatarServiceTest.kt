import com.github.nepyh.rooter.module.storage.FileStorage
import com.github.nepyh.rooter.module.storage.UploadableFile
import com.github.nepyh.rooter.module.user.UserService
import com.github.nepyh.rooter.module.user.exception.UserValidationException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.PartData
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

/**
 * 아바타 업로드 용량/포맷 제한 테스트.
 * UserRepo 는 인메모리 구현체를 쓰므로 DB 접속이 필요 없다.
 */
class UserAvatarServiceTest : StringSpec({

    fun fileItem(fileName: String, bytes: ByteArray): PartData.FileItem = PartData.FileItem(
        provider = { ByteReadChannel(bytes) },
        dispose = {},
        partHeaders = Headers.build {
            append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"$fileName\"")
        }
    )

    // 실제로 업로드된 바이트/키를 그대로 기록해두는 테스트용 스토리지 — 디스크/네트워크 없이 검증
    class RecordingFileStorage : FileStorage {
        val uploadedKeys = mutableListOf<String>()
        val deletedKeys = mutableListOf<String>()
        var uploadedBytes: ByteArray? = null
        var uploadedContentType: String? = null

        override suspend fun upload(file: UploadableFile, directory: String): String {
            val channel = file.content
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val read = channel.readAvailable(chunk)
                if (read == -1) break
                buffer.write(chunk, 0, read)
            }
            uploadedBytes = buffer.toByteArray()
            uploadedContentType = file.contentType

            val key = "$directory/key-${uploadedKeys.size + 1}.png"
            uploadedKeys += key
            return key
        }

        override suspend fun <T> readFile(
            fileKey: String,
            block: suspend (java.io.InputStream, String?, Long?) -> T
        ): T? = error("사용되지 않아야 함")

        override suspend fun getUrl(fileKey: String): String? = "https://fake-storage.test/$fileKey"

        override suspend fun delete(fileKey: String): Boolean {
            deletedKeys += fileKey
            return true
        }
    }

    "updateAvatar: 허용된 확장자(png)면 정상 업로드되고 바이트/Content-Type 이 그대로 전달된다" {
        val repo = InMemoryUserRepo()
        val userId = repo.insertUser("avatar-ok@test.com", "tester", "x").id.value
        val storage = RecordingFileStorage()
        val userService = UserService(repo, storage)
        val bytes = "fake-png-bytes".toByteArray()

        val response = userService.updateAvatar(userId, fileItem("photo.png", bytes))

        response.userId shouldBe userId
        response.avatarUrl shouldBe "https://fake-storage.test/${storage.uploadedKeys.single()}"
        storage.uploadedBytes shouldBe bytes
        // 파트 헤더에 Content-Type 이 없어도 확장자에서 유도한다
        storage.uploadedContentType shouldBe "image/png"
    }

    "updateAvatar: 기존 아바타가 있으면 교체 후 이전 오브젝트를 삭제한다" {
        val repo = InMemoryUserRepo()
        val userId = repo.insertUser("avatar-replace@test.com", "tester", "x", avatarImageKey = "avatars/old.png").id.value
        val storage = RecordingFileStorage()
        val userService = UserService(repo, storage)

        userService.updateAvatar(userId, fileItem("photo.png", "fake-png-bytes".toByteArray()))

        storage.deletedKeys shouldBe listOf("avatars/old.png")
        repo.findUserById(userId)?.avatarImageKey shouldBe storage.uploadedKeys.single()
    }

    "updateAvatar: 첫 업로드(기존 아바타 없음)면 삭제를 호출하지 않는다" {
        val repo = InMemoryUserRepo()
        val userId = repo.insertUser("avatar-first@test.com", "tester", "x").id.value
        val storage = RecordingFileStorage()
        val userService = UserService(repo, storage)

        userService.updateAvatar(userId, fileItem("photo.png", "fake-png-bytes".toByteArray()))

        storage.deletedKeys.shouldBeEmpty()
    }

    "updateAvatar: 허용되지 않는 확장자(gif)면 UnsupportedAvatarFileTypeException" {
        val repo = InMemoryUserRepo()
        val userId = repo.insertUser("avatar-badtype@test.com", "tester", "x").id.value
        val userService = UserService(repo, RecordingFileStorage())

        shouldThrow<UserValidationException.UnsupportedAvatarFileTypeException> {
            userService.updateAvatar(userId, fileItem("photo.gif", "aaa".toByteArray()))
        }
    }

    "updateAvatar: 확장자가 없으면 UnsupportedAvatarFileTypeException" {
        val repo = InMemoryUserRepo()
        val userId = repo.insertUser("avatar-noext@test.com", "tester", "x").id.value
        val userService = UserService(repo, RecordingFileStorage())

        shouldThrow<UserValidationException.UnsupportedAvatarFileTypeException> {
            userService.updateAvatar(userId, fileItem("photo", "aaa".toByteArray()))
        }
    }

    "updateAvatar: 5MB 를 초과하면 AvatarFileTooLargeException" {
        val repo = InMemoryUserRepo()
        val userId = repo.insertUser("avatar-toolarge@test.com", "tester", "x").id.value
        val storage = RecordingFileStorage()
        val userService = UserService(repo, storage)
        val oversized = ByteArray(5 * 1024 * 1024 + 1)

        shouldThrow<UserValidationException.AvatarFileTooLargeException> {
            userService.updateAvatar(userId, fileItem("photo.png", oversized))
        }
        storage.uploadedBytes shouldBe null // 상한을 넘으면 fileStorage.upload 자체가 호출되면 안 됨
    }

    "updateAvatar: 정확히 5MB 면 통과한다 (경계값)" {
        val repo = InMemoryUserRepo()
        val userId = repo.insertUser("avatar-exactsize@test.com", "tester", "x").id.value
        val storage = RecordingFileStorage()
        val userService = UserService(repo, storage)
        val exactSize = ByteArray(5 * 1024 * 1024)

        userService.updateAvatar(userId, fileItem("photo.png", exactSize))

        storage.uploadedBytes?.size shouldBe exactSize.size
    }

    "getUserInfo: 저장된 아바타 키로 avatarUrl 을 채운다" {
        val repo = InMemoryUserRepo()
        val userId = repo.insertUser("info@test.com", "tester", "x", avatarImageKey = "avatars/old.png").id.value
        repo.insertStudentProfile(userId, schoolId = "C107181084", grade = 2, classNumber = 3)
        val userService = UserService(repo, RecordingFileStorage())

        val response = userService.getUserInfo(userId)

        response.avatarUrl shouldBe "https://fake-storage.test/avatars/old.png"
    }

    "getUserInfo: 아바타를 등록한 적이 없으면 avatarUrl 은 null" {
        val repo = InMemoryUserRepo()
        val userId = repo.insertUser("info-noavatar@test.com", "tester", "x").id.value
        repo.insertStudentProfile(userId, schoolId = "C107181084", grade = 2, classNumber = 3)
        val userService = UserService(repo, RecordingFileStorage())

        userService.getUserInfo(userId).avatarUrl shouldBe null
    }
})
