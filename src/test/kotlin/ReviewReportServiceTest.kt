import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizAttemptRow
import com.github.nepyh.rooter.module.quiz.model.DailyQuizAttemptTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizChoiceRow
import com.github.nepyh.rooter.module.quiz.model.DailyQuizChoiceTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizQuestionRow
import com.github.nepyh.rooter.module.quiz.model.DailyQuizQuestionTable
import com.github.nepyh.rooter.module.review.ReviewChoiceDraft
import com.github.nepyh.rooter.module.review.ReviewService
import com.github.nepyh.rooter.module.review.addCustomReviewQuestion
import com.github.nepyh.rooter.module.review.dto.ChapterWrongRateResponse
import com.github.nepyh.rooter.module.review.model.ReviewChoiceTable
import com.github.nepyh.rooter.module.review.model.ReviewQuestionTable
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizAttemptRow
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizAttemptTable
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizChoiceRow
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizChoiceTable
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizQuestionRow
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizQuestionTable
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * 단원별 오답률 리포트 통합 테스트 (로컬 PostgreSQL 필요). 퀴즈·답변 기록은 DAO 로 직접 넣는다.
 *
 * 접속 정보는 환경변수로 오버라이드 가능: TEST_JDBC_URL / TEST_DB_USER / TEST_DB_PASSWORD
 */
