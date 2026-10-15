import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.common.config.EnvironmentMode
import com.github.nepyh.rooter.module.planboard.model.ChapterRow
import com.github.nepyh.rooter.module.planboard.model.ChapterTable
import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectRow
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.planboard.model.SubjectRow
import com.github.nepyh.rooter.module.planboard.model.SubjectTable
import com.github.nepyh.rooter.module.planboard.model.TextbookRow
import com.github.nepyh.rooter.module.planboard.model.TextbookTable
import com.github.nepyh.rooter.module.review.model.ReviewChoiceTable
import com.github.nepyh.rooter.module.review.model.ReviewQuestionTable
import com.github.nepyh.rooter.module.taskquiz.GeneratedTaskQuizQuestion
import com.github.nepyh.rooter.module.taskquiz.QuizHistoryStatus
import com.github.nepyh.rooter.module.taskquiz.TaskQuizHistoryService
import com.github.nepyh.rooter.module.taskquiz.TaskQuizLlmClient
import com.github.nepyh.rooter.module.taskquiz.TaskQuizService
import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizQuestionResponse
import com.github.nepyh.rooter.module.taskquiz.exception.QuizHistoryNotFoundException
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizAttemptRow
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizAttemptTable
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizChoiceTable
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizQuestionTable
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * 퀴즈 히스토리 목록 통합 테스트 (로컬 PostgreSQL 필요). LLM 은 [FakeTaskQuizHistoryLlmClient] 로 고정 응답을 준다.
 *
 * 접속 정보는 환경변수로 오버라이드 가능: TEST_JDBC_URL / TEST_DB_USER / TEST_DB_PASSWORD
 */
