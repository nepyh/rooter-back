import com.github.nepyh.rooter.module.storage.FileStorage
import com.github.nepyh.rooter.module.storage.UploadableFile
import com.github.nepyh.rooter.module.notification.DeviceTokenService
import com.github.nepyh.rooter.module.user.AuthService
import com.github.nepyh.rooter.module.user.ExposedUserRepo
import com.github.nepyh.rooter.module.user.UserService
import com.github.nepyh.rooter.module.user.dto.AgreementDecision
import com.github.nepyh.rooter.module.user.dto.AgreementsUpdateRequest
import com.github.nepyh.rooter.module.user.dto.UserLoginRequest
import com.github.nepyh.rooter.module.user.dto.UserRegisterRequest
import com.github.nepyh.rooter.module.user.exception.AgreementValidationException
import com.github.nepyh.rooter.module.user.model.AgreementCatalog
import com.github.nepyh.rooter.module.user.model.AgreementCode
import com.github.nepyh.rooter.module.user.model.AgreementDocument
import com.github.nepyh.rooter.module.user.model.UserAgreementRow
import com.github.nepyh.rooter.module.user.model.UserAgreementTable
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import java.sql.SQLException

/**
 * 약관·개인정보 동의 이력 통합 테스트 (로컬 PostgreSQL 필요).
 * 접속 정보는 환경변수로 오버라이드 가능: TEST_JDBC_URL / TEST_DB_USER / TEST_DB_PASSWORD
 */
