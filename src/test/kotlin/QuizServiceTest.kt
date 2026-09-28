import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.common.config.EnvironmentMode
import com.github.nepyh.rooter.module.planboard.model.ChapterTable
import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.planboard.model.SubjectTable
import com.github.nepyh.rooter.module.planboard.model.TextbookTable
import com.github.nepyh.rooter.module.quiz.GeneratedQuestion
import com.github.nepyh.rooter.module.quiz.QuizLlmClient
import com.github.nepyh.rooter.module.quiz.QuizService
import com.github.nepyh.rooter.module.quiz.exception.QuizValidationException
import com.github.nepyh.rooter.module.quiz.model.DailyQuizAttemptTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizChoiceTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizQuestionTable
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * 일일 퀴즈 서비스 통합 테스트 (로컬 PostgreSQL 필요). LLM 은 [FakeQuizLlmClient] 로 고정 응답을 준다.
 *
 * 접속 정보는 환경변수로 오버라이드 가능: TEST_JDBC_URL / TEST_DB_USER / TEST_DB_PASSWORD
 */
class QuizServiceTest : StringSpec({

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
            exec("DROP TABLE IF EXISTS daily_quiz_attempts CASCADE")
            exec("DROP TABLE IF EXISTS daily_quiz_choices CASCADE")
            exec("DROP TABLE IF EXISTS daily_quiz_questions CASCADE")
            exec("DROP TABLE IF EXISTS plan_tasks CASCADE")
            exec("DROP TABLE IF EXISTS plan_subjects CASCADE")
            exec("DROP TABLE IF EXISTS daily_plans CASCADE")
            exec("DROP TABLE IF EXISTS plan_boards CASCADE")
            exec("DROP TABLE IF EXISTS chapters CASCADE")
            exec("DROP TABLE IF EXISTS textbooks CASCADE")
            exec("DROP TABLE IF EXISTS subjects CASCADE")
            exec("DROP TABLE IF EXISTS users CASCADE")
            SchemaUtils.create(
                UserTable, SubjectTable, TextbookTable, ChapterTable, PlanBoardTable, PlanSubjectTable,
                DailyPlanTable, PlanTaskTable, DailyQuizQuestionTable, DailyQuizChoiceTable, DailyQuizAttemptTable
            )
        }
    }

    beforeEach {
        transaction(db) {
            DailyQuizAttemptTable.deleteAll()
            DailyQuizChoiceTable.deleteAll()
            DailyQuizQuestionTable.deleteAll()
            PlanTaskTable.deleteAll()
            PlanSubjectTable.deleteAll()
            DailyPlanTable.deleteAll()
            PlanBoardTable.deleteAll()
            UserTable.deleteAll()
        }
    }

    val day = LocalDate.of(2026, 9, 29)

    fun seedDailyPlan(): Pair<Int, Int> = transaction(db) {
        val user = UserRow.new {
            email = "quiz@test.com"
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

    fun storedQuestionCount(dailyPlanId: Int): Long = transaction(db) {
        DailyQuizQuestionTable.selectAll().where { DailyQuizQuestionTable.dailyPlanId eq dailyPlanId }.count()
    }

    "generateQuiz: 이미 만든 퀴즈가 있으면 새로 만들지 않고 같은 퀴즈를 돌려준다" {
        val (userId, dailyPlanId) = seedDailyPlan()
        val llm = FakeQuizLlmClient()
        val service = QuizService(llm)

        val first = service.generateQuiz(userId, day)
        val second = service.generateQuiz(userId, day)

        llm.calls.get() shouldBe 1
        storedQuestionCount(dailyPlanId) shouldBe 5L
        second.questions.map { it.id } shouldBe first.questions.map { it.id }
        second.questions.map { q -> q.choices.map { it.id } } shouldBe first.questions.map { q -> q.choices.map { it.id } }
        service.getQuiz(userId, dailyPlanId).questions.map { it.id } shouldBe first.questions.map { it.id }
    }

    "generateQuiz: 동시에 두 번 요청돼도 한 세트만 저장되고 둘 다 같은 퀴즈를 받는다" {
        val (userId, dailyPlanId) = seedDailyPlan()
        // 두 요청이 모두 '퀴즈 없음' 을 확인한 뒤 LLM 호출 단계에서 겹치도록 응답을 늦춘다
        val llm = FakeQuizLlmClient(delayMillis = 500)
        val service = QuizService(llm)

        val results = coroutineScope {
            listOf(async { service.generateQuiz(userId, day) }, async { service.generateQuiz(userId, day) }).awaitAll()
        }

        storedQuestionCount(dailyPlanId) shouldBe 5L
        results[1].questions.map { it.id } shouldBe results[0].questions.map { it.id }
    }

    "generateQuiz: LLM 이 빈 목록을 주면 QuizGenerationFailedException 이고 아무것도 저장하지 않는다" {
        val (userId, dailyPlanId) = seedDailyPlan()
        val service = QuizService(FakeQuizLlmClient(questionCount = 0))

        shouldThrow<QuizValidationException.QuizGenerationFailedException> {
            service.generateQuiz(userId, day)
        }
        storedQuestionCount(dailyPlanId) shouldBe 0L
    }

    "generateQuiz: 해당 날짜에 계획이 없으면 NoPlanForDateException" {
        val (userId, _) = seedDailyPlan()

        shouldThrow<QuizValidationException.NoPlanForDateException> {
            QuizService(FakeQuizLlmClient()).generateQuiz(userId, day.plusDays(1))
        }
    }
})

private class FakeQuizLlmClient(
    private val questionCount: Int = 5,
    private val delayMillis: Long = 0
) : QuizLlmClient(dummyAppConfig()) {
    val calls = AtomicInteger(0)

    override suspend fun generateQuestions(context: String, count: Int): List<GeneratedQuestion> {
        val n = calls.incrementAndGet()
        if (delayMillis > 0) delay(delayMillis)
        return (1..questionCount).map { i ->
            GeneratedQuestion(
                questionText = "문제 $i (호출 $n)",
                choices = listOf("A", "B", "C", "D"),
                correctIndex = 0
            )
        }
    }
}

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
