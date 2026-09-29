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
import com.github.nepyh.rooter.module.taskquiz.GeneratedTaskQuizQuestion
import com.github.nepyh.rooter.module.taskquiz.TaskQuizLlmClient
import com.github.nepyh.rooter.module.taskquiz.TaskQuizService
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizAttemptTable
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizChoiceTable
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizQuestionTable
import com.github.nepyh.rooter.module.user.model.StudentProfileRow
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
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

    /** 사용자가 직접 추가한 태스크 하나를 만든다. withScope 면 플랜보드에 과학 단원 범위를, grade 가 있으면 학생 프로필을 붙인다 */
    fun seedTask(taskName: String, grade: Int?, withScope: Boolean): Int = transaction(db) {
        val user = UserRow.new { email = "tq@test.com"; username = "t"; password = "x"; createdAt = OffsetDateTime.now() }
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
})

private class FakeTaskQuizLlmClient : TaskQuizLlmClient(dummyAppConfig()) {
    var lastTaskName: String? = null
    var lastGradeLabel: String? = null
    var lastStudyScope: String? = null

    override suspend fun generateQuestions(taskName: String, gradeLabel: String, studyScope: String): List<GeneratedTaskQuizQuestion> {
        lastTaskName = taskName
        lastGradeLabel = gradeLabel
        lastStudyScope = studyScope
        return (1..5).map { GeneratedTaskQuizQuestion("문제 $it", listOf("A", "B", "C", "D"), 0, "해설") }
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
