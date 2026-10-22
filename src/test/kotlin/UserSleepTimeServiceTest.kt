import com.github.nepyh.rooter.module.storage.FileStorage
import com.github.nepyh.rooter.module.storage.UploadableFile
import com.github.nepyh.rooter.module.user.UserService
import com.github.nepyh.rooter.module.user.dto.SleepTimeRequest
import com.github.nepyh.rooter.module.user.dto.SleepTimeResponse
import com.github.nepyh.rooter.module.user.exception.UserNotFoundException
import com.github.nepyh.rooter.module.user.exception.UserValidationException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * 수면시간(sleep-time) 조회·저장 테스트.
 * UserRepo 는 인메모리 구현체를 쓰므로 DB 접속이 필요 없다.
 */
class UserSleepTimeServiceTest : StringSpec({

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

    "getSleepTime: 저장한 적 없으면 기본값 23:00 / 06:30 과 isDefault=true" {
        val repo = InMemoryUserRepo()
        val userId = seedUser(repo, "sleep-default@test.com")

        UserService(repo, noopFileStorage).getSleepTime(userId) shouldBe SleepTimeResponse("23:00", "06:30", isDefault = true)
    }

    "updateSleepTime: 저장하면 같은 값을 돌려주고, 조회해도 저장한 값이 나온다 (자정 넘어 자는 경우 포함)" {
        val repo = InMemoryUserRepo()
        val userId = seedUser(repo, "sleep-save@test.com")
        val service = UserService(repo, noopFileStorage)

        service.updateSleepTime(userId, SleepTimeRequest("23:30", "07:00")) shouldBe SleepTimeResponse("23:30", "07:00", isDefault = false)
        service.updateSleepTime(userId, SleepTimeRequest("01:00", "07:30")) shouldBe SleepTimeResponse("01:00", "07:30", isDefault = false)
        service.getSleepTime(userId) shouldBe SleepTimeResponse("01:00", "07:30", isDefault = false)
    }

    "updateSleepTime: HH:mm 이 아니면 INVALID_TIME_FORMAT 이고 저장하지 않는다" {
        val repo = InMemoryUserRepo()
        val userId = seedUser(repo, "sleep-format@test.com")
        val service = UserService(repo, noopFileStorage)

        listOf("7:00", "24:00", "23:00:00", "abc", "").forEach { bad ->
            shouldThrow<UserValidationException.WrongTimeFormatException> {
                service.updateSleepTime(userId, SleepTimeRequest(bad, "07:00"))
            }.code shouldBe "INVALID_TIME_FORMAT"
        }
        service.getSleepTime(userId).isDefault shouldBe true
    }

    "updateSleepTime: 취침과 기상이 같은 시각이면 INVALID_SLEEP_TIME" {
        val repo = InMemoryUserRepo()
        val userId = seedUser(repo, "sleep-same@test.com")

        shouldThrow<UserValidationException.WrongSleepTimeException> {
            UserService(repo, noopFileStorage).updateSleepTime(userId, SleepTimeRequest("07:00", "07:00"))
        }.code shouldBe "INVALID_SLEEP_TIME"
    }

    "getSleepTime·updateSleepTime: 존재하지 않는 유저면 UserNotFoundException" {
        val service = UserService(InMemoryUserRepo(), noopFileStorage)

        shouldThrow<UserNotFoundException> { service.getSleepTime(999) }
        shouldThrow<UserNotFoundException> { service.updateSleepTime(999, SleepTimeRequest("23:00", "07:00")) }
    }
})
