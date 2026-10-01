import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.common.config.EnvironmentMode
import com.github.nepyh.rooter.module.planboard.guessTaskSubject
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
import com.github.nepyh.rooter.module.taskquiz.GeneratedTaskQuizQuestion
import com.github.nepyh.rooter.module.taskquiz.TaskQuizLlmClient
import com.github.nepyh.rooter.module.taskquiz.TaskQuizService
import com.github.nepyh.rooter.module.taskquiz.exception.TaskQuizValidationException
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizAttemptTable
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizChoiceTable
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizQuestionRow
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizQuestionTable
import com.github.nepyh.rooter.module.user.model.StudentProfileRow
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * 태스크 완료 퀴즈 생성 통합 테스트 (로컬 PostgreSQL 필요). LLM 은 [FakeTaskQuizLlmClient] 로 고정 응답을 준다.
 *
 * 접속 정보는 환경변수로 오버라이드 가능: TEST_JDBC_URL / TEST_DB_USER / TEST_DB_PASSWORD
 */
class TaskQuizServiceTest : StringSpec({

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
                "task_quiz_choices", "task_quiz_questions", "task_quiz_attempts", "plan_tasks", "plan_subjects",
                "daily_plans", "plan_boards", "chapters", "textbooks", "subjects", "student_profiles", "users"
            ).forEach { exec("DROP TABLE IF EXISTS $it CASCADE") }
            SchemaUtils.create(
                UserTable, StudentProfileTable, SubjectTable, TextbookTable, ChapterTable, PlanBoardTable, PlanSubjectTable,
                DailyPlanTable, PlanTaskTable, TaskQuizAttemptTable, TaskQuizQuestionTable, TaskQuizChoiceTable
            )
        }
    }

    beforeEach {
        transaction(db) {
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

    var lastUserId = -1 // seedTask 가 만든 유저 id. answerQuestion/submitQuiz 테스트에서 소유자로 씀

    /** 사용자가 직접 추가한 태스크 하나를 만든다. withScope 면 플랜보드에 과학 단원 범위를, grade 가 있으면 학생 프로필을 붙인다 */
    fun seedTask(taskName: String, grade: Int?, withScope: Boolean): Int = transaction(db) {
        val user = UserRow.new { email = "tq@test.com"; username = "t"; password = "x"; createdAt = OffsetDateTime.now() }
        lastUserId = user.id.value
        if (grade != null) {
            StudentProfileRow.new { this.user = user; schoolId = "B107132131"; this.grade = grade; classNumber = 1 }
        }
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
        PlanTaskRow.new {
            this.dailyPlan = dailyPlan; this.taskName = taskName
            startTime = LocalTime.of(16, 0); endTime = LocalTime.of(16, 30); estimatedMinutes = 30
        }.id.value
    }

    "사용자가 추가한 태스크도 학년과 플랜보드 학습 범위를 함께 넘겨 퀴즈를 만든다" {
        val taskId = seedTask("과학 문제집 30쪽 풀기", grade = 2, withScope = true)
        val llm = FakeTaskQuizLlmClient()

        TaskQuizService(llm).generateAttempt(taskId, 1, "과학 문제집 30쪽 풀기")

        llm.lastTaskName shouldBe "과학 문제집 30쪽 풀기"
        llm.lastGradeLabel shouldBe "중학교 2학년"
        llm.lastStudyScope shouldBe "과학: 지구계, 지권의 구조"
        transaction(db) { TaskQuizQuestionTable.selectAll().count() } shouldBe 5L
    }

    "학습 범위도 학생 프로필도 없으면 '지정 안 됨' 과 학년 정보 없음으로 넘긴다" {
        val taskId = seedTask("영어 단어 30개 외우기", grade = null, withScope = false)
        val llm = FakeTaskQuizLlmClient()

        TaskQuizService(llm).generateAttempt(taskId, 1, "영어 단어 30개 외우기")

        llm.lastGradeLabel shouldBe "중학생(학년 정보 없음)"
        llm.lastStudyScope shouldBe "지정 안 됨"
        transaction(db) { TaskQuizAttemptTable.selectAll().count() } shouldBe 1L
    }

    "getCurrentQuiz: 플랜보드 학습 범위 과목(subjects)과 태스크 이름으로 추정한 과목(subject)을 함께 준다" {
        val taskId = seedTask("과학 문제집 30쪽 풀기", grade = 2, withScope = true)
        val service = TaskQuizService(FakeTaskQuizLlmClient())
        service.generateAttempt(taskId, 1, "과학 문제집 30쪽 풀기")

        val quiz = service.getCurrentQuiz(lastUserId, taskId)

        quiz.subjects.map { it.subjectName } shouldBe listOf("과학")
        quiz.subject?.subjectName shouldBe "과학"
    }

    "getCurrentQuiz: 학습 범위가 없는 보드의 태스크면 subject 는 null, subjects 는 빈 목록" {
        val taskId = seedTask("영어 단어 30개 외우기", grade = null, withScope = false)
        val service = TaskQuizService(FakeTaskQuizLlmClient())
        service.generateAttempt(taskId, 1, "영어 단어 30개 외우기")

        val quiz = service.getCurrentQuiz(lastUserId, taskId)

        quiz.subject shouldBe null
        quiz.subjects shouldBe emptyList()
    }

    "guessTaskSubject: 이름에 과목명이 하나면 그 과목, 없으면 보드 과목이 하나일 때만 그 과목, 애매하면 null" {
        val math = 1 to "수학"
        val science = 2 to "과학"
        guessTaskSubject("수학 개념 정리", listOf(math, science)) shouldBe math
        guessTaskSubject("문제집 30쪽", listOf(science)) shouldBe science
        guessTaskSubject("문제집 30쪽", listOf(math, science)) shouldBe null
        guessTaskSubject("수학·과학 복습", listOf(math, science)) shouldBe null
        guessTaskSubject("아무거나", emptyList()) shouldBe null
    }

    fun attemptCount(taskId: Int): Long = transaction(db) {
        TaskQuizAttemptTable.selectAll().where { TaskQuizAttemptTable.planTaskId eq taskId }.count()
    }

    suspend fun failAll(service: TaskQuizService, userId: Int, taskId: Int) {
        service.getCurrentQuiz(userId, taskId).questions.forEach { service.answerQuestion(userId, taskId, it.id, it.choices[1].id) }
        service.submitQuiz(userId, taskId)
    }

    "startQuiz: 퀴즈가 없으면 종료 시각 전이어도 바로 1차 퀴즈를 만들고, 다시 눌러도 같은 퀴즈를 준다" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        val userId = lastUserId
        val service = TaskQuizService(FakeTaskQuizLlmClient())

        val first = service.startQuiz(userId, taskId)
        val second = service.startQuiz(userId, taskId)

        first.attemptNumber shouldBe 1
        first.questions.size shouldBe 5
        second.questions.map { it.id } shouldBe first.questions.map { it.id }
        attemptCount(taskId) shouldBe 1L
    }

    "startQuiz: 떨어지면 10분 대기 전엔 RetryNotReadyException, 지나면 2차를 만들고, 3번 다 떨어지면 NoMoreAttemptsException" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        val userId = lastUserId
        val now = TaskQuizService(FakeTaskQuizLlmClient())
        val later = { minutes: Long -> TaskQuizService(FakeTaskQuizLlmClient(), Clock.offset(Clock.systemUTC(), Duration.ofMinutes(minutes))) }

        now.startQuiz(userId, taskId)
        failAll(now, userId, taskId)
        shouldThrow<TaskQuizValidationException.RetryNotReadyException> { now.startQuiz(userId, taskId) }

        later(11).startQuiz(userId, taskId).attemptNumber shouldBe 2
        failAll(now, userId, taskId)
        later(11).startQuiz(userId, taskId).attemptNumber shouldBe 3
        failAll(now, userId, taskId)
        shouldThrow<TaskQuizValidationException.NoMoreAttemptsException> { later(60).startQuiz(userId, taskId) }
    }

    "startQuiz: 통과한 태스크면 AlreadyPassedException 이고 태스크는 완료 상태다" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        val userId = lastUserId
        val service = TaskQuizService(FakeTaskQuizLlmClient())
        service.startQuiz(userId, taskId).questions.forEach { service.answerQuestion(userId, taskId, it.id, it.choices[0].id) }
        service.submitQuiz(userId, taskId).passed shouldBe true

        shouldThrow<TaskQuizValidationException.AlreadyPassedException> { service.startQuiz(userId, taskId) }
        transaction(db) { PlanTaskRow[taskId].isCompleted } shouldBe true
    }

    "completeWithQuiz: 통과 뒤 완료를 취소했다가 다시 누르면 퀴즈 없이 바로 완료, 아직이면 풀 퀴즈를 준다" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        val userId = lastUserId
        val service = TaskQuizService(FakeTaskQuizLlmClient())

        val quiz = service.completeWithQuiz(userId, taskId)!!
        quiz.questions.forEach { service.answerQuestion(userId, taskId, it.id, it.choices[0].id) }
        service.submitQuiz(userId, taskId).passed shouldBe true
        transaction(db) { PlanTaskRow[taskId].isCompleted = false } // 완료 취소

        service.completeWithQuiz(userId, taskId) shouldBe null
        transaction(db) { PlanTaskRow[taskId].isCompleted } shouldBe true
        attemptCount(taskId) shouldBe 1L
    }

    "startQuiz: AI 가 퀴즈를 못 만들면 GenerationFailedException 이고 아무것도 저장하지 않는다" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        val service = TaskQuizService(FakeTaskQuizLlmClient(emptyList()))

        shouldThrow<TaskQuizValidationException.GenerationFailedException> { service.startQuiz(lastUserId, taskId) }
        attemptCount(taskId) shouldBe 0L
    }

    "generateAttempt: 스케줄러와 완료 버튼이 같은 차수를 겹쳐 만들려 해도 한 번만 저장된다" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        val service = TaskQuizService(FakeTaskQuizLlmClient())

        coroutineScope {
            (1..3).map { async { service.generateAttempt(taskId, 1, "수학 문제집 풀기") } }.awaitAll()
        } shouldBe listOf(true, true, true)
        attemptCount(taskId) shouldBe 1L
    }

    "answerQuestion: 정답을 고르면 isCorrect=true 를 돌려주고 DB 에 저장한다" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        val userId = lastUserId
        val service = TaskQuizService(FakeTaskQuizLlmClient())
        service.generateAttempt(taskId, 1, "수학 문제집 풀기")
        val firstQuestion = service.getCurrentQuiz(userId, taskId).questions.first()
        val correctChoiceId = firstQuestion.choices[0].id // FakeTaskQuizLlmClient 는 correct_index=0

        val response = service.answerQuestion(userId, taskId, firstQuestion.id, correctChoiceId)

        response.isCorrect shouldBe true
        response.correctChoiceId shouldBe correctChoiceId
        response.reason shouldBe null
        response.explanation shouldBe "자세한 풀이 1" // 맞아도 자세한 풀이는 준다
        transaction(db) { TaskQuizQuestionRow[firstQuestion.id].selectedChoiceId } shouldBe correctChoiceId
    }

    "answerQuestion: 오답을 고르면 isCorrect=false 와 실제 정답을 함께 돌려준다" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        val userId = lastUserId
        val service = TaskQuizService(FakeTaskQuizLlmClient())
        service.generateAttempt(taskId, 1, "수학 문제집 풀기")
        val firstQuestion = service.getCurrentQuiz(userId, taskId).questions.first()
        val wrongChoiceId = firstQuestion.choices[1].id

        val response = service.answerQuestion(userId, taskId, firstQuestion.id, wrongChoiceId)

        response.isCorrect shouldBe false
        response.correctChoiceId shouldBe firstQuestion.choices[0].id
        response.reason shouldBe "B 가 틀린 이유" // 고른 오답 보기의 짧은 이유
        response.explanation shouldBe "자세한 풀이 1" // 정답 보기의 자세한 풀이
    }

    "answerQuestion: 이미 답한 문제에 다시 답하면 AlreadyAnsweredException — 정답을 본 뒤 답 변경을 막는다" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        val userId = lastUserId
        val service = TaskQuizService(FakeTaskQuizLlmClient())
        service.generateAttempt(taskId, 1, "수학 문제집 풀기")
        val firstQuestion = service.getCurrentQuiz(userId, taskId).questions.first()
        service.answerQuestion(userId, taskId, firstQuestion.id, firstQuestion.choices[1].id)

        shouldThrow<TaskQuizValidationException.AlreadyAnsweredException> {
            service.answerQuestion(userId, taskId, firstQuestion.id, firstQuestion.choices[0].id)
        }
    }

    "answerQuestion: 다른 문제의 보기 id 를 보내면 InvalidAnswerException — 정답 보기를 여러 문제에 재사용하는 걸 막는다" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        val userId = lastUserId
        val service = TaskQuizService(FakeTaskQuizLlmClient())
        service.generateAttempt(taskId, 1, "수학 문제집 풀기")
        val questions = service.getCurrentQuiz(userId, taskId).questions
        val choiceIdFromAnotherQuestion = questions[1].choices[0].id

        shouldThrow<TaskQuizValidationException.InvalidAnswerException> {
            service.answerQuestion(userId, taskId, questions[0].id, choiceIdFromAnotherQuestion)
        }
    }

    "submitQuiz: 아직 답하지 않은 문제가 있으면 IncompleteAnswersException" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        val userId = lastUserId
        val service = TaskQuizService(FakeTaskQuizLlmClient())
        service.generateAttempt(taskId, 1, "수학 문제집 풀기")
        val questions = service.getCurrentQuiz(userId, taskId).questions
        questions.take(4).forEach { service.answerQuestion(userId, taskId, it.id, it.choices[0].id) }

        shouldThrow<TaskQuizValidationException.IncompleteAnswersException> {
            service.submitQuiz(userId, taskId)
        }
    }

    "submitQuiz: 모든 문제에 정답을 저장해두면 DB 에 저장된 답으로만 채점해 통과 처리한다" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        val userId = lastUserId
        val service = TaskQuizService(FakeTaskQuizLlmClient())
        service.generateAttempt(taskId, 1, "수학 문제집 풀기")
        val questions = service.getCurrentQuiz(userId, taskId).questions
        questions.forEach { service.answerQuestion(userId, taskId, it.id, it.choices[0].id) } // 전부 정답(index 0)

        val result = service.submitQuiz(userId, taskId)

        result.correctCount shouldBe 5
        result.passed shouldBe true
        result.results.all { it.isCorrect } shouldBe true
        transaction(db) { PlanTaskRow[taskId].isCompleted } shouldBe true
    }

    "submitQuiz: 이미 채점된 퀴즈를 다시 제출하면 AlreadySubmittedException" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        val userId = lastUserId
        val service = TaskQuizService(FakeTaskQuizLlmClient())
        service.generateAttempt(taskId, 1, "수학 문제집 풀기")
        val questions = service.getCurrentQuiz(userId, taskId).questions
        questions.forEach { service.answerQuestion(userId, taskId, it.id, it.choices[0].id) }
        service.submitQuiz(userId, taskId)

        shouldThrow<TaskQuizValidationException.AlreadySubmittedException> {
            service.submitQuiz(userId, taskId)
        }
    }
    "submitQuiz: results 의 explanation 은 짧은 이유가 아니라 정답 보기의 자세한 풀이다" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        val userId = lastUserId
        val service = TaskQuizService(FakeTaskQuizLlmClient())
        service.generateAttempt(taskId, 1, "수학 문제집 풀기")
        val questions = service.getCurrentQuiz(userId, taskId).questions
        questions.forEach { service.answerQuestion(userId, taskId, it.id, it.choices[1].id) } // 전부 오답

        val response = service.submitQuiz(userId, taskId)

        response.correctCount shouldBe 0
        response.results.map { it.explanation } shouldBe (1..5).map { "자세한 풀이 $it" }
    }

    "getCurrentQuiz: 이미 답한 문제는 selectedChoiceId 가 채워져 앱을 다시 켜도 이어서 풀 수 있다" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        val userId = lastUserId
        val service = TaskQuizService(FakeTaskQuizLlmClient())
        service.generateAttempt(taskId, 1, "수학 문제집 풀기")
        val first = service.getCurrentQuiz(userId, taskId).questions.first()
        service.answerQuestion(userId, taskId, first.id, first.choices[2].id)

        val questions = service.getCurrentQuiz(userId, taskId).questions

        questions.first().selectedChoiceId shouldBe first.choices[2].id
        questions.drop(1).map { it.selectedChoiceId } shouldBe List(4) { null }
    }

    "generateAttempt: 정답 번호가 보기 범위를 벗어난 문제는 저장하지 않는다" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        val llm = FakeTaskQuizLlmClient(
            (1..5).map { GeneratedTaskQuizQuestion("문제 $it", listOf("A", "B", "C", "D"), if (it == 3) 4 else 0, "풀이") }
        )

        TaskQuizService(llm).generateAttempt(taskId, 1, "수학 문제집 풀기")

        transaction(db) { TaskQuizQuestionTable.selectAll().count() } shouldBe 4L
        transaction(db) { TaskQuizAttemptTable.selectAll().single()[TaskQuizAttemptTable.totalCount] } shouldBe 4
    }
})

private class FakeTaskQuizLlmClient(
    private val questions: List<GeneratedTaskQuizQuestion> = (1..5).map {
        GeneratedTaskQuizQuestion("문제 $it", listOf("A", "B", "C", "D"), 0, "자세한 풀이 $it", listOf("", "B 가 틀린 이유", "C 가 틀린 이유", "D 가 틀린 이유"))
    }
) : TaskQuizLlmClient(dummyAppConfig()) {
    var lastTaskName: String? = null
    var lastGradeLabel: String? = null
    var lastStudyScope: String? = null

    override suspend fun generateQuestions(taskName: String, gradeLabel: String, studyScope: String): List<GeneratedTaskQuizQuestion> {
        lastTaskName = taskName
        lastGradeLabel = gradeLabel
        lastStudyScope = studyScope
        return questions
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
