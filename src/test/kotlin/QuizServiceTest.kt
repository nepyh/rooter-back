import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.common.config.EnvironmentMode
import com.github.nepyh.rooter.module.planboard.model.ChapterTable
import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.TextbookRow
import com.github.nepyh.rooter.module.planboard.model.SubjectRow
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectRow
import com.github.nepyh.rooter.module.planboard.model.ChapterRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.planboard.model.SubjectTable
import com.github.nepyh.rooter.module.planboard.model.TextbookTable
import com.github.nepyh.rooter.module.quiz.GeneratedQuestion
import com.github.nepyh.rooter.module.school.NiceApiClient
import com.github.nepyh.rooter.module.school.SchoolDataFetcher
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import com.github.nepyh.rooter.module.quiz.QuizLlmClient
import com.github.nepyh.rooter.module.quiz.QuizService
import com.github.nepyh.rooter.module.quiz.dailyQuizQuestionCount
import com.github.nepyh.rooter.module.quiz.exception.QuizValidationException
import com.github.nepyh.rooter.module.quiz.model.DailyQuizAttemptTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizChoiceTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizQuestionTable
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
import java.time.LocalTime
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
    // 퀴즈 생성은 NICE 를 쓰지 않는다 (QuizService 생성자에 필요할 뿐)
    val noNiceFetcher = SchoolDataFetcher(NiceApiClient(apiKey = "test-key", httpClient = HttpClient(MockEngine { error("NICE 가 호출되면 안 됨") })))

    /** completedTasks 개의 완료한 할일이 있는 일일 계획을 만든다 */
    fun seedDailyPlan(completedTasks: Int = 0): Pair<Int, Int> = transaction(db) {
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
        repeat(completedTasks) { i ->
            PlanTaskRow.new {
                this.dailyPlan = dailyPlan; taskName = "할일 $i"; isCompleted = true
                startTime = LocalTime.of(16 + i, 0); endTime = LocalTime.of(16 + i, 30); estimatedMinutes = 30
            }
        }
        user.id.value to dailyPlan.id.value
    }

    fun storedQuestionCount(dailyPlanId: Int): Long = transaction(db) {
        DailyQuizQuestionTable.selectAll().where { DailyQuizQuestionTable.dailyPlanId eq dailyPlanId }.count()
    }

    "generateQuiz: 이미 만든 퀴즈가 있으면 새로 만들지 않고 같은 퀴즈를 돌려준다" {
        val (userId, dailyPlanId) = seedDailyPlan()
        val llm = FakeQuizLlmClient()
        val service = QuizService(llm, noNiceFetcher)

        val first = service.generateQuiz(userId, day)
        val second = service.generateQuiz(userId, day)

        llm.calls.get() shouldBe 1
        storedQuestionCount(dailyPlanId) shouldBe 3L
        second.questions.map { it.id } shouldBe first.questions.map { it.id }
        second.questions.map { q -> q.choices.map { it.id } } shouldBe first.questions.map { q -> q.choices.map { it.id } }
        service.getQuiz(userId, dailyPlanId).questions.map { it.id } shouldBe first.questions.map { it.id }
    }

    "generateQuiz: AI 가 준 풀이를 문항마다 저장하고, 제출 전 조회 응답에는 풀이를 노출하지 않는다" {
        val (userId, dailyPlanId) = seedDailyPlan()
        QuizService(FakeQuizLlmClient(), noNiceFetcher).generateQuiz(userId, day)

        transaction(db) {
            DailyQuizQuestionTable.selectAll().where { DailyQuizQuestionTable.dailyPlanId eq dailyPlanId }
                .orderBy(DailyQuizQuestionTable.id).map { it[DailyQuizQuestionTable.explanation] }
        } shouldBe (1..3).map { "풀이 $it" }
    }

    "generateQuiz: 동시에 두 번 요청돼도 한 세트만 저장되고 둘 다 같은 퀴즈를 받는다" {
        val (userId, dailyPlanId) = seedDailyPlan()
        // 두 요청이 모두 '퀴즈 없음' 을 확인한 뒤 LLM 호출 단계에서 겹치도록 응답을 늦춘다
        val llm = FakeQuizLlmClient(delayMillis = 500)
        val service = QuizService(llm, noNiceFetcher)

        val results = coroutineScope {
            listOf(async { service.generateQuiz(userId, day) }, async { service.generateQuiz(userId, day) }).awaitAll()
        }

        storedQuestionCount(dailyPlanId) shouldBe 3L
        results[1].questions.map { it.id } shouldBe results[0].questions.map { it.id }
    }

    "generateQuiz: LLM 이 빈 목록을 주면 QuizGenerationFailedException 이고 아무것도 저장하지 않는다" {
        val (userId, dailyPlanId) = seedDailyPlan()
        val service = QuizService(FakeQuizLlmClient(questionCount = 0), noNiceFetcher)

        shouldThrow<QuizValidationException.QuizGenerationFailedException> {
            service.generateQuiz(userId, day)
        }
        storedQuestionCount(dailyPlanId) shouldBe 0L
    }

    "generateQuiz: 응답 subjects 에 플랜보드 학습 범위 과목이 한 번씩 등록 순서대로 들어간다" {
        val (userId, dailyPlanId) = seedDailyPlan()
        transaction(db) {
            val board = DailyPlanRow[dailyPlanId].planBoard
            fun range(subjectName: String) {
                val subject = SubjectRow.find { SubjectTable.name eq subjectName }.firstOrNull() ?: SubjectRow.new { name = subjectName }
                val textbook = TextbookRow.new { this.subject = subject; title = "$subjectName 교과서" }
                val chapter = ChapterRow.new { this.textbook = textbook; chapterName = "$subjectName 1단원"; chapterOrder = 1 }
                PlanSubjectRow.new { planBoard = board; this.textbook = textbook; startChapter = chapter; endChapter = chapter }
            }
            range("수학"); range("과학"); range("수학") // 같은 과목 교과서가 두 권이어도 한 번만
        }

        val quiz = QuizService(FakeQuizLlmClient(), noNiceFetcher).generateQuiz(userId, day)

        quiz.subjects.map { it.subjectName } shouldBe listOf("수학", "과학")
        // 이미 만든 퀴즈를 다시 받을 때도 같은 값
        QuizService(FakeQuizLlmClient(), noNiceFetcher).generateQuiz(userId, day).subjects shouldBe quiz.subjects
    }

    // 같은 날 보드가 여러 개인 경우 (#241). 과학 교과서: 생물의 구성(식물세포) / 태양계(태양, 행성)
    fun seedTwoBoards(firstBoardHasRange: Boolean): Triple<Int, Int, Int> = transaction(db) {
        val subject = SubjectRow.find { SubjectTable.name eq "과학" }.firstOrNull() ?: SubjectRow.new { name = "과학" }
        val textbook = TextbookRow.new { this.subject = subject; title = "과학 교과서" }
        fun chapter(name: String, order: Int, parent: ChapterRow? = null) = ChapterRow.new {
            this.textbook = textbook; chapterName = name; chapterOrder = order; parentId = parent?.id?.value
        }
        val life = chapter("생물의 구성", 1)
        chapter("식물세포", 1, life)
        val solar = chapter("태양계", 2)
        chapter("태양", 1, solar)

        val user = UserRow.new { email = "boards@test.com"; username = "tester"; password = "x"; createdAt = OffsetDateTime.now() }
        fun board(title: String) = PlanBoardRow.new {
            this.user = user; this.title = title; startDate = day; endDate = day; createdAt = OffsetDateTime.now()
        }.also { DailyPlanRow.new { planBoard = it; planDate = day } }

        // 먼저 만든 보드: 생물 범위가 있는 보드, 또는 앱이 자동으로 만드는 범위 없는 기본 보드
        val first = board(if (firstBoardHasRange) "생물 수행평가" else "기본 플랜보드")
        if (firstBoardHasRange) {
            PlanSubjectRow.new { planBoard = first; this.textbook = textbook; startChapter = life; endChapter = life }
        }
        val science = board("과학 시험")
        PlanSubjectRow.new { planBoard = science; this.textbook = textbook; startChapter = solar; endChapter = solar }
        Triple(user.id.value, first.id.value, science.id.value)
    }

    "generateQuiz: planBoardId 를 주면 같은 날 다른 보드가 있어도 그 보드의 학습 범위로 만든다" {
        val (userId, bioBoardId, scienceBoardId) = seedTwoBoards(firstBoardHasRange = true)
        val llm = FakeQuizLlmClient()
        val service = QuizService(llm, noNiceFetcher)

        val science = service.generateQuiz(userId, day, scienceBoardId)
        llm.lastContext shouldContain "태양계"
        llm.lastContext shouldNotContain "식물세포"

        // 보드마다 퀴즈가 따로 만들어진다
        val bio = service.generateQuiz(userId, day, bioBoardId)
        llm.lastContext shouldContain "식물세포"
        (bio.dailyPlanId == science.dailyPlanId) shouldBe false
        llm.calls.get() shouldBe 2
    }

    "generateQuiz: planBoardId 를 생략하면 학습 범위가 없는 기본 보드가 먼저 있어도 범위 있는 보드로 만든다" {
        val (userId, _, _) = seedTwoBoards(firstBoardHasRange = false)
        val llm = FakeQuizLlmClient()

        QuizService(llm, noNiceFetcher).generateQuiz(userId, day)

        llm.lastContext shouldContain "태양계"
    }

    "generateQuiz: 남의 플랜보드나 없는 플랜보드를 주면 NoPlanForDateException" {
        val (_, _, scienceBoardId) = seedTwoBoards(firstBoardHasRange = true)
        val (otherUserId, _) = seedDailyPlan()
        val service = QuizService(FakeQuizLlmClient(), noNiceFetcher)

        shouldThrow<QuizValidationException.NoPlanForDateException> { service.generateQuiz(otherUserId, day, scienceBoardId) }
        shouldThrow<QuizValidationException.NoPlanForDateException> { service.generateQuiz(otherUserId, day, 999_999) }
    }

    "dailyQuizQuestionCount: 완료한 할일 1개 이하 3문항, 3개 이하 5문항, 4개 이상 7문항" {
        listOf(0, 1, 2, 3, 4, 10).map { dailyQuizQuestionCount(it) } shouldBe listOf(3, 3, 5, 5, 7, 7)
    }

    "generateQuiz: 그날 완료한 할일 수에 맞춘 문항 수를 AI 에 요청하고 그만큼 저장한다" {
        val (userId, dailyPlanId) = seedDailyPlan(completedTasks = 4)
        val llm = FakeQuizLlmClient()

        val quiz = QuizService(llm, noNiceFetcher).generateQuiz(userId, day)

        llm.lastCount shouldBe 7
        quiz.questions.size shouldBe 7
        storedQuestionCount(dailyPlanId) shouldBe 7L
    }

    "generateQuiz: AI 가 요청보다 많이 주면 정한 문항 수까지만 저장한다" {
        val (userId, dailyPlanId) = seedDailyPlan(completedTasks = 2)

        val quiz = QuizService(FakeQuizLlmClient(questionCount = 8), noNiceFetcher).generateQuiz(userId, day)

        quiz.questions.size shouldBe 5
        storedQuestionCount(dailyPlanId) shouldBe 5L
    }

    "generateQuiz: 해당 날짜에 계획이 없으면 NoPlanForDateException" {
        val (userId, _) = seedDailyPlan()

        shouldThrow<QuizValidationException.NoPlanForDateException> {
            QuizService(FakeQuizLlmClient(), noNiceFetcher).generateQuiz(userId, day.plusDays(1))
        }
    }
})

private class FakeQuizLlmClient(
    private val questionCount: Int? = null, // null 이면 요청받은 count 만큼 만든다
    private val delayMillis: Long = 0
) : QuizLlmClient(dummyAppConfig()) {
    val calls = AtomicInteger(0)
    @Volatile var lastContext: String = ""
    @Volatile var lastCount: Int = 0

    override suspend fun generateQuestions(context: String, count: Int): List<GeneratedQuestion> {
        val n = calls.incrementAndGet()
        lastContext = context
        lastCount = count
        if (delayMillis > 0) delay(delayMillis)
        return (1..(questionCount ?: count)).map { i ->
            GeneratedQuestion(
                questionText = "문제 $i (호출 $n)",
                choices = listOf("A", "B", "C", "D"),
                correctIndex = 0,
                explanation = "풀이 $i"
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
