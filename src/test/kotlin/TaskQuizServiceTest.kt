import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.common.quizQuestionCount
import com.github.nepyh.rooter.common.config.EnvironmentMode
import com.github.nepyh.rooter.module.planboard.PlanTaskService
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskUpdateRequest
import com.github.nepyh.rooter.module.planboard.exception.PlanTaskNotFoundException
import com.github.nepyh.rooter.module.planboard.exception.PlanTaskValidationException
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
import com.github.nepyh.rooter.module.planboard.model.PlanTaskStatus
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.planboard.model.SubjectRow
import com.github.nepyh.rooter.module.planboard.model.SubjectTable
import com.github.nepyh.rooter.module.planboard.model.TextbookRow
import com.github.nepyh.rooter.module.planboard.model.TextbookTable
import com.github.nepyh.rooter.module.review.ReviewService
import com.github.nepyh.rooter.module.review.model.ReviewChoiceTable
import com.github.nepyh.rooter.module.review.model.ReviewQuestionTable
import com.github.nepyh.rooter.module.taskquiz.GeneratedTaskQuizQuestion
import com.github.nepyh.rooter.module.taskquiz.MAX_ATTEMPTS
import com.github.nepyh.rooter.module.taskquiz.TaskQuizLlmClient
import com.github.nepyh.rooter.module.taskquiz.TaskQuizService
import com.github.nepyh.rooter.module.taskquiz.TaskQuizTriggerJob
import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizQuestionResponse
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizChoiceRow
import com.github.nepyh.rooter.module.taskquiz.taskQuizPassCount
import com.github.nepyh.rooter.module.taskquiz.exception.TaskQuizNotFoundException
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
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import kotlin.random.Random

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
                "review_choices", "review_questions", "task_quiz_choices", "task_quiz_questions", "task_quiz_attempts", "plan_tasks", "plan_subjects",
                "daily_plans", "plan_boards", "chapters", "textbooks", "subjects", "student_profiles", "users"
            ).forEach { exec("DROP TABLE IF EXISTS $it CASCADE") }
            SchemaUtils.create(
                UserTable, StudentProfileTable, SubjectTable, TextbookTable, ChapterTable, PlanBoardTable, PlanSubjectTable,
                DailyPlanTable, PlanTaskTable, TaskQuizAttemptTable, TaskQuizQuestionTable, TaskQuizChoiceTable,
                // 틀린 문제를 복습 목록으로 복사한다 (users 를 지우면 cascade 로 함께 지워짐)
                ReviewQuestionTable, ReviewChoiceTable
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
    fun seedTask(taskName: String, grade: Int?, withScope: Boolean, minutes: Int = 45): Int = transaction(db) {
        // 한 테스트에서 여러 번 불러도 이메일(유니크)이 겹치지 않게 한다
        val user = UserRow.new { email = "tq${UserRow.count()}@test.com"; username = "t"; password = "x"; createdAt = OffsetDateTime.now() }
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
            startTime = LocalTime.of(16, 0); endTime = LocalTime.of(16, 0).plusMinutes(minutes.toLong()); estimatedMinutes = minutes
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

    "문항마다 학습 범위 단원을 저장하고(범위 밖·빈 값은 null), 틀린 문제 복사분에도 단원이 따라간다" {
        val taskId = seedTask("과학 문제집 30쪽 풀기", grade = 2, withScope = true)
        val userId = lastUserId
        fun q(n: Int, chapter: String) = GeneratedTaskQuizQuestion(
            "문제 $n", listOf("A", "B", "C", "D"), 0, "풀이 $n", listOf("", "B 이유", "C 이유", "D 이유"), chapter_name = chapter
        )
        // 대단원(Ⅰ 지권의 변화)은 고른 범위(지구계~지권의 구조)에 없어 받지 않는다
        val service = TaskQuizService(FakeTaskQuizLlmClient(listOf(q(1, "지구계"), q(2, "지권의 구조 "), q(3, "Ⅰ 지권의 변화"), q(4, ""))))
        service.generateAttempt(taskId, 1, "과학 문제집 30쪽 풀기")

        transaction(db) {
            TaskQuizQuestionRow.all().sortedBy { it.id.value }.map { it.chapterName }
        } shouldBe listOf("지구계", "지권의 구조", null, null)

        val second = service.getCurrentQuiz(userId, taskId).questions[1]
        service.answerQuestion(userId, taskId, second.id, second.choice("B").id)
        ReviewService().listOpen(userId, cursor = null).questions.single().chapterName shouldBe "지권의 구조"
    }

    "학습 범위도 학생 프로필도 없으면 '지정 안 됨' 과 학년 정보 없음으로 넘긴다" {
        val taskId = seedTask("영어 단어 30개 외우기", grade = null, withScope = false)
        val llm = FakeTaskQuizLlmClient()

        TaskQuizService(llm).generateAttempt(taskId, 1, "영어 단어 30개 외우기")

        llm.lastGradeLabel shouldBe "중학생(학년 정보 없음)"
        llm.lastStudyScope shouldBe "지정 안 됨"
        transaction(db) { TaskQuizAttemptTable.selectAll().count() } shouldBe 1L
    }

    /** 수학·과학 두 과목 범위가 있는 보드에 할일 하나를 만든다. taskSubject 가 "수학"/"과학" 이면 할일 subject_id 를 그 과목으로 저장한다 */
    fun seedTwoSubjectTask(taskName: String, taskSubject: String? = null): Int = transaction(db) {
        val user = UserRow.new { email = "tq${UserRow.count()}@test.com"; username = "t"; password = "x"; createdAt = OffsetDateTime.now() }
        val board = PlanBoardRow.new {
            this.user = user; title = "보드"; startDate = day; endDate = day; createdAt = OffsetDateTime.now()
        }
        val subjects = listOf("수학" to "이차방정식", "과학" to "지구계").associate { (subjectName, unitName) ->
            val subject = SubjectRow.new { name = subjectName }
            val textbook = TextbookRow.new { this.subject = subject; title = "$subjectName 3" }
            val chapter = ChapterRow.new { this.textbook = textbook; chapterName = unitName; chapterOrder = 1 }
            PlanSubjectRow.new { planBoard = board; this.textbook = textbook; startChapter = chapter; endChapter = chapter }
            subjectName to subject
        }
        val dailyPlan = DailyPlanRow.new { planBoard = board; planDate = day }
        PlanTaskRow.new {
            this.dailyPlan = dailyPlan; this.taskName = taskName
            startTime = LocalTime.of(16, 0); endTime = LocalTime.of(17, 0); estimatedMinutes = 60
            subjectId = taskSubject?.let { subjects.getValue(it).id }
        }.id.value
    }

    "여러 과목 보드에서는 할일 이름으로 추정한 과목의 범위만 넘긴다 (수학 할일에 과학 문제가 섞이지 않게)" {
        val taskId = seedTwoSubjectTask("수학 문제집 30쪽 풀기")
        val llm = FakeTaskQuizLlmClient()

        TaskQuizService(llm).generateAttempt(taskId, 1, "수학 문제집 30쪽 풀기")

        llm.lastStudyScope shouldBe "수학: 이차방정식"
        llm.lastCount shouldBe 5
    }

    "할일에 저장된 과목(subject_id)이 있으면 이름에 과목명이 없어도 그 과목 범위만 넘긴다" {
        val taskId = seedTwoSubjectTask("문제집 30쪽 풀기", taskSubject = "과학")
        val llm = FakeTaskQuizLlmClient()

        TaskQuizService(llm).generateAttempt(taskId, 1, "문제집 30쪽 풀기")

        llm.lastStudyScope shouldBe "과학: 지구계"
    }

    "할일 과목을 정할 수 없으면 보드 범위 전체를 넘긴다" {
        val taskId = seedTwoSubjectTask("문제집 30쪽 풀기")
        val llm = FakeTaskQuizLlmClient()

        TaskQuizService(llm).generateAttempt(taskId, 1, "문제집 30쪽 풀기")

        llm.lastStudyScope shouldBe "수학: 이차방정식\n과학: 지구계"
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
        service.getCurrentQuiz(userId, taskId).questions.forEach { service.answerQuestion(userId, taskId, it.id, it.choice("B").id) }
        service.submitQuiz(userId, taskId)
    }

    "openQuiz: 퀴즈가 없으면 종료 시각 전이어도 바로 1차 퀴즈를 만들고, 다시 열어도 같은 퀴즈를 준다" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        val userId = lastUserId
        val service = TaskQuizService(FakeTaskQuizLlmClient())

        val first = service.openQuiz(userId, taskId)
        val second = service.openQuiz(userId, taskId)

        first.attemptNumber shouldBe 1
        first.questions.size shouldBe 5
        second.questions.map { it.id } shouldBe first.questions.map { it.id }
        attemptCount(taskId) shouldBe 1L
    }

    "openQuiz: 떨어진 뒤 10분 전엔 그 차수를 그대로 주고, 10분이 지나면 다음 차수를 바로 만든다. 3번 다 떨어지면 더 만들지 않는다" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        val userId = lastUserId
        val now = TaskQuizService(FakeTaskQuizLlmClient())
        val later = { minutes: Long -> TaskQuizService(FakeTaskQuizLlmClient(), Clock.offset(Clock.systemUTC(), Duration.ofMinutes(minutes))) }

        now.openQuiz(userId, taskId)
        failAll(now, userId, taskId)
        now.openQuiz(userId, taskId).attemptNumber shouldBe 1 // 대기 중 — 예전처럼 최근 차수
        later(11).openQuiz(userId, taskId).attemptNumber shouldBe 2
        failAll(now, userId, taskId)
        later(11).openQuiz(userId, taskId).attemptNumber shouldBe 3
        failAll(now, userId, taskId)
        later(60).openQuiz(userId, taskId).attemptNumber shouldBe 3
        attemptCount(taskId) shouldBe 3L
    }

    "openQuiz: 통과한 태스크면 새로 만들지 않고 통과한 차수를 준다" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        val userId = lastUserId
        val service = TaskQuizService(FakeTaskQuizLlmClient())
        service.openQuiz(userId, taskId).questions.forEach { service.answerQuestion(userId, taskId, it.id, it.choice("A").id) }
        service.submitQuiz(userId, taskId).passed shouldBe true

        service.openQuiz(userId, taskId).attemptNumber shouldBe 1
        attemptCount(taskId) shouldBe 1L
    }

    "openQuiz: AI 가 퀴즈를 못 만들면 GenerationFailedException 이고 아무것도 저장하지 않는다" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        val service = TaskQuizService(FakeTaskQuizLlmClient(emptyList()))

        shouldThrow<TaskQuizValidationException.GenerationFailedException> { service.openQuiz(lastUserId, taskId) }
        attemptCount(taskId) shouldBe 0L
    }

    "openQuiz: 다른 사람 태스크면 TaskQuizNotFoundException 이고 퀴즈를 만들지 않는다" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        val service = TaskQuizService(FakeTaskQuizLlmClient())

        shouldThrow<TaskQuizNotFoundException> { service.openQuiz(lastUserId + 999, taskId) }
        attemptCount(taskId) shouldBe 0L
    }

    "generateAttempt: 스케줄러와 퀴즈 열기가 같은 차수를 겹쳐 만들려 해도 한 번만 저장된다" {
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
        val correctChoiceId = firstQuestion.choice("A").id // FakeTaskQuizLlmClient 는 "A" 가 정답

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
        val wrongChoiceId = firstQuestion.choice("B").id

        val response = service.answerQuestion(userId, taskId, firstQuestion.id, wrongChoiceId)

        response.isCorrect shouldBe false
        response.correctChoiceId shouldBe firstQuestion.choice("A").id
        response.reason shouldBe "B 가 틀린 이유" // 고른 오답 보기의 짧은 이유
        response.explanation shouldBe "자세한 풀이 1" // 정답 보기의 자세한 풀이
    }

    "answerQuestion: 틀린 문제는 보기별 이유·풀이와 함께 복습 목록에 들어가고, 맞힌 문제는 들어가지 않는다" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        val userId = lastUserId
        val service = TaskQuizService(FakeTaskQuizLlmClient())
        service.generateAttempt(taskId, 1, "수학 문제집 풀기")
        val (first, second) = service.getCurrentQuiz(userId, taskId).questions

        service.answerQuestion(userId, taskId, first.id, first.choices.first { it.choiceText == "A" }.id) // 맞힘
        service.answerQuestion(userId, taskId, second.id, second.choices.first { it.choiceText == "C" }.id) // 틀림

        val copied = ReviewService().listOpen(userId, cursor = null).questions.single()
        listOf(copied.source, copied.questionText) shouldBe listOf("TASK_QUIZ", "문제 2")
        copied.choices.map { it.choiceText }.sorted() shouldBe listOf("A", "B", "C", "D")
        val wrong = ReviewService().answer(userId, copied.id, copied.choices.first { it.choiceText == "B" }.id)
        listOf(wrong.isCorrect, wrong.reason, wrong.explanation, wrong.cleared) shouldBe listOf(false, "B 가 틀린 이유", "자세한 풀이 2", false)
    }

    "answerQuestion: 이미 답한 문제에 다시 답하면 AlreadyAnsweredException — 정답을 본 뒤 답 변경을 막는다" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        val userId = lastUserId
        val service = TaskQuizService(FakeTaskQuizLlmClient())
        service.generateAttempt(taskId, 1, "수학 문제집 풀기")
        val firstQuestion = service.getCurrentQuiz(userId, taskId).questions.first()
        service.answerQuestion(userId, taskId, firstQuestion.id, firstQuestion.choice("B").id)

        shouldThrow<TaskQuizValidationException.AlreadyAnsweredException> {
            service.answerQuestion(userId, taskId, firstQuestion.id, firstQuestion.choice("A").id)
        }
    }

    "answerQuestion: 다른 문제의 보기 id 를 보내면 InvalidAnswerException — 정답 보기를 여러 문제에 재사용하는 걸 막는다" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        val userId = lastUserId
        val service = TaskQuizService(FakeTaskQuizLlmClient())
        service.generateAttempt(taskId, 1, "수학 문제집 풀기")
        val questions = service.getCurrentQuiz(userId, taskId).questions
        val choiceIdFromAnotherQuestion = questions[1].choice("A").id

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
        questions.take(4).forEach { service.answerQuestion(userId, taskId, it.id, it.choice("A").id) }

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
        questions.forEach { service.answerQuestion(userId, taskId, it.id, it.choice("A").id) } // 전부 정답("A")

        val result = service.submitQuiz(userId, taskId)

        result.correctCount shouldBe 5
        result.passed shouldBe true
        result.results.all { it.isCorrect } shouldBe true
        transaction(db) { PlanTaskRow[taskId].status } shouldBe PlanTaskStatus.DONE
    }

    "submitQuiz: 이미 채점된 퀴즈를 다시 제출하면 AlreadySubmittedException" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        val userId = lastUserId
        val service = TaskQuizService(FakeTaskQuizLlmClient())
        service.generateAttempt(taskId, 1, "수학 문제집 풀기")
        val questions = service.getCurrentQuiz(userId, taskId).questions
        questions.forEach { service.answerQuestion(userId, taskId, it.id, it.choice("A").id) }
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
        questions.forEach { service.answerQuestion(userId, taskId, it.id, it.choice("B").id) } // 전부 오답

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
        service.answerQuestion(userId, taskId, first.id, first.choice("C").id)

        val questions = service.getCurrentQuiz(userId, taskId).questions

        questions.first().selectedChoiceId shouldBe first.choice("C").id
        questions.drop(1).map { it.selectedChoiceId } shouldBe List(4) { null }
    }

    /** 채점이 끝난 퀴즈 차수를 넣고 태스크 상태를 퀴즈 결과대로 맞춘다 (통과 DONE, 마지막 차수 불합격 FAILED) */
    fun seedGradedAttempt(taskId: Int, attemptNumber: Int, passed: Boolean) = transaction(db) {
        TaskQuizAttemptTable.insert {
            it[planTaskId] = taskId; it[this.attemptNumber] = attemptNumber; it[totalCount] = 5
            it[correctCount] = if (passed) 5 else 0; it[this.passed] = passed; it[createdAt] = OffsetDateTime.now()
        }
        PlanTaskRow[taskId].status = when {
            passed -> PlanTaskStatus.DONE
            attemptNumber >= MAX_ATTEMPTS -> PlanTaskStatus.FAILED
            else -> PlanTaskStatus.PENDING
        }
    }

    "updateTask(status): 퀴즈를 통과한 태스크는 미완료로 되돌릴 수 없다 (같은 값은 그대로 성공)" {
        val taskId = seedTask("과학 문제집 30쪽 풀기", grade = 2, withScope = false)
        seedGradedAttempt(taskId, 1, passed = true)

        shouldThrow<PlanTaskValidationException.TaskFinalizedException> {
            PlanTaskService().updateTask(lastUserId, taskId, PlanTaskUpdateRequest(status = PlanTaskStatus.PENDING))
        }
        PlanTaskService().updateTask(lastUserId, taskId, PlanTaskUpdateRequest(status = PlanTaskStatus.DONE)).status shouldBe PlanTaskStatus.DONE
        transaction(db) { PlanTaskRow[taskId].status } shouldBe PlanTaskStatus.DONE
    }

    "updateTask(status): 3차까지 모두 떨어져 실패가 확정된 태스크는 완료로 바꿀 수 없다" {
        val taskId = seedTask("과학 문제집 30쪽 풀기", grade = 2, withScope = false)
        (1..3).forEach { seedGradedAttempt(taskId, it, passed = false) }

        shouldThrow<PlanTaskValidationException.TaskFinalizedException> {
            PlanTaskService().updateTask(lastUserId, taskId, PlanTaskUpdateRequest(status = PlanTaskStatus.DONE))
        }
        transaction(db) { PlanTaskRow[taskId].status } shouldBe PlanTaskStatus.FAILED
    }

    "updateTask: 퀴즈로 결과가 확정된 태스크는 시간·예상 시간을 못 바꾸고, 이름 변경과 같은 값 재전송은 된다" {
        val taskId = seedTask("과학 문제집 30쪽 풀기", grade = 2, withScope = false)
        seedGradedAttempt(taskId, 1, passed = true)
        val (start, end, minutes) = transaction(db) {
            PlanTaskRow[taskId].let { Triple(it.startTime.toString(), it.endTime.toString(), it.estimatedMinutes) }
        }

        shouldThrow<PlanTaskValidationException.TaskFinalizedException> {
            PlanTaskService().updateTask(lastUserId, taskId, PlanTaskUpdateRequest(startTime = "21:00", endTime = "21:30"))
        }
        shouldThrow<PlanTaskValidationException.TaskFinalizedException> {
            PlanTaskService().updateTask(lastUserId, taskId, PlanTaskUpdateRequest(estimatedMinutes = minutes + 10))
        }
        // 프론트처럼 모든 필드를 지금 값 그대로 보내면서 이름만 바꾸는 건 된다
        PlanTaskService().updateTask(
            lastUserId, taskId, PlanTaskUpdateRequest(taskName = "과학 문제집 31쪽", startTime = start, endTime = end, estimatedMinutes = minutes)
        ).taskName shouldBe "과학 문제집 31쪽"
        transaction(db) { PlanTaskRow[taskId].let { it.startTime.toString() to it.endTime.toString() } } shouldBe (start to end)
    }

    "updateTask: 재시도가 남아 결과가 확정되지 않은 태스크는 시간을 바꿀 수 있다" {
        val taskId = seedTask("과학 문제집 30쪽 풀기", grade = 2, withScope = false)
        seedGradedAttempt(taskId, 1, passed = false)

        PlanTaskService().updateTask(lastUserId, taskId, PlanTaskUpdateRequest(startTime = "21:00", endTime = "21:30"))
            .let { it.startTime to it.estimatedMinutes } shouldBe ("21:00" to 30)
    }

    "updateTask(status): 퀴즈를 통과하지 않은 태스크는 완료로 바꿀 수 없다 (퀴즈 없음 / 재시도 남음, #259)" {
        val retrying = seedTask("과학 문제집 30쪽 풀기", grade = 2, withScope = false)
        seedGradedAttempt(retrying, 1, passed = false)
        shouldThrow<PlanTaskValidationException.TaskQuizRequiredException> {
            PlanTaskService().updateTask(lastUserId, retrying, PlanTaskUpdateRequest(status = PlanTaskStatus.DONE))
        }
        transaction(db) { PlanTaskRow[retrying].status } shouldBe PlanTaskStatus.PENDING

        val noQuiz = transaction(db) {
            PlanTaskRow.new {
                dailyPlan = PlanTaskRow[retrying].dailyPlan; taskName = "영어 단어 외우기"
                startTime = LocalTime.of(17, 0); endTime = LocalTime.of(17, 30); estimatedMinutes = 30
            }.id.value
        }
        shouldThrow<PlanTaskValidationException.TaskQuizRequiredException> {
            PlanTaskService().updateTask(lastUserId, noQuiz, PlanTaskUpdateRequest(status = PlanTaskStatus.DONE))
        }
        transaction(db) { PlanTaskRow[noQuiz].status } shouldBe PlanTaskStatus.PENDING
        // 미완료를 그대로 미완료로 보내는 건 성공
        PlanTaskService().updateTask(lastUserId, noQuiz, PlanTaskUpdateRequest(status = PlanTaskStatus.PENDING)).status shouldBe PlanTaskStatus.PENDING
    }

    "updateTask(status): 퀴즈 없이 이미 완료된 태스크(예전 데이터)는 같은 값은 성공하고 완료 취소도 된다" {
        val taskId = seedTask("과학 문제집 30쪽 풀기", grade = 2, withScope = false)
        transaction(db) { PlanTaskRow[taskId].status = PlanTaskStatus.DONE }

        PlanTaskService().updateTask(lastUserId, taskId, PlanTaskUpdateRequest(status = PlanTaskStatus.DONE)).status shouldBe PlanTaskStatus.DONE
        PlanTaskService().updateTask(lastUserId, taskId, PlanTaskUpdateRequest(status = PlanTaskStatus.PENDING)).status shouldBe PlanTaskStatus.PENDING
        // 한 번 취소하면 다시 완료하려면 퀴즈를 통과해야 한다
        shouldThrow<PlanTaskValidationException.TaskQuizRequiredException> {
            PlanTaskService().updateTask(lastUserId, taskId, PlanTaskUpdateRequest(status = PlanTaskStatus.DONE))
        }
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

    "generateAttempt: 보기 순서를 섞어 저장하고, 정답 표시와 오답 보기의 짧은 이유는 보기와 함께 움직인다" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        TaskQuizService(FakeTaskQuizLlmClient(), random = Random(7)).generateAttempt(taskId, 1, "수학 문제집 풀기")

        // 문항별 보기를 저장(=응답) 순서대로: (보기, 정답 여부, 풀이·이유)
        val stored = transaction(db) {
            TaskQuizQuestionRow.all().sortedBy { it.id.value }.map { question ->
                TaskQuizChoiceRow.find { TaskQuizChoiceTable.questionId eq question.id }.sortedBy { it.id.value }
                    .map { Triple(it.choiceText, it.isCorrect, it.explanation) }
            }
        }
        stored.forEachIndexed { i, choices ->
            choices.map { it.first }.sorted() shouldBe listOf("A", "B", "C", "D")
            choices.single { it.second }.first shouldBe "A"
            choices.associate { it.first to it.third } shouldBe mapOf(
                "A" to "자세한 풀이 ${i + 1}", "B" to "B 가 틀린 이유", "C" to "C 가 틀린 이유", "D" to "D 가 틀린 이유"
            )
        }
        // AI 는 모든 문항의 정답을 첫 보기로 줬지만 저장된 정답 위치는 한 곳에 몰리지 않는다
        stored.map { choices -> choices.indexOfFirst { it.second } }.toSet().size shouldBeGreaterThan 1
    }

    "quizQuestionCount: 30분 이하 4문항, 1시간 이하 5문항, 1시간 초과 7문항" {
        listOf(0, 20, 30, 31, 60, 61, 120).map { quizQuestionCount(it) } shouldBe listOf(4, 4, 4, 5, 5, 7, 7)
    }

    "taskQuizPassCount: 70점 이상 — 3문항 3개, 4문항 3개, 5문항 4개, 7문항 5개" {
        listOf(1, 3, 4, 5, 7).map { taskQuizPassCount(it) } shouldBe listOf(1, 3, 3, 4, 5)
    }

    "submitQuiz: 30분 할일은 4문항이고 3개 맞으면 통과, 2개면 불합격" {
        suspend fun solve(correct: Int): Boolean {
            val taskId = seedTask("영어 단어 외우기", grade = null, withScope = false, minutes = 30)
            val userId = lastUserId
            val service = TaskQuizService(FakeTaskQuizLlmClient())
            val quiz = service.openQuiz(userId, taskId)
            quiz.questions.size shouldBe 4
            quiz.passCount shouldBe 3
            quiz.questions.forEachIndexed { i, q -> service.answerQuestion(userId, taskId, q.id, q.choice(if (i < correct) "A" else "B").id) }
            return service.submitQuiz(userId, taskId).passed
        }

        solve(correct = 3) shouldBe true
        solve(correct = 2) shouldBe false
    }

    "generateAttempt: 할일 길이에 맞춘 문항 수를 AI 에 요청하고 그만큼 저장한다" {
        val short = seedTask("영어 단어 외우기", grade = null, withScope = false, minutes = 20)
        val shortLlm = FakeTaskQuizLlmClient()
        TaskQuizService(shortLlm).generateAttempt(short, 1, "영어 단어 외우기")
        shortLlm.lastCount shouldBe 4

        val long = seedTask("수학 문제집 풀기", grade = null, withScope = false, minutes = 90)
        val longLlm = FakeTaskQuizLlmClient()
        TaskQuizService(longLlm).generateAttempt(long, 1, "수학 문제집 풀기")
        longLlm.lastCount shouldBe 7

        val stored = transaction(db) {
            TaskQuizAttemptTable.selectAll().associate { it[TaskQuizAttemptTable.planTaskId].value to it[TaskQuizAttemptTable.totalCount] }
        }
        stored shouldBe mapOf(short to 4, long to 7)
    }

    "generateAttempt: AI 가 요청보다 많이 주면 정한 문항 수까지만 저장한다" {
        val taskId = seedTask("영어 단어 외우기", grade = null, withScope = false, minutes = 20)
        val llm = FakeTaskQuizLlmClient((1..6).map { GeneratedTaskQuizQuestion("문제 $it", listOf("A", "B", "C", "D"), 0, "풀이") })

        TaskQuizService(llm).generateAttempt(taskId, 1, "영어 단어 외우기")

        transaction(db) { TaskQuizQuestionTable.selectAll().count() } shouldBe 4L
    }

    "submitQuiz: 7문항이면 5개 맞아야 통과하고, 응답과 조회에 통과 기준(passCount)을 준다" {
        suspend fun solve(correct: Int): Pair<Int, Boolean> {
            val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false, minutes = 90)
            val userId = lastUserId
            val service = TaskQuizService(FakeTaskQuizLlmClient())
            val quiz = service.openQuiz(userId, taskId)
            quiz.passCount shouldBe 5
            quiz.questions.forEachIndexed { i, q -> service.answerQuestion(userId, taskId, q.id, q.choice(if (i < correct) "A" else "B").id) }
            val result = service.submitQuiz(userId, taskId)
            result.totalCount shouldBe 7
            return result.passCount to result.passed
        }

        solve(correct = 5) shouldBe (5 to true)
        solve(correct = 4) shouldBe (5 to false)
    }

    "submitQuiz: AI 가 문항을 덜 줘서 3문항만 저장됐으면 3개를 다 맞히면 통과한다 (예전엔 4개 기준이라 통과 불가)" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false) // 45분 → 5문항 요청
        val userId = lastUserId
        val llm = FakeTaskQuizLlmClient((1..3).map { GeneratedTaskQuizQuestion("문제 $it", listOf("A", "B", "C", "D"), 0, "풀이") })
        val service = TaskQuizService(llm)
        service.openQuiz(userId, taskId).questions.forEach { service.answerQuestion(userId, taskId, it.id, it.choice("A").id) }

        val result = service.submitQuiz(userId, taskId)

        result.totalCount shouldBe 3
        result.passed shouldBe true
    }

    fun failRequest() = PlanTaskUpdateRequest(status = PlanTaskStatus.FAILED)

    "updateTask(status=FAILED): 대기 중인 할일을 실패로 저장하고 status=FAILED 를 준다. 다시 보내도 그대로 성공" {
        val taskId = seedTask("과학 문제집 30쪽 풀기", grade = 2, withScope = false)

        val failed = PlanTaskService().updateTask(lastUserId, taskId, failRequest())

        failed.status shouldBe PlanTaskStatus.FAILED
        PlanTaskService().updateTask(lastUserId, taskId, failRequest()).status shouldBe PlanTaskStatus.FAILED
        transaction(db) { PlanTaskRow[taskId].status } shouldBe PlanTaskStatus.FAILED
    }

    "updateTask(status=FAILED): 완료된 할일은 실패로 바꿀 수 없다 (TASK_FINALIZED)" {
        val taskId = seedTask("과학 문제집 30쪽 풀기", grade = 2, withScope = false)
        seedGradedAttempt(taskId, 1, passed = true)

        shouldThrow<PlanTaskValidationException.TaskFinalizedException> {
            PlanTaskService().updateTask(lastUserId, taskId, failRequest())
        }
        transaction(db) { PlanTaskRow[taskId].status } shouldBe PlanTaskStatus.DONE
    }

    "updateTask(status=FAILED): 다른 사람 할일이면 PlanTaskNotFoundException" {
        val taskId = seedTask("과학 문제집 30쪽 풀기", grade = 2, withScope = false)

        shouldThrow<PlanTaskNotFoundException> { PlanTaskService().updateTask(lastUserId + 999, taskId, failRequest()) }
        transaction(db) { PlanTaskRow[taskId].status } shouldBe PlanTaskStatus.PENDING
    }

    "updateTask(status=DONE): 완료는 퀴즈 통과로만 되므로 TASK_QUIZ_REQUIRED, 같이 보낸 다른 필드도 바뀌지 않는다" {
        val taskId = seedTask("과학 문제집 30쪽 풀기", grade = 2, withScope = false)

        shouldThrow<PlanTaskValidationException.TaskQuizRequiredException> {
            PlanTaskService().updateTask(lastUserId, taskId, PlanTaskUpdateRequest(taskName = "바뀐 이름", status = PlanTaskStatus.DONE))
        }
        transaction(db) { PlanTaskRow[taskId].let { it.taskName to it.status } } shouldBe ("과학 문제집 30쪽 풀기" to PlanTaskStatus.PENDING)
    }

    "updateTask(status=PENDING): 퀴즈로 완료된 할일은 취소할 수 없고, 퀴즈 없이 완료된 예전 할일은 취소된다" {
        val passed = seedTask("과학 문제집 30쪽 풀기", grade = 2, withScope = false)
        seedGradedAttempt(passed, 1, passed = true)
        shouldThrow<PlanTaskValidationException.TaskFinalizedException> {
            PlanTaskService().updateTask(lastUserId, passed, PlanTaskUpdateRequest(status = PlanTaskStatus.PENDING))
        }

        val legacy = transaction(db) {
            PlanTaskRow.new {
                dailyPlan = PlanTaskRow[passed].dailyPlan; taskName = "영어 단어 외우기"; status = PlanTaskStatus.DONE
                startTime = LocalTime.of(17, 0); endTime = LocalTime.of(17, 30); estimatedMinutes = 30
            }.id.value
        }
        PlanTaskService().updateTask(lastUserId, legacy, PlanTaskUpdateRequest(status = PlanTaskStatus.PENDING))
            .status shouldBe PlanTaskStatus.PENDING
    }

    "updateTask(status): 실패한 할일은 완료·대기로 되돌릴 수 없다 (같은 값 FAILED 재전송은 그대로 성공)" {
        val taskId = seedTask("과학 문제집 30쪽 풀기", grade = 2, withScope = false)
        PlanTaskService().updateTask(lastUserId, taskId, failRequest())

        shouldThrow<PlanTaskValidationException.TaskFinalizedException> {
            PlanTaskService().updateTask(lastUserId, taskId, PlanTaskUpdateRequest(status = PlanTaskStatus.DONE))
        }
        shouldThrow<PlanTaskValidationException.TaskFinalizedException> {
            PlanTaskService().updateTask(lastUserId, taskId, PlanTaskUpdateRequest(status = PlanTaskStatus.PENDING))
        }
        PlanTaskService().updateTask(lastUserId, taskId, failRequest()).status shouldBe PlanTaskStatus.FAILED
    }

    "submitQuiz: 3차까지 모두 떨어지면 할일이 실패(FAILED)로 저장된다" {
        val taskId = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        val userId = lastUserId
        (1..2).forEach { seedGradedAttempt(taskId, it, passed = false) }
        val service = TaskQuizService(FakeTaskQuizLlmClient())
        service.generateAttempt(taskId, 3, "수학 문제집 풀기")

        failAll(service, userId, taskId)

        transaction(db) { PlanTaskRow[taskId].status } shouldBe PlanTaskStatus.FAILED
    }

    "TaskQuizTriggerJob: 실패 처리한 할일은 종료 시각이 지나도 퀴즈를 만들지 않고, 재시도도 만들지 않는다" {
        val failedTask = seedTask("수학 문제집 풀기", grade = null, withScope = false)
        val userId = lastUserId
        val pendingTask = transaction(db) {
            PlanTaskRow.new {
                dailyPlan = PlanTaskRow[failedTask].dailyPlan; taskName = "영어 단어 외우기"
                startTime = LocalTime.of(17, 0); endTime = LocalTime.of(17, 30); estimatedMinutes = 30
            }.id.value
        }
        val retrying = transaction(db) {
            PlanTaskRow.new {
                dailyPlan = PlanTaskRow[failedTask].dailyPlan; taskName = "국어 복습"
                startTime = LocalTime.of(18, 0); endTime = LocalTime.of(18, 30); estimatedMinutes = 30
            }.id.value
        }
        transaction(db) {
            TaskQuizAttemptTable.insert {
                it[planTaskId] = retrying; it[attemptNumber] = 1; it[totalCount] = 5; it[correctCount] = 0
                it[passed] = false; it[createdAt] = OffsetDateTime.now().minusHours(1)
            }
        }
        PlanTaskService().updateTask(userId, failedTask, failRequest())
        PlanTaskService().updateTask(userId, retrying, failRequest())

        // 그날 밤 23:00 (KST) — 세 할일 모두 종료 시각이 지났다
        val due = TaskQuizTriggerJob(TaskQuizService(FakeTaskQuizLlmClient()))
            .findDue(day.atTime(23, 0).atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime())

        due.map { it.runKey } shouldBe listOf("task_quiz_${pendingTask}_1")
    }
})

private class FakeTaskQuizLlmClient(
    private val questions: List<GeneratedTaskQuizQuestion>? = null // null 이면 요청받은 count 만큼 만든다
) : TaskQuizLlmClient(dummyAppConfig()) {
    var lastTaskName: String? = null
    var lastGradeLabel: String? = null
    var lastStudyScope: String? = null
    var lastCount: Int? = null

    override suspend fun generateQuestions(taskName: String, gradeLabel: String, studyScope: String, count: Int): List<GeneratedTaskQuizQuestion> {
        lastTaskName = taskName
        lastGradeLabel = gradeLabel
        lastStudyScope = studyScope
        lastCount = count
        return questions ?: (1..count).map {
            GeneratedTaskQuizQuestion("문제 $it", listOf("A", "B", "C", "D"), 0, "자세한 풀이 $it", listOf("", "B 가 틀린 이유", "C 가 틀린 이유", "D 가 틀린 이유"))
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

/** 보기 순서가 섞여 저장되므로 테스트는 위치가 아니라 보기 글자로 고른다 */
private fun TaskQuizQuestionResponse.choice(text: String) = choices.first { it.choiceText == text }
