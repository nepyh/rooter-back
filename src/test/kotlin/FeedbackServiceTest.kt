import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.common.config.EnvironmentMode
import com.github.nepyh.rooter.module.feedback.FeedbackService
import com.github.nepyh.rooter.module.feedback.ReplanLlmClient
import com.github.nepyh.rooter.module.feedback.dto.FeedbackSubmitRequest
import com.github.nepyh.rooter.module.feedback.exception.DailyPlanNotFoundException
import com.github.nepyh.rooter.module.feedback.exception.FeedbackAlreadySubmittedException
import com.github.nepyh.rooter.module.feedback.exception.FeedbackNotFoundException
import com.github.nepyh.rooter.module.feedback.exception.FeedbackValidationException
import com.github.nepyh.rooter.module.feedback.model.DailyFeedbackTable
import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizAttemptTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizChoiceTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizQuestionTable
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
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * feedback 서비스 통합 테스트 (로컬 PostgreSQL 필요).
 *
 * 피드백 제출은 replan 단계에서 퀴즈 오답 테이블을 조회하므로 daily_quiz_* 까지 함께 만든다.
 * AI 재조정 제안(replan)은 응답 검증 대상이 아니고 실패해도 빈 목록으로 흡수되므로 LLM 응답은 고정하지 않는다.
 *
 * 접속 정보는 환경변수로 오버라이드 가능: TEST_JDBC_URL / TEST_DB_USER / TEST_DB_PASSWORD
 */