class ReviewReportServiceTest : StringSpec({

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
                "review_choices", "review_questions", "task_quiz_choices", "task_quiz_questions", "task_quiz_attempts",
                "daily_quiz_attempts", "daily_quiz_choices", "daily_quiz_questions", "plan_tasks", "daily_plans", "plan_boards", "users"
            ).forEach { exec("DROP TABLE IF EXISTS $it CASCADE") }
            SchemaUtils.create(
                UserTable, PlanBoardTable, DailyPlanTable, PlanTaskTable,
                DailyQuizQuestionTable, DailyQuizChoiceTable, DailyQuizAttemptTable,
                TaskQuizAttemptTable, TaskQuizQuestionTable, TaskQuizChoiceTable,
                ReviewQuestionTable, ReviewChoiceTable
            )
        }
    }

    beforeEach {
        transaction(db) {
            listOf(
                ReviewChoiceTable, ReviewQuestionTable, TaskQuizChoiceTable, TaskQuizQuestionTable, TaskQuizAttemptTable,
                DailyQuizAttemptTable, DailyQuizChoiceTable, DailyQuizQuestionTable, PlanTaskTable, DailyPlanTable, PlanBoardTable, UserTable
            ).forEach { it.deleteAll() }
        }
    }

    val day = LocalDate.of(2026, 10, 12)

    /** 유저 하나와 그 유저의 일일 계획·할일을 만든다. (userId, dailyPlan, planTask) */
    fun seedUser(email: String) = transaction(db) {
        val user = UserRow.new { this.email = email; username = "t"; password = "x"; createdAt = OffsetDateTime.now() }
        val board = PlanBoardRow.new { this.user = user; title = "보드"; startDate = day; endDate = day; createdAt = OffsetDateTime.now() }
        val dailyPlan = DailyPlanRow.new { planBoard = board; planDate = day }
        val task = PlanTaskRow.new {
            this.dailyPlan = dailyPlan; taskName = "할일"; startTime = LocalTime.of(16, 0); endTime = LocalTime.of(16, 30); estimatedMinutes = 30
        }
        Triple(user.id.value, dailyPlan.id.value, task.id.value)
    }

    /** 일일 퀴즈 문항 하나를 만들고 정답(correct=true) 또는 오답을 골라 제출한 기록을 남긴다 */
    fun answerDaily(userId: Int, dailyPlanId: Int, chapter: String?, correct: Boolean) = transaction(db) {
        val question = DailyQuizQuestionRow.new { dailyPlan = DailyPlanRow[dailyPlanId]; questionText = "일일"; chapterName = chapter }
        val right = DailyQuizChoiceRow.new { this.question = question; choiceText = "정답"; isCorrect = true }
        val wrong = DailyQuizChoiceRow.new { this.question = question; choiceText = "오답"; isCorrect = false }
        DailyQuizAttemptRow.new { this.userId = userId; selectedChoice = if (correct) right else wrong; createdAt = OffsetDateTime.now() }
    }

    /** 태스크 퀴즈 문항 하나. correct=null 이면 아직 답하지 않은 문항 */
    fun answerTask(planTaskId: Int, chapter: String?, correct: Boolean?) = transaction(db) {
        val attempt = TaskQuizAttemptRow.new {
            this.planTaskId = EntityID(planTaskId, PlanTaskTable)
            attemptNumber = (TaskQuizAttemptRow.count() + 1).toInt(); totalCount = 1; createdAt = OffsetDateTime.now()
        }
        val question = TaskQuizQuestionRow.new { this.attempt = attempt; questionText = "태스크"; chapterName = chapter }
        val right = TaskQuizChoiceRow.new { this.question = question; choiceText = "정답"; isCorrect = true; explanation = "풀이" }
        val wrong = TaskQuizChoiceRow.new { this.question = question; choiceText = "오답"; isCorrect = false; explanation = "이유" }
        if (correct != null) question.selectedChoiceId = (if (correct) right else wrong).id.value
    }

    fun addOpenReview(userId: Int, chapter: String) = transaction(db) {
        addCustomReviewQuestion(userId, chapter, "맞춤", null, listOf(ReviewChoiceDraft("정답", true), ReviewChoiceDraft("오답", false)))
    }

    "일일·태스크 퀴즈를 단원별로 묶어 틀린 수 ÷ 푼 수를 오답률 높은 순으로 내고, 단원 미상·안 푼 문항·남의 기록은 뺀다" {
        val (me, myPlan, myTask) = seedUser("me@test.com")
        answerDaily(me, myPlan, "일차방정식", correct = false)
        answerDaily(me, myPlan, "일차방정식", correct = true)
        answerDaily(me, myPlan, "소인수분해", correct = true)
        answerDaily(me, myPlan, null, correct = false) // 단원 미상
        answerTask(myTask, "일차방정식", correct = false)
        answerTask(myTask, "소인수분해", correct = true)
        answerTask(myTask, "소인수분해", correct = null) // 아직 안 답함
        answerTask(myTask, null, correct = true) // 단원 미상
        addOpenReview(me, "일차방정식")
        addOpenReview(me, "일차방정식")

        val (other, otherPlan, otherTask) = seedUser("other@test.com")
        answerDaily(other, otherPlan, "소인수분해", correct = false)
        answerTask(otherTask, "소인수분해", correct = false)
        addOpenReview(other, "소인수분해")

        val report = ReviewService().chapterReport(me)

        report.chapters shouldBe listOf(
            ChapterWrongRateResponse("일차방정식", answeredCount = 3, wrongCount = 2, wrongRate = 67, openReviewCount = 2),
            ChapterWrongRateResponse("소인수분해", answeredCount = 2, wrongCount = 0, wrongRate = 0, openReviewCount = 0)
        )
        report.untaggedAnsweredCount shouldBe 2
    }

    "오답률이 같으면 틀린 수가 많은 단원이 먼저 나오고, 푼 퀴즈가 없으면 빈 배열" {
        val (me, myPlan, _) = seedUser("tie@test.com")
        ReviewService().chapterReport(me).chapters shouldBe emptyList()

        answerDaily(me, myPlan, "가 단원", correct = false)
        answerDaily(me, myPlan, "나 단원", correct = false)
        answerDaily(me, myPlan, "나 단원", correct = false)

        ReviewService().chapterReport(me).chapters.map { it.chapterName to it.wrongRate } shouldBe listOf("나 단원" to 100, "가 단원" to 100)
    }
})

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
