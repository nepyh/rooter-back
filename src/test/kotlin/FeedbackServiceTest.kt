import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.common.config.EnvironmentMode
import com.github.nepyh.rooter.module.feedback.FeedbackReplanJob
import com.github.nepyh.rooter.module.feedback.FeedbackService
import com.github.nepyh.rooter.module.feedback.ReplanLlmClient
import com.github.nepyh.rooter.module.feedback.dto.FeedbackSubmitRequest
import com.github.nepyh.rooter.module.feedback.exception.DailyPlanNotFoundException
import com.github.nepyh.rooter.module.feedback.exception.FeedbackAlreadySubmittedException
import com.github.nepyh.rooter.module.feedback.exception.FeedbackNotFoundException
import com.github.nepyh.rooter.module.feedback.exception.FeedbackValidationException
import com.github.nepyh.rooter.module.feedback.model.DailyFeedbackTable
import com.github.nepyh.rooter.module.planboard.exception.PlanTaskNotFoundException
import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizAttemptTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizChoiceTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizQuestionTable
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * feedback 서비스 통합 테스트 (로컬 PostgreSQL 필요).
 *
 * 설문은 할일(plan_task) 단위이고, 조정(AI)은 제출이 아니라 자정 배치 [FeedbackReplanJob] 이 하루 1회 돈다.
 * 조정 근거를 만들 때 퀴즈 오답 테이블을 조회하므로 daily_quiz_* 까지 함께 만든다.
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
            exec("DROP TABLE IF EXISTS plan_tasks CASCADE")
            exec("DROP TABLE IF EXISTS daily_plans CASCADE")
            exec("DROP TABLE IF EXISTS plan_boards CASCADE")
            exec("DROP TABLE IF EXISTS users CASCADE")
            SchemaUtils.create(
                UserTable, PlanBoardTable, DailyPlanTable, PlanTaskTable,
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
            PlanTaskTable.deleteAll()
            DailyPlanTable.deleteAll()
            PlanBoardTable.deleteAll()
            UserTable.deleteAll()
        }
    }

    val feedbackService = FeedbackService(ReplanLlmClient(dummyAppConfig()))

    val day = LocalDate.of(2026, 9, 29)

    /** 사용자·플랜보드·일일 계획·할일 하나를 만들고 (userId, dailyPlanId, taskId) 를 돌려준다. */
    fun seedPlan(email: String = "feedback@test.com", taskName: String = "과학 1단원"): Triple<Int, Int, Int> =
        transaction(db) {
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
            val task = PlanTaskRow.new {
                this.dailyPlan = dailyPlan
                this.taskName = taskName
                startTime = LocalTime.of(9, 0)
                endTime = LocalTime.of(10, 0)
                estimatedMinutes = 60
            }
            Triple(user.id.value, dailyPlan.id.value, task.id.value)
        }

    /** 이미 만든 일일 계획에 할일을 하나 더 붙인다. */
    fun addTask(dailyPlanId: Int, taskName: String, startHour: Int = 11): Int = transaction(db) {
        val task = PlanTaskRow.new {
            dailyPlan = DailyPlanRow[dailyPlanId]
            this.taskName = taskName
            startTime = LocalTime.of(startHour, 0)
            endTime = LocalTime.of(startHour + 1, 0)
            estimatedMinutes = 60
        }
        task.id.value
    }

    fun boardIdOf(dailyPlanId: Int): Int = transaction(db) {
        DailyPlanRow[dailyPlanId].planBoard.id.value
    }

    "createdAt: 제출·조회 응답의 createdAt 은 오프셋이 포함된 시각이다" {
        val (userId, dailyPlanId, taskId) = seedPlan()

        val submitted = feedbackService.submitFeedback(
            userId,
            taskId,
            FeedbackSubmitRequest(difficulty = "적당", timeSpentMinutes = 30, focusLevel = 3)
        )
        val fetched = feedbackService.getFeedbackByTask(userId, taskId)

        // 저장된 값과 조회한 값이 같은 시각이다 (조회 경로도 같은 포맷을 쓴다)
        fetched.id shouldBe submitted.id
        fetched.createdAt shouldBe submitted.createdAt
        fetched.taskId shouldBe taskId
        fetched.dailyPlanId shouldBe dailyPlanId

        listOf(submitted.createdAt, fetched.createdAt).forEach { createdAt ->
            // 오프셋이 없으면 OffsetDateTime.parse 가 실패한다
            val parsed = OffsetDateTime.parse(createdAt)
            Duration.between(parsed, OffsetDateTime.now()).abs().toMinutes() shouldBe 0L
        }
    }

    "submitFeedback: 어려움이 목록 밖의 값이면 InvalidDifficultyException" {
        val (userId, _, taskId) = seedPlan()

        shouldThrow<FeedbackValidationException.InvalidDifficultyException> {
            feedbackService.submitFeedback(userId, taskId, FeedbackSubmitRequest(difficulty = "보통"))
        }
    }

    "submitFeedback: 소요 시간이 1분 미만이면 InvalidTimeSpentMinutesException" {
        val (userId, _, taskId) = seedPlan()

        shouldThrow<FeedbackValidationException.InvalidTimeSpentMinutesException> {
            feedbackService.submitFeedback(
                userId,
                taskId,
                FeedbackSubmitRequest(difficulty = "적당", timeSpentMinutes = 0)
            )
        }
    }

    "submitFeedback: 집중도가 1~5 밖이면 InvalidFocusLevelException" {
        val (userId, _, taskId) = seedPlan()

        shouldThrow<FeedbackValidationException.InvalidFocusLevelException> {
            feedbackService.submitFeedback(
                userId,
                taskId,
                FeedbackSubmitRequest(difficulty = "적당", focusLevel = 6)
            )
        }
    }

    "submitFeedback: 같은 할일에 두 번 제출하면 FeedbackAlreadySubmittedException" {
        val (userId, _, taskId) = seedPlan()

        feedbackService.submitFeedback(userId, taskId, FeedbackSubmitRequest(difficulty = "적당", focusLevel = 3))

        shouldThrow<FeedbackAlreadySubmittedException> {
            feedbackService.submitFeedback(userId, taskId, FeedbackSubmitRequest(difficulty = "어려움", focusLevel = 2))
        }
    }

    "submitFeedback: 같은 날 다른 할일의 설문은 따로 제출되고 그날 목록에 둘 다 담긴다" {
        val (userId, dailyPlanId, firstTaskId) = seedPlan()
        val secondTaskId = addTask(dailyPlanId, "과학 2단원")

        feedbackService.submitFeedback(userId, firstTaskId, FeedbackSubmitRequest(difficulty = "적당", focusLevel = 3))
        feedbackService.submitFeedback(userId, secondTaskId, FeedbackSubmitRequest(difficulty = "어려움", focusLevel = 2))

        val feedbacks = feedbackService.getFeedbacksByDailyPlan(userId, dailyPlanId)

        feedbacks.map { it.taskId } shouldContainExactly listOf(firstTaskId, secondTaskId)
    }

    "submitFeedback: 남의 할일이면 PlanTaskNotFoundException" {
        val (_, _, otherTaskId) = seedPlan("owner@test.com")
        val (otherUserId, _, _) = seedPlan("other@test.com")

        shouldThrow<PlanTaskNotFoundException> {
            feedbackService.submitFeedback(otherUserId, otherTaskId, FeedbackSubmitRequest(difficulty = "적당"))
        }
    }

    "getFeedbackByTask: 제출한 설문이 없으면 FeedbackNotFoundException" {
        val (userId, _, taskId) = seedPlan()

        shouldThrow<FeedbackNotFoundException> {
            feedbackService.getFeedbackByTask(userId, taskId)
        }
    }

    "getFeedbacksByDailyPlan: 남의 일일 계획이면 DailyPlanNotFoundException" {
        val (_, otherDailyPlanId, _) = seedPlan("owner@test.com")
        val (otherUserId, _, _) = seedPlan("other@test.com")

        shouldThrow<DailyPlanNotFoundException> {
            feedbackService.getFeedbacksByDailyPlan(otherUserId, otherDailyPlanId)
        }
    }

    "buildReplanContext: 제출된 설문만 근거로 쓰고 제출하지 않은 할일은 빠진다" {
        val (userId, dailyPlanId, submittedTaskId) = seedPlan(taskName = "제출한 할일")
        addTask(dailyPlanId, "제출 안 한 할일")

        feedbackService.submitFeedback(
            userId,
            submittedTaskId,
            FeedbackSubmitRequest(difficulty = "어려움", timeSpentMinutes = 90, focusLevel = 2)
        )

        val context = feedbackService.buildReplanContext(userId, boardIdOf(dailyPlanId), day)

        context shouldNotBe null
        context!! shouldContain "제출한 할일"
        context shouldNotContain "제출 안 한 할일"
    }

    "buildReplanContext: 제출된 설문이 없으면 null (조정 트리거 없음)" {
        val (userId, dailyPlanId, _) = seedPlan()
        addTask(dailyPlanId, "제출 안 한 할일")

        feedbackService.buildReplanContext(userId, boardIdOf(dailyPlanId), day) shouldBe null
    }

    "FeedbackReplanJob: 설문이 0건인 날은 후보가 없다" {
        val (_, dailyPlanId, _) = seedPlan()
        addTask(dailyPlanId, "제출 안 한 할일")

        val due = FeedbackReplanJob(feedbackService).findDue(afterMidnightOf(day))

        due shouldBe emptyList()
    }

    "FeedbackReplanJob: 설문이 있는 보드·날짜마다 하루 1건 후보를 만들고 runKey 에 날짜·보드를 담는다" {
        val (userId, dailyPlanId, taskId) = seedPlan()
        feedbackService.submitFeedback(userId, taskId, FeedbackSubmitRequest(difficulty = "어려움"))

        val due = FeedbackReplanJob(feedbackService).findDue(afterMidnightOf(day))

        due.map { it.runKey } shouldContainExactly listOf("feedback_replan_${day}_${boardIdOf(dailyPlanId)}")
    }
})

/** `day` 다음날 00:30 (KST) — 자정 배치가 직전 하루를 대상으로 도는 시각. */
private fun afterMidnightOf(day: LocalDate): OffsetDateTime =
    day.plusDays(1).atTime(0, 30).atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime()

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
