import com.github.nepyh.rooter.module.storage.FileStorage
import com.github.nepyh.rooter.module.storage.UploadableFile
import com.github.nepyh.rooter.module.user.UserService
import com.github.nepyh.rooter.module.user.dto.UnavailableTimeRequest
import com.github.nepyh.rooter.module.user.exception.UserNotFoundException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * 회원 탈퇴 테스트. UserRepo 는 인메모리 구현체(DB cascade 를 흉내 냄)라 DB 접속이 필요 없다.
 */
class UserDeleteServiceTest : StringSpec({

    // 지운 키만 기록하는 테스트용 스토리지. failDelete 면 삭제가 예외를 던진다
    class DeleteRecordingFileStorage(private val failDelete: Boolean = false) : FileStorage {
        val deletedKeys = mutableListOf<String>()

        override suspend fun upload(file: UploadableFile, directory: String): String = error("사용되지 않아야 함")

        override suspend fun <T> readFile(
            fileKey: String,
            block: suspend (java.io.InputStream, String?, Long?) -> T
        ): T? = error("사용되지 않아야 함")

        override suspend fun getUrl(fileKey: String): String? = "https://fake-storage.test/$fileKey"

        override suspend fun delete(fileKey: String): Boolean {
            if (failDelete) error("스토리지 장애")
            deletedKeys += fileKey
            return true
        }
    }

    "deleteUser: 유저와 그 유저의 기록이 지워지고 아바타 오브젝트도 삭제된다" {
        val repo = InMemoryUserRepo()
        val userId = repo.insertUser("bye@test.com", "tester", "x").id.value
        repo.updateAvatarImageKey(userId, "avatars/me.png")
        val storage = DeleteRecordingFileStorage()
        val userService = UserService(repo, storage)
        userService.addUnavailableTime(userId, UnavailableTimeRequest(dayOfWeek = 1.toShort(), startTime = "18:00", endTime = "20:00"))

        userService.deleteUser(userId)

        repo.findUserById(userId) shouldBe null
        repo.findUnavailableTimesByUserId(userId).shouldBeEmpty()
        storage.deletedKeys shouldBe listOf("avatars/me.png")
    }

    "deleteUser: 다른 유저는 그대로 남는다" {
        val repo = InMemoryUserRepo()
        val me = repo.insertUser("me@test.com", "me", "x").id.value
        val other = repo.insertUser("other@test.com", "other", "x").id.value
        val userService = UserService(repo, DeleteRecordingFileStorage())

        userService.deleteUser(me)

        repo.findUserById(me) shouldBe null
        repo.findUserById(other)?.email shouldBe "other@test.com"
    }

    "deleteUser: 아바타가 없으면 스토리지를 건드리지 않는다" {
        val repo = InMemoryUserRepo()
        val userId = repo.insertUser("plain@test.com", "tester", "x").id.value
        val storage = DeleteRecordingFileStorage()

        UserService(repo, storage).deleteUser(userId)

        repo.findUserById(userId) shouldBe null
        storage.deletedKeys.shouldBeEmpty()
    }

    "deleteUser: 아바타 삭제가 실패해도 탈퇴는 성공한다" {
        val repo = InMemoryUserRepo()
        val userId = repo.insertUser("flaky@test.com", "tester", "x").id.value
        repo.updateAvatarImageKey(userId, "avatars/flaky.png")

        UserService(repo, DeleteRecordingFileStorage(failDelete = true)).deleteUser(userId)

        repo.findUserById(userId) shouldBe null
    }

    "deleteUser: 없는 유저면 UserNotFoundException" {
        val userService = UserService(InMemoryUserRepo(), DeleteRecordingFileStorage())

        shouldThrow<UserNotFoundException> { userService.deleteUser(999) }
    }
})
