import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.common.config.EnvironmentMode
import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.taskquiz.ShiftableTask
import com.github.nepyh.rooter.module.taskquiz.TaskQuizLlmClient
import com.github.nepyh.rooter.module.taskquiz.TaskQuizService
import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizAnswer
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizAttemptRow
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizAttemptTable
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizChoiceRow
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizChoiceTable
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizQuestionRow
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizQuestionTable
import com.github.nepyh.rooter.module.taskquiz.shiftLaterTasks
import com.github.nepyh.rooter.module.user.model.DayOfWeek
import com.github.nepyh.rooter.module.user.model.UnavailableTimeRow
import com.github.nepyh.rooter.module.user.model.UnavailableTimeTable
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * 태스크 완료 퀴즈 불합격 시 남은 계획 밀기 테스트.
 * shiftLaterTasks 는 순수 함수, submitQuiz 쪽은 로컬 PostgreSQL 이 필요한 통합 테스트.
 */
class TaskQuizShiftTest : StringSpec({

    fun m(hhmm: String): Int = hhmm.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
    fun summary(result: List<com.github.nepyh.rooter.module.taskquiz.ShiftedTask>) =
        result.map { "${it.id}:${it.startTime}~${it.endTime}" }

    // ---- shiftLaterTasks (순수 함수) ----

    "shiftLaterTasks: 15분씩 밀고 원래 간격은 유지한다" {
        val result = shiftLaterTasks(
            listOf(ShiftableTask(1, m("17:10"), m("17:50")), ShiftableTask(2, m("18:00"), m("18:30"))),
            15, emptyList()
        )
        summary(result) shouldBe listOf("1:17:25~18:05", "2:18:15~18:45")
    }

    "shiftLaterTasks: 학원 같은 막힌 시간과 겹치면 그 뒤로 넘기고, 뒤 태스크는 겹치지 않게 이어 붙인다" {
        val result = shiftLaterTasks(
            listOf(ShiftableTask(1, m("17:10"), m("17:50")), ShiftableTask(2, m("20:00"), m("20:30"))),
            15, listOf(m("18:00") to m("20:00"))
        )
        summary(result) shouldBe listOf("1:20:00~20:40", "2:20:40~21:10")
    }

    "shiftLaterTasks: 밀려서 23시를 넘어도 그날 안에 넣고, 자정을 넘기면 23:59 에 맞춘다" {
        val result = shiftLaterTasks(listOf(ShiftableTask(1, m("22:50"), m("23:20")), ShiftableTask(2, m("23:30"), m("23:50"))), 15, emptyList())
        summary(result) shouldBe listOf("1:23:05~23:35", "2:23:45~23:59")
    }

    // ---- submitQuiz 통합 ----

    val testDbUrl = System.getenv("TEST_JDBC_URL") ?: "jdbc:postgresql://localhost:5432/planboard_test"
    val testDbUser = System.getenv("TEST_DB_USER") ?: "rooter_dev"
    val testDbPassword = System.getenv("TEST_DB_PASSWORD") ?: "wapapyrus"

    ensureTestDatabase(testDbUrl, testDbUser, testDbPassword)
    val db = Database.connect(url = testDbUrl, driver = "org.postgresql.Driver", user = testDbUser, password = testDbPassword)

    beforeSpec {
        transaction(db) {
            listOf(
                "task_quiz_choices", "task_quiz_questions", "task_quiz_attempts", "plan_tasks", "daily_plans",
                "plan_boards", "user_unavailable_times", "users"
            ).forEach { exec("DROP TABLE IF EXISTS $it CASCADE") }
            SchemaUtils.create(
                UserTable, UnavailableTimeTable, PlanBoardTable, DailyPlanTable, PlanTaskTable,
                TaskQuizAttemptTable, TaskQuizQuestionTable, TaskQuizChoiceTable
            )
        }
    }

    beforeEach {
        transaction(db) {
            TaskQuizChoiceTable.deleteAll(); TaskQuizQuestionTable.deleteAll(); TaskQuizAttemptTable.deleteAll()
            PlanTaskTable.deleteAll(); DailyPlanTable.deleteAll(); PlanBoardTable.deleteAll()
            UnavailableTimeTable.deleteAll(); UserTable.deleteAll()
        }
    }

    val today = LocalDate.of(2026, 9, 29) // 화요일
    val at1705 = Clock.fixed(Instant.parse("2026-09-29T08:05:00Z"), ZoneOffset.UTC) // 한국 시간 17:05

    data class Seeded(val userId: Int, val quizTaskId: Int, val otherTaskIds: Map<String, Int>, val answers: List<Pair<Int, Pair<Int, Int>>>)

    /** 16:00~17:00 퀴즈 태스크(attemptNumber 차수 퀴즈) + 같은 날 다른 태스크들. answers = questionId to (정답, 오답) */
    fun seed(attemptNumber: Int, others: List<Triple<String, String, Boolean>>, academy: Pair<String, String>? = null): Seeded = transaction(db) {
        val user = UserRow.new { email = "shift@test.com"; username = "t"; password = "x"; createdAt = OffsetDateTime.now() }
        if (academy != null) {
            UnavailableTimeRow.new {
                this.user = user; dayOfWeek = DayOfWeek.TUESDAY
                startTime = LocalTime.parse(academy.first); endTime = LocalTime.parse(academy.second)
            }
        }
        val board = PlanBoardRow.new { this.user = user; title = "보드"; startDate = today; endDate = today; createdAt = OffsetDateTime.now() }
        val dailyPlan = DailyPlanRow.new { planBoard = board; planDate = today }
        fun task(name: String, range: String, completed: Boolean) = PlanTaskRow.new {
            this.dailyPlan = dailyPlan; taskName = name
            startTime = LocalTime.parse(range.substringBefore("~")); endTime = LocalTime.parse(range.substringAfter("~"))
            estimatedMinutes = 30; isCompleted = completed
        }.id.value
        val quizTask = task("퀴즈 태스크", "16:00~17:00", false)
        val otherIds = others.associate { (name, range, completed) -> name to task(name, range, completed) }
        val attempt = TaskQuizAttemptRow.new {
            planTaskId = EntityID(quizTask, PlanTaskTable); this.attemptNumber = attemptNumber; totalCount = 5; createdAt = OffsetDateTime.now()
        }
        val answers = (1..5).map { i ->
            val q = TaskQuizQuestionRow.new { this.attempt = attempt; questionText = "문제 $i" }
            val right = TaskQuizChoiceRow.new { question = q; choiceText = "정답"; isCorrect = true; explanation = "" }
            val wrong = TaskQuizChoiceRow.new { question = q; choiceText = "오답"; isCorrect = false; explanation = "" }
            q.id.value to (right.id.value to wrong.id.value)
        }
        Seeded(user.id.value, quizTask, otherIds, answers)
    }

    fun allWrong(s: Seeded) = s.answers.map { (q, c) -> TaskQuizAnswer(q, c.second) }
    fun timesOf(ids: Collection<Int>) = transaction(db) {
        ids.associateWith { id -> PlanTaskRow[id].let { "${it.startTime}~${it.endTime}" } }
    }
    val service = TaskQuizService(NoLlm, at1705)

    "불합격이라 재시도가 잡히면 오늘 아직 시작 안 한 미완료 태스크만 15분 뒤로 민다" {
        val s = seed(
            attemptNumber = 1,
            others = listOf(
                Triple("다음 공부", "17:10~17:50", false),
                Triple("그다음 공부", "18:00~18:30", false),
                Triple("진행 중", "17:00~17:30", false), // 17:05 현재 이미 시작 → 안 밀림
                Triple("완료함", "19:00~19:30", true)    // 완료 → 안 밀림
            )
        )

        val response = service.submitQuiz(s.userId, s.quizTaskId, allWrong(s))

        response.retryScheduled shouldBe true
        // "다음 공부" 는 17:25 로 밀리면 "진행 중"(~17:30)과 겹쳐서 그 뒤로
        response.shiftedTasks.map { "${it.taskName} ${it.startTime}~${it.endTime}" } shouldBe listOf(
            "다음 공부 17:30~18:10", "그다음 공부 18:15~18:45"
        )
        timesOf(s.otherTaskIds.values) shouldBe mapOf(
            s.otherTaskIds.getValue("다음 공부") to "17:30~18:10",
            s.otherTaskIds.getValue("그다음 공부") to "18:15~18:45",
            s.otherTaskIds.getValue("진행 중") to "17:00~17:30",
            s.otherTaskIds.getValue("완료함") to "19:00~19:30"
        )
    }

    "밀 때 학원 같은 불가능 시간은 건너뛴다" {
        val s = seed(1, listOf(Triple("다음 공부", "17:40~18:10", false)), academy = "18:00" to "20:00")

        val response = service.submitQuiz(s.userId, s.quizTaskId, allWrong(s))

        response.shiftedTasks.single().let { "${it.startTime}~${it.endTime}" } shouldBe "20:00~20:30"
    }

    "3번째에서도 떨어지면 더 재시도가 없으니 밀지 않는다" {
        val last = seed(3, listOf(Triple("다음 공부", "17:10~17:50", false)))
        val lastResponse = service.submitQuiz(last.userId, last.quizTaskId, allWrong(last))
        lastResponse.taskInvalidated shouldBe true
        lastResponse.shiftedTasks shouldBe emptyList()
        timesOf(last.otherTaskIds.values).values.single() shouldBe "17:10~17:50"
    }

    "통과하면 밀지 않는다" {
        val s = seed(1, listOf(Triple("다음 공부", "17:10~17:50", false)))
        val response = service.submitQuiz(s.userId, s.quizTaskId, s.answers.map { (q, c) -> TaskQuizAnswer(q, c.first) })
        response.passed shouldBe true
        response.shiftedTasks shouldBe emptyList()
    }
})

/** 제출 흐름은 LLM 을 쓰지 않는다 */
private object NoLlm : TaskQuizLlmClient(
    AppConfig(
        EnvironmentMode.DEV, "", "", "", 1, emptyList(), 0, "local", null, null, null, null, null,
        "", "", "", "", "", "", "", "", ""
    )
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
