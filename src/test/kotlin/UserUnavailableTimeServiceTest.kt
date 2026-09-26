import com.github.nepyh.rooter.module.storage.FileStorage
import com.github.nepyh.rooter.module.storage.UploadableFile
import com.github.nepyh.rooter.module.user.UserService
import com.github.nepyh.rooter.module.user.dto.UnavailableTimeRequest
import com.github.nepyh.rooter.module.user.exception.UnavailableTimeNotFoundException
import com.github.nepyh.rooter.module.user.exception.UserNotFoundException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * 유저 불가능 시간(unavailable-times) 삭제 테스트.
 * UserRepo 는 인메모리 구현체를 쓰므로 DB 접속이 필요 없다.
 */
class UserUnavailableTimeServiceTest : StringSpec({

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

    fun seedUser(repo: InMemoryUserRepo, email: String): Int = repo.insertUser(email, "tester", "x").id.value

    fun userServiceOf(repo: InMemoryUserRepo) = UserService(repo, noopFileStorage)

    "deleteUnavailableTime: 존재하지 않는 유저면 UserNotFoundException" {
        val userService = userServiceOf(InMemoryUserRepo())

        shouldThrow<UserNotFoundException> {
            userService.deleteUnavailableTime(999, 1)
        }
    }

    "deleteUnavailableTime: 존재하지 않는 timeId 면 UnavailableTimeNotFoundException" {
        val repo = InMemoryUserRepo()
        val userId = seedUser(repo, "delete-notfound@test.com")
        val userService = userServiceOf(repo)

        shouldThrow<UnavailableTimeNotFoundException> {
            userService.deleteUnavailableTime(userId, 999)
        }
    }

    "deleteUnavailableTime: 본인 소유가 아니면 UnavailableTimeNotFoundException (존재 여부를 노출하지 않음)" {
        val repo = InMemoryUserRepo()
        val ownerId = seedUser(repo, "delete-owner@test.com")
        val otherId = seedUser(repo, "delete-other@test.com")
        val userService = userServiceOf(repo)
        val time = userService.addUnavailableTime(ownerId, UnavailableTimeRequest(dayOfWeek = 1, startTime = "22:00", endTime = "23:00"))

        shouldThrow<UnavailableTimeNotFoundException> {
            userService.deleteUnavailableTime(otherId, time.id)
        }
        // 본인 소유 목록엔 여전히 남아있어야 함 (삭제 안 됐는지 확인)
        userService.getUnavailableTimes(ownerId).map { it.id } shouldBe listOf(time.id)
    }

    "deleteUnavailableTime: 본인 소유면 정상 삭제되고, 목록 조회에서 사라진다" {
        val repo = InMemoryUserRepo()
        val userId = seedUser(repo, "delete-ok@test.com")
        val userService = userServiceOf(repo)
        val time = userService.addUnavailableTime(userId, UnavailableTimeRequest(dayOfWeek = 1, startTime = "22:00", endTime = "23:00"))

        userService.deleteUnavailableTime(userId, time.id)

        userService.getUnavailableTimes(userId).shouldBeEmpty()
    }
})
