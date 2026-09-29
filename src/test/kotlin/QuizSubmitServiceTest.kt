import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.common.config.EnvironmentMode
import com.github.nepyh.rooter.module.planboard.model.ChapterTable
import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.planboard.model.SubjectTable
import com.github.nepyh.rooter.module.planboard.model.TextbookTable
import com.github.nepyh.rooter.module.quiz.QuizLlmClient
import com.github.nepyh.rooter.module.quiz.QuizService
import com.github.nepyh.rooter.module.quiz.WeakAreaSuggestion
import com.github.nepyh.rooter.module.quiz.dto.QuizAnswerSubmission
import com.github.nepyh.rooter.module.quiz.exception.QuizValidationException
import com.github.nepyh.rooter.module.quiz.model.DailyQuizAttemptTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizChoiceRow
import com.github.nepyh.rooter.module.quiz.model.DailyQuizChoiceTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizQuestionRow
import com.github.nepyh.rooter.module.quiz.model.DailyQuizQuestionTable
import com.github.nepyh.rooter.module.school.NiceApiClient
import com.github.nepyh.rooter.module.school.SchoolDataFetcher
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
import com.github.nepyh.rooter.module.user.model.UnavailableTimeTable
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * 일일 퀴즈 제출(채점 + 복습 태스크 추가) 통합 테스트 (로컬 PostgreSQL 필요).
 * 약점 분석 LLM 은 [FakeWeakAreaLlmClient] 로 고정 응답을 주고, 학생 프로필이 없어 NICE 는 호출되지 않는다.
 *
 * 접속 정보는 환경변수로 오버라이드 가능: TEST_JDBC_URL / TEST_DB_USER / TEST_DB_PASSWORD
 */