class TaskQuizHistoryServiceTest : StringSpec({

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
                "review_choices", "review_questions", "task_quiz_choices", "task_quiz_questions", "task_quiz_attempts", "plan_tasks", "plan_subjects",
                "daily_plans", "plan_boards", "chapters", "textbooks", "subjects", "student_profiles", "users"
            ).forEach { exec("DROP TABLE IF EXISTS $it CASCADE") }
            SchemaUtils.create(
                UserTable, StudentProfileTable, SubjectTable, TextbookTable, ChapterTable, PlanBoardTable, PlanSubjectTable,
                DailyPlanTable, PlanTaskTable, TaskQuizAttemptTable, TaskQuizQuestionTable, TaskQuizChoiceTable,
                ReviewQuestionTable, ReviewChoiceTable
            )
        }
    }

    beforeEach {
        transaction(db) {
            ReviewChoiceTable.deleteAll()
            ReviewQuestionTable.deleteAll()
            TaskQuizChoiceTable.deleteAll()
            TaskQuizQuestionTable.deleteAll()
            TaskQuizAttemptTable.deleteAll()
            PlanTaskTable.deleteAll()
            PlanSubjectTable.deleteAll()
            DailyPlanTable.deleteAll()
            PlanBoardTable.deleteAll()
            ChapterTable.deleteAll()
            TextbookTable.deleteAll()
            SubjectTable.deleteAll()
            StudentProfileTable.deleteAll()
            UserTable.deleteAll()
        }
    }

    val day = LocalDate.of(2026, 9, 29)

    /** 같은 일일계획에 할일을 하나 더 만든다 (한 유저에 여러 퀴즈를 만들 때) */
    fun newTask(dailyPlanId: Int, taskName: String, minutes: Int = 45): Int = transaction(db) {
        PlanTaskRow.new {
            this.dailyPlan = DailyPlanRow[dailyPlanId]
            this.taskName = taskName
            startTime = LocalTime.of(16, 0)
            endTime = LocalTime.of(16, 0).plusMinutes(minutes.toLong())
            estimatedMinutes = minutes
        }.id.value
    }

    /** 유저 + 플랜보드 + 일일계획 + 할일 하나를 만든다. withScope 면 플랜보드에 과학 단원 범위를 붙인다 */
    fun seedTask(taskName: String, withScope: Boolean, minutes: Int = 45): Triple<Int, Int, Int> = transaction(db) {
        // 한 테스트에서 여러 번 불러도 이메일(유니크)이 겹치지 않게 한다
        val user = UserRow.new { email = "qh${UserRow.count()}@test.com"; username = "t"; password = "x"; createdAt = OffsetDateTime.now() }
        val board = PlanBoardRow.new {
            this.user = user; title = "보드"; startDate = day; endDate = day; createdAt = OffsetDateTime.now()
        }
        if (withScope) {
            val science = SubjectRow.new { name = "과학" }
            val textbook = TextbookRow.new { subject = science; title = "과학 1" }
            val unit = ChapterRow.new { this.textbook = textbook; chapterName = "Ⅰ 지권의 변화"; chapterOrder = 1 }
            val first = ChapterRow.new { this.textbook = textbook; parentId = unit.id.value; chapterName = "지구계"; chapterOrder = 1 }
            val second = ChapterRow.new { this.textbook = textbook; parentId = unit.id.value; chapterName = "지권의 구조"; chapterOrder = 2 }
            PlanSubjectRow.new { planBoard = board; this.textbook = textbook; startChapter = first; endChapter = second }
        }
        val dailyPlan = DailyPlanRow.new { planBoard = board; planDate = day }
        val taskId = newTask(dailyPlan.id.value, taskName, minutes)
        Triple(user.id.value, dailyPlan.id.value, taskId)
    }

    /** 1차 퀴즈를 만든다 (LLM 은 고정 응답) */
    suspend fun makeQuiz(taskId: Int, taskName: String, llm: FakeTaskQuizHistoryLlmClient = FakeTaskQuizHistoryLlmClient()): TaskQuizService {
        val service = TaskQuizService(llm)
        service.generateAttempt(taskId, 1, taskName)
        return service
    }

    "목록: 제출이 끝난 차수는 done 으로 나오고 제목·과목·시간 3종·점수를 담는다" {
        val (userId, _, taskId) = seedTask("과학 문제집 30쪽 풀기", withScope = true)
        val service = makeQuiz(taskId, "과학 문제집 30쪽 풀기")
        service.getCurrentQuiz(userId, taskId).questions.forEach { question ->
            service.answerQuestion(userId, taskId, question.id, question.choice("A").id)
        }
        service.submitQuiz(userId, taskId)

        val done = TaskQuizHistoryService().list(userId, QuizHistoryStatus.DONE)
        val item = done.quizzes.single()

        item.type shouldBe "TASK"
        item.status shouldBe "DONE"
        item.title shouldBe "과학 문제집 30쪽 풀기"
        item.attemptNumber shouldBe 1
        item.subjectName shouldBe "과학"                 // 문항 단원(지구계·지권의 구조) -> 교과서 -> 과목
        item.scheduledAt shouldBe "2026-09-29T16:45"     // 할일이 끝나는 시각(퀴즈가 열리는 시각)
        item.totalCount shouldBe 5
        item.correctCount shouldBe 5
        item.passed shouldBe true
        item.completedAt.shouldNotBeNull()               // 제출 시각이 저장된다
        (item.createdAt.isNotBlank()) shouldBe true
        done.totalCount shouldBe 1

        TaskQuizHistoryService().list(userId, QuizHistoryStatus.TODO).quizzes shouldBe emptyList()
    }

    "목록: 아직 채점 전이면 todo 로 나오고 완료 시간·정답 수가 null" {
        val (userId, _, taskId) = seedTask("수학 문제집 풀기", withScope = false)
        makeQuiz(taskId, "수학 문제집 풀기")

        val item = TaskQuizHistoryService().list(userId, QuizHistoryStatus.TODO).quizzes.single()

        item.status shouldBe "TODO"
        item.completedAt shouldBe null
        item.correctCount shouldBe null
        item.passed shouldBe null
        item.totalCount shouldBe 5
        item.subjectId shouldBe null   // 학습 범위도 단원 기록도 없어 과목을 못 찾는다

        TaskQuizHistoryService().list(userId, QuizHistoryStatus.DONE).quizzes shouldBe emptyList()
    }

    "목록: 남의 퀴즈는 나오지 않는다" {
        val (me, _, myTask) = seedTask("수학 문제집 풀기", withScope = false)
        val (_, _, otherTask) = seedTask("영어 단어 30개 외우기", withScope = false)
        makeQuiz(myTask, "수학 문제집 풀기")
        makeQuiz(otherTask, "영어 단어 30개 외우기")

        val mine = TaskQuizHistoryService().list(me, null)

        mine.quizzes.map { it.title } shouldBe listOf("수학 문제집 풀기")
        mine.totalCount shouldBe 1
    }

    "목록: 커서로 다음 페이지를 받는다 (최신 차수가 먼저)" {
        val (userId, dailyPlanId, firstTask) = seedTask("수학 문제집 풀기", withScope = false)
        val secondTask = newTask(dailyPlanId, "영어 단어 30개 외우기")
        makeQuiz(firstTask, "수학 문제집 풀기")
        makeQuiz(secondTask, "영어 단어 30개 외우기")

        val history = TaskQuizHistoryService()
        val firstPage = history.list(userId, null, cursor = null, limit = 1)
        firstPage.quizzes.map { it.title } shouldBe listOf("영어 단어 30개 외우기")
        firstPage.totalCount shouldBe 2
        val cursor = firstPage.nextCursor.shouldNotBeNull()

        val secondPage = history.list(userId, null, cursor = cursor, limit = 1)
        secondPage.quizzes.map { it.title } shouldBe listOf("수학 문제집 풀기")
        secondPage.nextCursor shouldBe null
    }

    "목록: 단원 기록이 없는 퀴즈는 할일 이름으로 과목을 추정한다" {
        val (userId, _, taskId) = seedTask("과학 문제집 30쪽 풀기", withScope = true)
        // AI 가 붙인 단원이 학습 범위 밖이면 저장은 null 이 된다 (matchQuizChapter)
        val llm = FakeTaskQuizHistoryLlmClient(
            listOf(GeneratedTaskQuizQuestion("문제 1", listOf("A", "B", "C", "D"), 0, "풀이 1", listOf("", "", "", ""), chapter_name = "없는 단원"))
        )
        makeQuiz(taskId, "과학 문제집 30쪽 풀기", llm)

        val item = TaskQuizHistoryService().list(userId, null).quizzes.single()

        item.subjectName shouldBe "과학"   // 폴백: 할일 이름에 과목명이 들어 있다
    }

    "목록: 제출 시각이 없는 예전 차수는 done 이지만 완료 시간은 null" {
        val (userId, _, taskId) = seedTask("수학 문제집 풀기", withScope = false)
        transaction(db) {
            TaskQuizAttemptRow.new {
                this.planTaskId = EntityID(taskId, PlanTaskTable)
                attemptNumber = 1
                totalCount = 5
                correctCount = 3
                passed = false
                createdAt = OffsetDateTime.now()
                // submitted_at 컬럼 추가 전에 푼 퀴즈라 제출 시각이 없다
            }
        }

        val item = TaskQuizHistoryService().list(userId, QuizHistoryStatus.DONE).quizzes.single()

        item.status shouldBe "DONE"
        item.completedAt shouldBe null
        item.passed shouldBe false
    }
    "상세: 제출이 끝난 차수는 내가 고른 답·정답·해설(문항 풀이, 보기별 풀이)을 준다" {
        val (userId, _, taskId) = seedTask("과학 문제집 30쪽 풀기", withScope = true)
        val service = makeQuiz(taskId, "과학 문제집 30쪽 풀기")
        val questions = service.getCurrentQuiz(userId, taskId).questions
        service.answerQuestion(userId, taskId, questions[0].id, questions[0].choice("A").id) // 정답
        service.answerQuestion(userId, taskId, questions[1].id, questions[1].choice("B").id) // 오답
        questions.drop(2).forEach { service.answerQuestion(userId, taskId, it.id, it.choice("A").id) }
        service.submitQuiz(userId, taskId)

        val quizId = TaskQuizHistoryService().list(userId, null).quizzes.single().quizId
        val detail = TaskQuizHistoryService().detail(userId, quizId)

        detail.status shouldBe "DONE"
        detail.title shouldBe "과학 문제집 30쪽 풀기"
        detail.subjectName shouldBe "과학"
        detail.attemptNumber shouldBe 1
        detail.totalCount shouldBe 5
        detail.passCount shouldBe 4       // 70점 이상
        detail.correctCount shouldBe 4
        detail.passed shouldBe true
        detail.completedAt.shouldNotBeNull()

        val first = detail.questions.first()
        first.isCorrect shouldBe true
        first.explanation shouldBe "자세한 풀이 1"    // 문항 풀이 = 정답 보기의 풀이
        first.choices.first { it.choiceText == "A" }.selected shouldBe true
        first.choices.first { it.choiceText == "B" }.selected shouldBe false
        first.choices.all { it.isCorrect != null } shouldBe true

        val second = detail.questions[1]
        second.isCorrect shouldBe false
        second.choices.first { it.choiceText == "A" }.isCorrect shouldBe true   // 실제 정답 보기
        second.choices.first { it.choiceText == "B" }.isCorrect shouldBe false  // 내가 고른 오답
        second.choices.first { it.choiceText == "B" }.explanation shouldBe "B 가 틀린 이유"
    }

    "상세: 미응시 차수는 정답·해설을 숨긴다 (퀴즈를 풀기 전에 답이 새지 않게)" {
        val (userId, _, taskId) = seedTask("수학 문제집 풀기", withScope = false)
        makeQuiz(taskId, "수학 문제집 풀기") // 답을 하지 않는다

        val quizId = TaskQuizHistoryService().list(userId, QuizHistoryStatus.TODO).quizzes.single().quizId
        val detail = TaskQuizHistoryService().detail(userId, quizId)

        detail.status shouldBe "TODO"
        detail.correctCount shouldBe null
        detail.completedAt shouldBe null
        detail.questions.size shouldBe 5
        detail.questions.all { it.isCorrect == null } shouldBe true
        detail.questions.all { it.explanation == null } shouldBe true
        detail.questions.all { question -> question.choices.all { it.isCorrect == null } } shouldBe true
        detail.questions.all { question -> question.choices.all { it.explanation == null } } shouldBe true
    }

    "상세: 남의 차수는 조회되지 않는다" {
        val (me, _, myTask) = seedTask("수학 문제집 풀기", withScope = false)
        val (other, _, otherTask) = seedTask("영어 단어 30개 외우기", withScope = false)
        makeQuiz(myTask, "수학 문제집 풀기")
        makeQuiz(otherTask, "영어 단어 30개 외우기")

        val othersQuizId = TaskQuizHistoryService().list(other, null).quizzes.single().quizId

        shouldThrow<QuizHistoryNotFoundException> {
            TaskQuizHistoryService().detail(me, othersQuizId)
        }
    }
})

/** 보기 순서가 섞여 저장되므로 테스트는 위치가 아니라 보기 글자로 고른다 */
private fun TaskQuizQuestionResponse.choice(text: String) = choices.first { it.choiceText == text }

private class FakeTaskQuizHistoryLlmClient(
    private val questions: List<GeneratedTaskQuizQuestion>? = null // null 이면 요청받은 count 만큼 만든다
) : TaskQuizLlmClient(dummyAppConfig()) {
    override suspend fun generateQuestions(taskName: String, gradeLabel: String, studyScope: String, count: Int): List<GeneratedTaskQuizQuestion> =
        questions ?: (1..count).map {
            GeneratedTaskQuizQuestion("문제 $it", listOf("A", "B", "C", "D"), 0, "자세한 풀이 $it", listOf("", "B 가 틀린 이유", "C 가 틀린 이유", "D 가 틀린 이유"))
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
