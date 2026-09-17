import com.github.nepyh.rooter.module.planboard.CatalogService
import com.github.nepyh.rooter.module.planboard.model.SchoolTextbookAdoptions
import com.github.nepyh.rooter.module.planboard.model.Subjects
import com.github.nepyh.rooter.module.planboard.model.Textbooks
import com.github.nepyh.rooter.module.user.model.StudentProfileRow
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import java.sql.SQLException
import java.time.OffsetDateTime

/**
 * 학교-교과서 매핑(school_textbook_adoptions) 조회 통합 테스트 (로컬 PostgreSQL 필요).
 * 실행 방법은 PlanBoardServiceTest 와 동일 (TEST_JDBC_URL / TEST_DB_USER / TEST_DB_PASSWORD).
 */
class CatalogServiceTest : StringSpec({

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
            // CASCADE 로 드랍: users 를 다른 스펙(예: PlanBoardServiceTest)의 테이블이 FK 로 참조하고
            // 있어도 실행 순서와 무관하게 안전하게 재생성하기 위함
            exec("DROP TABLE IF EXISTS school_textbook_adoptions CASCADE")
            exec("DROP TABLE IF EXISTS textbooks CASCADE")
            exec("DROP TABLE IF EXISTS subjects CASCADE")
            exec("DROP TABLE IF EXISTS student_profiles CASCADE")
            exec("DROP TABLE IF EXISTS users CASCADE")
            SchemaUtils.create(UserTable, StudentProfileTable, Subjects, Textbooks, SchoolTextbookAdoptions)
        }
    }

    beforeEach {
        transaction(db) {
            SchoolTextbookAdoptions.deleteAll()
            Textbooks.deleteAll()
            Subjects.deleteAll()
            StudentProfileTable.deleteAll()
            UserTable.deleteAll()
        }
    }

    val catalogService = CatalogService()

    fun seedUser(email: String): Int = transaction(db) {
        UserRow.new {
            this.email = email
            username = "tester"
            password = "x"
            createdAt = OffsetDateTime.now()
        }.id.value
    }

    fun seedProfile(userId: Int, schoolId: String, grade: Int) = transaction(db) {
        StudentProfileRow.new {
            user = UserRow[userId]
            this.schoolId = schoolId
            this.grade = grade
            classNumber = 1
        }
    }

    fun seedSubject(name: String): Int = transaction(db) {
        Subjects.insert { it[this.name] = name } get Subjects.id
    }

    fun seedTextbook(subjectId: Int, title: String): Int = transaction(db) {
        Textbooks.insert {
            it[this.subjectId] = subjectId
            it[this.title] = title
        } get Textbooks.id
    }

    fun seedAdoption(schoolId: String, grade: Int, subjectId: Int, textbookId: Int) = transaction(db) {
        SchoolTextbookAdoptions.insert {
            it[this.schoolId] = schoolId
            it[this.grade] = grade
            it[this.subjectId] = subjectId
            it[this.textbookId] = textbookId
        }
    }

    "getRecommendedTextbooks: 학생 프로필이 없으면 빈 목록을 반환한다" {
        val userId = seedUser("no-profile@test.com")

        val result = catalogService.getRecommendedTextbooks(userId)

        result.shouldBeEmpty()
    }

    "getRecommendedTextbooks: 학교/학년에 매핑 데이터가 없으면 빈 목록을 반환한다" {
        val userId = seedUser("no-mapping@test.com")
        seedProfile(userId, schoolId = "C107181084", grade = 2)

        val result = catalogService.getRecommendedTextbooks(userId)

        result.shouldBeEmpty()
    }

    "getRecommendedTextbooks: 매핑이 있으면 과목명/교과서명과 함께 반환한다" {
        val userId = seedUser("mapped@test.com")
        seedProfile(userId, schoolId = "C107181084", grade = 2)
        val subjectId = seedSubject("수학")
        val textbookId = seedTextbook(subjectId, "중학수학 2-1 (천재교육)")
        seedAdoption(schoolId = "C107181084", grade = 2, subjectId = subjectId, textbookId = textbookId)

        val result = catalogService.getRecommendedTextbooks(userId)

        result shouldBe listOf(
            com.github.nepyh.rooter.module.planboard.dto.RecommendedTextbookResponse(
                subjectId = subjectId,
                subjectName = "수학",
                textbookId = textbookId,
                textbookTitle = "중학수학 2-1 (천재교육)"
            )
        )
    }

    "getRecommendedTextbooks: 다른 학년의 매핑은 포함되지 않는다" {
        val userId = seedUser("wrong-grade@test.com")
        seedProfile(userId, schoolId = "C107181084", grade = 2)
        val subjectId = seedSubject("영어")
        val textbookId = seedTextbook(subjectId, "중학영어 3 (YBM)")
        seedAdoption(schoolId = "C107181084", grade = 3, subjectId = subjectId, textbookId = textbookId)

        val result = catalogService.getRecommendedTextbooks(userId)

        result.shouldBeEmpty()
    }

    "getRecommendedTextbooks: 다른 학교의 매핑은 포함되지 않는다" {
        val userId = seedUser("wrong-school@test.com")
        seedProfile(userId, schoolId = "C107181084", grade = 2)
        val subjectId = seedSubject("국어")
        val textbookId = seedTextbook(subjectId, "중학국어 2 (미래엔)")
        seedAdoption(schoolId = "C999999999", grade = 2, subjectId = subjectId, textbookId = textbookId)

        val result = catalogService.getRecommendedTextbooks(userId)

        result.shouldBeEmpty()
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