class UserAgreementServiceTest : StringSpec({

    val testDbUrl = System.getenv("TEST_JDBC_URL") ?: "jdbc:postgresql://localhost:5432/planboard_test"
    val testDbUser = System.getenv("TEST_DB_USER") ?: "rooter_dev"
    val testDbPassword = System.getenv("TEST_DB_PASSWORD") ?: "wapapyrus"

    ensureAgreementTestDatabase(testDbUrl, testDbUser, testDbPassword)

    val db = Database.connect(
        url = testDbUrl,
        driver = "org.postgresql.Driver",
        user = testDbUser,
        password = testDbPassword
    )

    beforeSpec {
        transaction(db) {
            exec("DROP TABLE IF EXISTS user_agreements CASCADE")
            exec("DROP TABLE IF EXISTS users CASCADE")
            SchemaUtils.create(UserTable, UserAgreementTable)
        }
    }

    beforeEach {
        transaction(db) {
            UserAgreementTable.deleteAll()
            UserTable.deleteAll()
        }
    }

    val noopFileStorage = object : FileStorage {
        override suspend fun upload(file: UploadableFile, directory: String) = error("사용되지 않아야 함")
        override suspend fun <T> readFile(
            fileKey: String,
            block: suspend (java.io.InputStream, String?, Long?) -> T
        ): T? = error("사용되지 않아야 함")
        override suspend fun getUrl(fileKey: String): String? = error("사용되지 않아야 함")
        override suspend fun delete(fileKey: String) = error("사용되지 않아야 함")
    }

    fun catalog(version: String = TEST_AGREEMENT_VERSION) = AgreementCatalog(
        listOf(
            AgreementDocument(AgreementCode.TERMS, "이용약관", version, "https://example.test/terms/"),
            AgreementDocument(AgreementCode.PRIVACY, "개인정보 처리방침", version, "https://example.test/privacy/")
        )
    )

    fun service(catalog: AgreementCatalog = catalog()) = UserService(ExposedUserRepo(), noopFileStorage, catalog)

    fun registerRequest(email: String, agreements: List<AgreementDecision>?) = UserRegisterRequest(
        email = email,
        username = "tester",
        password = "abcd1234",
        agreements = agreements
    )

    fun agreed(code: String, version: String = TEST_AGREEMENT_VERSION) = AgreementDecision(code, version, true)

    fun rowCount(userId: Int): Long = transaction(db) {
        UserAgreementRow.find { UserAgreementTable.userId eq userId }.count()
    }

    fun userIdOf(email: String): Int = ExposedUserRepo().findUserByEmail(email)!!.id.value

    "가입: 필수 2건을 보내면 이력 2행이 남고 pendingAgreements 가 비어 있다" {
        val userService = service()
        userService.registerUser(registerRequest("agree@test.com", listOf(agreed("TERMS"), agreed("PRIVACY"))))

        val userId = userIdOf("agree@test.com")
        rowCount(userId) shouldBe 2L
        userService.pendingAgreements(userId).shouldBeEmpty()
    }

    "가입: agreements 를 안 보내면 가입은 되고 pendingAgreements 에 필수 2건이 남는다" {
        val userService = service()
        userService.registerUser(registerRequest("legacy@test.com", null))

        val userId = userIdOf("legacy@test.com")
        rowCount(userId) shouldBe 0L
        userService.pendingAgreements(userId) shouldContainExactly listOf("TERMS", "PRIVACY")
    }

    "가입: 필수 항목을 빠뜨리면 REQUIRED_AGREEMENT_MISSING 이고 유저도 안 만들어진다" {
        val userService = service()
        val exception = shouldThrow<AgreementValidationException.RequiredMissingException> {
            userService.registerUser(registerRequest("partial@test.com", listOf(agreed("TERMS"))))
        }
        exception.code shouldBe "REQUIRED_AGREEMENT_MISSING"
        transaction(db) { UserTable.selectAll().count() } shouldBe 0L
    }

    "가입: 필수 항목을 agreed=false 로 보내면 거부한다" {
        val userService = service()
        shouldThrow<AgreementValidationException.RequiredMissingException> {
            userService.registerUser(
                registerRequest(
                    "refuse@test.com",
                    listOf(
                        AgreementDecision("TERMS", TEST_AGREEMENT_VERSION, false),
                        agreed("PRIVACY")
                    )
                )
            )
        }
    }

    "가입: 버전이 다르면 AGREEMENT_VERSION_MISMATCH" {
        val userService = service()
        val exception = shouldThrow<AgreementValidationException.VersionMismatchException> {
            userService.registerUser(
                registerRequest("oldver@test.com", listOf(agreed("TERMS", "2025-01-01"), agreed("PRIVACY")))
            )
        }
        exception.code shouldBe "AGREEMENT_VERSION_MISMATCH"
    }

    "가입: 모르는 code 는 UNKNOWN_AGREEMENT_CODE" {
        val userService = service()
        val exception = shouldThrow<AgreementValidationException.UnknownCodeException> {
            userService.registerUser(registerRequest("unknown@test.com", listOf(agreed("MARKETING"))))
        }
        exception.code shouldBe "UNKNOWN_AGREEMENT_CODE"
    }

    "가입: 같은 code 를 두 번 보내면 DUPLICATED_AGREEMENT_CODE" {
        val userService = service()
        val exception = shouldThrow<AgreementValidationException.DuplicatedCodeException> {
            userService.registerUser(
                registerRequest("dup@test.com", listOf(agreed("TERMS"), agreed("TERMS"), agreed("PRIVACY")))
            )
        }
        exception.code shouldBe "DUPLICATED_AGREEMENT_CODE"
    }

    "가입: 빈 배열은 EMPTY_AGREEMENTS" {
        val userService = service()
        val exception = shouldThrow<AgreementValidationException.EmptyAgreementsException> {
            userService.registerUser(registerRequest("empty@test.com", emptyList()))
        }
        exception.code shouldBe "EMPTY_AGREEMENTS"
    }

    "재동의: 기존 사용자가 동의하면 pendingAgreements 가 비고 이력은 append 된다" {
        val userService = service()
        userService.registerUser(registerRequest("reagree@test.com", null))
        val userId = userIdOf("reagree@test.com")

        val response = userService.updateAgreements(
            userId,
            AgreementsUpdateRequest(listOf(agreed("TERMS"), agreed("PRIVACY")))
        )

        response.pendingAgreements.shouldBeEmpty()
        rowCount(userId) shouldBe 2L
    }

    "재동의: 일부 항목만 보내면 나머지 필수 항목은 pending 으로 남는다" {
        val userService = service()
        userService.registerUser(registerRequest("partial2@test.com", null))
        val userId = userIdOf("partial2@test.com")

        val response = userService.updateAgreements(userId, AgreementsUpdateRequest(listOf(agreed("TERMS"))))

        response.pendingAgreements shouldContainExactly listOf("PRIVACY")
    }

    "재동의: 같은 항목을 다시 보내도 기존 행을 고치지 않고 새 행으로 쌓인다" {
        val userService = service()
        userService.registerUser(registerRequest("append@test.com", listOf(agreed("TERMS"), agreed("PRIVACY"))))
        val userId = userIdOf("append@test.com")

        val response = userService.updateAgreements(userId, AgreementsUpdateRequest(listOf(agreed("TERMS"))))

        response.pendingAgreements.shouldBeEmpty()
        rowCount(userId) shouldBe 3L
    }

    "철회: 필수 항목도 agreed=false 로 철회할 수 있고, 철회한 항목은 pending 에 다시 나온다" {
        val userService = service()
        userService.registerUser(registerRequest("withdraw@test.com", listOf(agreed("TERMS"), agreed("PRIVACY"))))
        val userId = userIdOf("withdraw@test.com")

        val response = userService.updateAgreements(
            userId,
            AgreementsUpdateRequest(listOf(AgreementDecision("PRIVACY", TEST_AGREEMENT_VERSION, false)))
        )

        response.pendingAgreements shouldContainExactly listOf("PRIVACY")
        userService.pendingAgreements(userId) shouldContainExactly listOf("PRIVACY")
        rowCount(userId) shouldBe 3L
        userService.getAgreements(userId).agreements.single { it.code == "PRIVACY" }.agreed shouldBe false
    }

    "철회 후 다시 동의하면 pending 이 비고, 철회 행은 이력에 남는다" {
        val userService = service()
        userService.registerUser(registerRequest("rejoin@test.com", listOf(agreed("TERMS"), agreed("PRIVACY"))))
        val userId = userIdOf("rejoin@test.com")
        userService.updateAgreements(
            userId,
            AgreementsUpdateRequest(listOf(AgreementDecision("TERMS", TEST_AGREEMENT_VERSION, false)))
        )

        val response = userService.updateAgreements(userId, AgreementsUpdateRequest(listOf(agreed("TERMS"))))

        response.pendingAgreements.shouldBeEmpty()
        rowCount(userId) shouldBe 4L
    }

    "pendingAgreements: conf 버전을 올리면 기존 동의자가 다시 pending 이 된다" {
        val oldService = service(catalog("2026-11-01"))
        oldService.registerUser(registerRequest("bump@test.com", listOf(agreed("TERMS"), agreed("PRIVACY"))))
        val userId = userIdOf("bump@test.com")

        val newService = service(catalog("2027-01-01"))

        newService.pendingAgreements(userId) shouldContainExactly listOf("TERMS", "PRIVACY")
    }

    "getAgreements: code 별 최신 1건만 주고 한 번도 동의 안 한 항목은 빠진다" {
        val userService = service()
        userService.registerUser(registerRequest("history@test.com", null))
        val userId = userIdOf("history@test.com")

        userService.updateAgreements(userId, AgreementsUpdateRequest(listOf(agreed("TERMS"))))
        val agreements = userService.getAgreements(userId).agreements

        agreements.map { it.code } shouldContainExactly listOf("TERMS")
        agreements.single().agreed shouldBe true
        agreements.single().required shouldBe true
    }

    "로그인 응답에 pendingAgreements 가 실린다" {
        val userService = service()
        userService.registerUser(registerRequest("login@test.com", null))
        val authService = AuthService(ExposedUserRepo(), userService, DeviceTokenService(noPushSender), "test-secret", "test-issuer", "", "")

        val response = authService.login(UserLoginRequest("login@test.com", "abcd1234"))

        response.pendingAgreements shouldContainExactly listOf("TERMS", "PRIVACY")
    }

    "탈퇴: 유저를 지우면 동의 이력도 cascade 로 함께 지워진다" {
        val userService = service()
        userService.registerUser(registerRequest("cascade@test.com", listOf(agreed("TERMS"), agreed("PRIVACY"))))
        val userId = userIdOf("cascade@test.com")

        transaction(db) { UserRow[userId].delete() }

        rowCount(userId) shouldBe 0L
    }
})

private fun ensureAgreementTestDatabase(url: String, user: String, password: String) {
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