class QuizSubmitServiceTest : StringSpec({

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
            listOf(
                "daily_quiz_attempts", "daily_quiz_choices", "daily_quiz_questions", "plan_tasks", "plan_subjects",
                "daily_plans", "plan_boards", "chapters", "textbooks", "subjects",
                "user_unavailable_times", "student_profiles", "users"
            ).forEach { exec("DROP TABLE IF EXISTS $it CASCADE") }
            SchemaUtils.create(
                UserTable, StudentProfileTable, UnavailableTimeTable, SubjectTable, TextbookTable, ChapterTable,
                PlanBoardTable, PlanSubjectTable, DailyPlanTable, PlanTaskTable,
                DailyQuizQuestionTable, DailyQuizChoiceTable, DailyQuizAttemptTable
            )
        }
    }

    beforeEach {
        transaction(db) {
            DailyQuizAttemptTable.deleteAll()
            DailyQuizChoiceTable.deleteAll()
            DailyQuizQuestionTable.deleteAll()
            PlanTaskTable.deleteAll()
            DailyPlanTable.deleteAll()
            PlanBoardTable.deleteAll()
            UserTable.deleteAll()
        }
    }

    // 학생 프로필이 없으면 NICE 를 부르지 않으므로, 불리면 바로 실패하게 둔다
    val schoolDataFetcher = SchoolDataFetcher(
        NiceApiClient(apiKey = "test-key", httpClient = HttpClient(MockEngine { error("NICE 가 호출되면 안 됨") }))
    )

    val friday = LocalDate.of(2026, 10, 2)
    val saturday = friday.plusDays(1)

    data class SeededQuiz(val userId: Int, val boardId: Int, val dailyPlanId: Int, val answers: List<Pair<Int, Pair<Int, Int>>>)

    /** 퀴즈 날짜의 일일 계획과 2문제(각 정답·오답 보기)를 만든다. answers = questionId to (정답 choiceId, 오답 choiceId) */
    fun seedQuiz(quizDate: LocalDate, boardEnd: LocalDate): SeededQuiz = transaction(db) {
        val user = UserRow.new {
            email = "quiz-submit@test.com"; username = "tester"; password = "x"; createdAt = OffsetDateTime.now()
        }
        val board = PlanBoardRow.new {
            this.user = user; title = "보드"; startDate = quizDate; endDate = boardEnd; createdAt = OffsetDateTime.now()
        }
        val dailyPlan = DailyPlanRow.new { planBoard = board; planDate = quizDate }
        val answers = (1..2).map { i ->
            val question = DailyQuizQuestionRow.new { this.dailyPlan = dailyPlan; questionText = "문제 $i" }
            val correct = DailyQuizChoiceRow.new { this.question = question; choiceText = "정답"; isCorrect = true }
            val wrong = DailyQuizChoiceRow.new { this.question = question; choiceText = "오답"; isCorrect = false }
            question.id.value to (correct.id.value to wrong.id.value)
        }
        SeededQuiz(user.id.value, board.id.value, dailyPlan.id.value, answers)
    }

    fun seedTask(boardId: Int, date: LocalDate, start: LocalTime, end: LocalTime) = transaction(db) {
        val dailyPlan = DailyPlanRow.find { (DailyPlanTable.planBoardId eq boardId) }.firstOrNull { it.planDate == date }
            ?: DailyPlanRow.new { planBoard = PlanBoardRow[boardId]; planDate = date }
        PlanTaskRow.new {
            this.dailyPlan = dailyPlan; taskName = "기존 태스크"; startTime = start; endTime = end
            estimatedMinutes = (end.toSecondOfDay() - start.toSecondOfDay()) / 60
        }
    }

    fun wrongAnswers(quiz: SeededQuiz) = quiz.answers.map { (q, choices) -> QuizAnswerSubmission(q, choices.second) }
    val twoSuggestions = listOf(WeakAreaSuggestion("1단원", "소인수분해 다시 풀기"), WeakAreaSuggestion("2단원", "정수 계산 연습"))

    "약점 분석 LLM 이 실패해도 채점 결과는 저장되고 제출은 성공한다" {
        val quiz = seedQuiz(friday, friday.plusDays(7))
        val service = QuizService(FakeWeakAreaLlmClient(fail = true), schoolDataFetcher)

        val result = service.submitQuiz(quiz.userId, quiz.dailyPlanId, wrongAnswers(quiz))

        result.correctCount shouldBe 0
        result.weakAreas shouldBe emptyList()
        result.insertedReviewTasks shouldBe emptyList()
        transaction(db) { DailyQuizAttemptTable.selectAll().count() } shouldBe 2L
        // 저장됐으므로 다시 내면 이미 제출함
        shouldThrow<QuizValidationException.AlreadySubmittedException> {
            service.submitQuiz(quiz.userId, quiz.dailyPlanId, wrongAnswers(quiz))
        }
    }

    "복습 태스크는 다음 날에, 그날 있던 태스크를 피해서 들어가고 응답에 시각이 담긴다" {
        val quiz = seedQuiz(friday, friday.plusDays(7))
        seedTask(quiz.boardId, saturday, LocalTime.of(6, 30), LocalTime.of(7, 30))
        val service = QuizService(FakeWeakAreaLlmClient(twoSuggestions), schoolDataFetcher)

        val result = service.submitQuiz(quiz.userId, quiz.dailyPlanId, wrongAnswers(quiz))

        // 토요일: 취침 06:30 까지, 기존 태스크 06:30~07:30 → 07:30 부터 20분씩 (새로 넣는 태스크 사이 10분 휴식)
        result.insertedReviewTasks.map { Triple(it.planDate, it.startTime, it.endTime) } shouldBe listOf(
            Triple("2026-10-03", "07:30", "07:50"),
            Triple("2026-10-03", "08:00", "08:20")
        )
        result.insertedReviewTasks.map { it.taskName } shouldBe listOf("복습: 소인수분해 다시 풀기", "복습: 정수 계산 연습")
        transaction(db) { PlanTaskRow.all().count { it.taskName.startsWith("복습:") } } shouldBe 2
    }

    "퀴즈 날이 플랜보드 마지막 날이면 그날의 남은 빈 시간에 넣고, 자정을 넘기지 않는다" {
        val quiz = seedQuiz(saturday, saturday)
        seedTask(quiz.boardId, saturday, LocalTime.of(6, 30), LocalTime.of(22, 0))
        val service = QuizService(FakeWeakAreaLlmClient(twoSuggestions), schoolDataFetcher)

        val result = service.submitQuiz(quiz.userId, quiz.dailyPlanId, wrongAnswers(quiz))

        result.insertedReviewTasks.map { Triple(it.planDate, it.startTime, it.endTime) } shouldBe listOf(
            Triple("2026-10-03", "22:00", "22:20"),
            Triple("2026-10-03", "22:30", "22:50")
        )
    }

    "빈 시간이 없어 못 넣은 복습 태스크는 insertedReviewTasks 에 나오지 않는다" {
        val quiz = seedQuiz(saturday, saturday)
        seedTask(quiz.boardId, saturday, LocalTime.of(6, 30), LocalTime.of(23, 0))
        val service = QuizService(FakeWeakAreaLlmClient(twoSuggestions), schoolDataFetcher)

        val result = service.submitQuiz(quiz.userId, quiz.dailyPlanId, wrongAnswers(quiz))

        result.weakAreas.size shouldBe 2
        result.insertedReviewTasks shouldBe emptyList()
        transaction(db) { PlanTaskRow.all().count { it.taskName.startsWith("복습:") } } shouldBe 0
    }

    "모두 맞히면 약점 분석을 부르지 않는다" {
        val quiz = seedQuiz(friday, friday.plusDays(7))
        val llm = FakeWeakAreaLlmClient(twoSuggestions)

        val result = QuizService(llm, schoolDataFetcher).submitQuiz(
            quiz.userId, quiz.dailyPlanId, quiz.answers.map { (q, choices) -> QuizAnswerSubmission(q, choices.first) }
        )

        result.correctCount shouldBe 2
        llm.calls.get() shouldBe 0
        result.insertedReviewTasks shouldBe emptyList()
    }
})

private class FakeWeakAreaLlmClient(
    private val suggestions: List<WeakAreaSuggestion> = emptyList(),
    private val fail: Boolean = false
) : QuizLlmClient(dummyAppConfig()) {
    val calls = AtomicInteger(0)

    override suspend fun analyzeWeakAreas(wrongQuestionTexts: List<String>, chapterNames: List<String>): List<WeakAreaSuggestion> {
        calls.incrementAndGet()
        if (fail) throw QuizValidationException.QuizGenerationFailedException()
        return suggestions
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
