import com.github.nepyh.rooter.module.storage.FileStorage
import com.github.nepyh.rooter.module.storage.UploadableFile
import com.github.nepyh.rooter.module.user.UserService
import com.github.nepyh.rooter.module.user.dto.CommuteTimeRequest
import com.github.nepyh.rooter.module.user.dto.CommuteTimeResponse
import com.github.nepyh.rooter.module.user.exception.UserNotFoundException
import com.github.nepyh.rooter.module.user.exception.UserValidationException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * 하교 후 귀가 시간(commute-time) 조회·저장 테스트.
 * UserRepo 는 인메모리 구현체를 쓰므로 DB 접속이 필요 없다.
 */
class UserCommuteTimeServiceTest : StringSpec({

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

    "getCommuteTime: 저장한 적 없으면 기본값 40분과 isDefault=true" {
        val repo = InMemoryUserRepo()
        val userId = seedUser(repo, "commute-default@test.com")

        UserService(repo, noopFileStorage, testAgreementCatalog).getCommuteTime(userId) shouldBe CommuteTimeResponse(40, isDefault = true)
    }

    "updateCommuteTime: 0~180분을 저장하고, 조회해도 저장한 값이 나온다" {
        val repo = InMemoryUserRepo()
        val userId = seedUser(repo, "commute-save@test.com")
        val service = UserService(repo, noopFileStorage, testAgreementCatalog)

        service.updateCommuteTime(userId, CommuteTimeRequest(25)) shouldBe CommuteTimeResponse(25, isDefault = false)
        service.updateCommuteTime(userId, CommuteTimeRequest(0)) shouldBe CommuteTimeResponse(0, isDefault = false)
        service.getCommuteTime(userId) shouldBe CommuteTimeResponse(0, isDefault = false)
    }

    "updateCommuteTime: 0~180분 밖이면 INVALID_COMMUTE_MINUTES 이고 저장하지 않는다" {
        val repo = InMemoryUserRepo()
        val userId = seedUser(repo, "commute-range@test.com")
        val service = UserService(repo, noopFileStorage, testAgreementCatalog)

        listOf(-1, 181).forEach { bad ->
            shouldThrow<UserValidationException.WrongCommuteMinutesException> {
                service.updateCommuteTime(userId, CommuteTimeRequest(bad))
            }.code shouldBe "INVALID_COMMUTE_MINUTES"
        }
        service.getCommuteTime(userId).isDefault shouldBe true
    }

    "getCommuteTime·updateCommuteTime: 존재하지 않는 유저면 UserNotFoundException" {
        val service = UserService(InMemoryUserRepo(), noopFileStorage, testAgreementCatalog)

        shouldThrow<UserNotFoundException> { service.getCommuteTime(999) }
        shouldThrow<UserNotFoundException> { service.updateCommuteTime(999, CommuteTimeRequest(30)) }
    }
})