class FeedbackServiceTest : StringSpec({

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
            exec("DROP TABLE IF EXISTS daily_feedback CASCADE")
            exec("DROP TABLE IF EXISTS daily_quiz_attempts CASCADE")
            exec("DROP TABLE IF EXISTS daily_quiz_choices CASCADE")
            exec("DROP TABLE IF EXISTS daily_quiz_questions CASCADE")
            exec("DROP TABLE IF EXISTS daily_plans CASCADE")
            exec("DROP TABLE IF EXISTS plan_boards CASCADE")
            exec("DROP TABLE IF EXISTS users CASCADE")
            SchemaUtils.create(
                UserTable, PlanBoardTable, DailyPlanTable,
                DailyQuizQuestionTable, DailyQuizChoiceTable, DailyQuizAttemptTable, DailyFeedbackTable
            )
        }
    }

    // 테스트 간 데이터 격리: 매 테스트 시작 전 전체 초기화 (FK 순서 주의)
    beforeEach {
        transaction(db) {
            DailyFeedbackTable.deleteAll()
            DailyQuizAttemptTable.deleteAll()
            DailyQuizChoiceTable.deleteAll()
            DailyQuizQuestionTable.deleteAll()
            DailyPlanTable.deleteAll()
            PlanBoardTable.deleteAll()
            UserTable.deleteAll()
        }
    }

    val feedbackService = FeedbackService(ReplanLlmClient(dummyAppConfig()))

    val day = LocalDate.of(2026, 9, 29)

    fun seedDailyPlan(email: String = "feedback@test.com"): Pair<Int, Int> = transaction(db) {
        val user = UserRow.new {
            this.email = email
            username = "tester"
            password = "x"
            createdAt = OffsetDateTime.now()
        }
        val board = PlanBoardRow.new {
            this.user = user
            title = "테스트 보드"
            startDate = day
            endDate = day
            createdAt = OffsetDateTime.now()
        }
        val dailyPlan = DailyPlanRow.new {
            planBoard = board
            planDate = day
        }
        user.id.value to dailyPlan.id.value
    }

    "createdAt: 제출·조회 응답의 createdAt 은 오프셋이 포함된 시각이다" {
        val (userId, dailyPlanId) = seedDailyPlan()

        val submitted = feedbackService.submitFeedback(
            userId,
            dailyPlanId,
            FeedbackSubmitRequest(difficulty = "적당", timeSpentMinutes = 30, focusLevel = 3)
        )
        val fetched = feedbackService.getFeedback(userId, dailyPlanId)

        // 저장된 값과 조회한 값이 같은 시각이다 (조회 경로도 같은 포맷을 쓴다)
        fetched.id shouldBe submitted.id
        fetched.createdAt shouldBe submitted.createdAt

        listOf(submitted.createdAt, fetched.createdAt).forEach { createdAt ->
            // 오프셋이 없으면 OffsetDateTime.parse 가 실패한다
            val parsed = OffsetDateTime.parse(createdAt)
            Duration.between(parsed, OffsetDateTime.now()).abs().toMinutes() shouldBe 0L
        }
    }

    "submitFeedback: 어려움이 목록 밖의 값이면 InvalidDifficultyException" {
        val (userId, dailyPlanId) = seedDailyPlan()

        shouldThrow<FeedbackValidationException.InvalidDifficultyException> {
            feedbackService.submitFeedback(userId, dailyPlanId, FeedbackSubmitRequest(difficulty = "보통"))
        }
    }

    "submitFeedback: 소요 시간이 1분 미만이면 InvalidTimeSpentMinutesException" {
        val (userId, dailyPlanId) = seedDailyPlan()

        shouldThrow<FeedbackValidationException.InvalidTimeSpentMinutesException> {
            feedbackService.submitFeedback(
                userId,
                dailyPlanId,
                FeedbackSubmitRequest(difficulty = "적당", timeSpentMinutes = 0)
            )
        }
    }

    "submitFeedback: 집중도가 1~5 밖이면 InvalidFocusLevelException" {
        val (userId, dailyPlanId) = seedDailyPlan()

        shouldThrow<FeedbackValidationException.InvalidFocusLevelException> {
            feedbackService.submitFeedback(
                userId,
                dailyPlanId,
                FeedbackSubmitRequest(difficulty = "적당", focusLevel = 6)
            )
        }
    }

    "submitFeedback: 같은 일일 계획에 두 번 제출하면 FeedbackAlreadySubmittedException" {
        val (userId, dailyPlanId) = seedDailyPlan()

        feedbackService.submitFeedback(userId, dailyPlanId, FeedbackSubmitRequest(difficulty = "적당", focusLevel = 3))

        shouldThrow<FeedbackAlreadySubmittedException> {
            feedbackService.submitFeedback(userId, dailyPlanId, FeedbackSubmitRequest(difficulty = "어려움", focusLevel = 2))
        }
    }

    "submitFeedback: 남의 일일 계획이면 DailyPlanNotFoundException" {
        val (_, dailyPlanId) = seedDailyPlan("owner@test.com")
        val (otherUserId, _) = seedDailyPlan("other@test.com")

        shouldThrow<DailyPlanNotFoundException> {
            feedbackService.submitFeedback(otherUserId, dailyPlanId, FeedbackSubmitRequest(difficulty = "적당"))
        }
    }

    "getFeedback: 제출한 피드백이 없으면 FeedbackNotFoundException" {
        val (userId, dailyPlanId) = seedDailyPlan()

        shouldThrow<FeedbackNotFoundException> {
            feedbackService.getFeedback(userId, dailyPlanId)
        }
    }
})

private fun dummyAppConfig() = AppConfig(
    environment = EnvironmentMode.DEV,
    jdbcUrl = "", dbUsername = "", dbPassword = "", dbMaxPoolSize = 1,
    corsAllowedHosts = emptyList(), corsMaxAgeSeconds = 0,
    storageType = "local", storageBaseDir = null, storageBaseUrl = null, storageBaseRoute = null,
    storageAwsRegion = null, storageAwsBucket = null,
    jwtSecret = "", jwtIssuer = "",
    llmBaseUrl = "", llmApiKey = "", llmModel = "",
    niceApiKey = "", niceBaseUrl = "",
    googleClientId = "", appleClientId = ""
)

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
