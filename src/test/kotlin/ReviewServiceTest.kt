import com.github.nepyh.rooter.module.review.ReviewChoiceDraft
import com.github.nepyh.rooter.module.review.ReviewService
import com.github.nepyh.rooter.module.review.copyWrongQuestionToReview
import com.github.nepyh.rooter.module.review.exception.ReviewQuestionNotFoundException
import com.github.nepyh.rooter.module.review.exception.ReviewValidationException
import com.github.nepyh.rooter.module.review.model.ReviewChoiceTable
import com.github.nepyh.rooter.module.review.model.ReviewQuestionRow
import com.github.nepyh.rooter.module.review.model.ReviewQuestionTable
import com.github.nepyh.rooter.module.review.model.ReviewSource
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import java.sql.SQLException
import java.time.OffsetDateTime

/**
 * 복습 목록 조회·답하기 통합 테스트 (로컬 PostgreSQL 필요). 틀린 문제 복사는 [copyWrongQuestionToReview] 로 직접 넣는다.
 *
 * 접속 정보는 환경변수로 오버라이드 가능: TEST_JDBC_URL / TEST_DB_USER / TEST_DB_PASSWORD
 */
class ReviewServiceTest : StringSpec({

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
            listOf("review_choices", "review_questions", "users").forEach { exec("DROP TABLE IF EXISTS $it CASCADE") }
            SchemaUtils.create(UserTable, ReviewQuestionTable, ReviewChoiceTable)
        }
    }

    beforeEach {
        transaction(db) {
            ReviewChoiceTable.deleteAll()
            ReviewQuestionTable.deleteAll()
            UserTable.deleteAll()
        }
    }

    val service = ReviewService()

    fun seedUser(email: String): Int = transaction(db) {
        UserRow.new { this.email = email; username = "t"; password = "x"; createdAt = OffsetDateTime.now() }.id.value
    }

    /** 원본 문항 id 가 sourceQuestionId 인 틀린 문제를 복습 목록에 넣는다. 정답은 "A", 오답 "B" 에는 짧은 이유가 있다 */
    fun seedWrong(userId: Int, sourceQuestionId: Int, source: ReviewSource = ReviewSource.TASK_QUIZ) = transaction(db) {
        copyWrongQuestionToReview(
            userId = userId,
            source = source,
            sourceQuestionId = sourceQuestionId,
            questionText = "문제 $sourceQuestionId",
            explanation = "풀이 $sourceQuestionId",
            choices = listOf(
                ReviewChoiceDraft("A", isCorrect = true, reason = "정답 보기에는 이유를 저장하지 않음"),
                ReviewChoiceDraft("B", isCorrect = false, reason = "B 가 틀린 이유"),
                ReviewChoiceDraft("C", isCorrect = false)
            )
        )
    }

    fun reviewQuestionCount(): Long = transaction(db) { ReviewQuestionTable.selectAll().count() }

    "listOpen: 최근 문제부터 limit 개씩, nextCursor 로 다음 페이지를 받고 마지막 페이지는 nextCursor=null" {
        val userId = seedUser("review-page@test.com")
        (1..5).forEach { seedWrong(userId, it) }

        val first = service.listOpen(userId, cursor = null, limit = 2)
        val second = service.listOpen(userId, cursor = first.nextCursor, limit = 2)
        val last = service.listOpen(userId, cursor = second.nextCursor, limit = 2)

        first.questions.map { it.questionText } shouldBe listOf("문제 5", "문제 4")
        second.questions.map { it.questionText } shouldBe listOf("문제 3", "문제 2")
        last.questions.map { it.questionText } shouldBe listOf("문제 1")
        last.nextCursor shouldBe null
        listOf(first.totalCount, second.totalCount, last.totalCount) shouldBe listOf(5, 5, 5)
    }

    "listOpen: 앞 페이지에서 문제를 맞혀 빠져도 다음 페이지가 밀리거나 겹치지 않는다" {
        val userId = seedUser("review-shift@test.com")
        (1..4).forEach { seedWrong(userId, it) }

        val first = service.listOpen(userId, cursor = null, limit = 2)
        first.questions.forEach { q -> service.answer(userId, q.id, q.choices.first { it.choiceText == "A" }.id) }
        val second = service.listOpen(userId, cursor = first.nextCursor, limit = 2)

        second.questions.map { it.questionText } shouldBe listOf("문제 2", "문제 1")
        second.totalCount shouldBe 2
    }

    "listOpen: 다른 사람의 복습 문제는 보이지 않는다" {
        val me = seedUser("review-me@test.com")
        val other = seedUser("review-other@test.com")
        seedWrong(other, 1)

        service.listOpen(me, cursor = null).let { listOf(it.totalCount, it.questions.size) } shouldBe listOf(0, 0)
    }

    "answer: 틀리면 목록에 남고 wrongCount 가 늘며, 고른 보기의 이유와 풀이를 준다" {
        val userId = seedUser("review-wrong@test.com")
        seedWrong(userId, 1)
        val question = service.listOpen(userId, cursor = null).questions.single()

        val result = service.answer(userId, question.id, question.choices.first { it.choiceText == "B" }.id)

        listOf(result.isCorrect, result.cleared, result.reason, result.explanation) shouldBe listOf(false, false, "B 가 틀린 이유", "풀이 1")
        result.correctChoiceId shouldBe question.choices.first { it.choiceText == "A" }.id
        service.listOpen(userId, cursor = null).questions.single().wrongCount shouldBe 1
        // 이유가 없는 오답 보기는 reason=null
        service.answer(userId, question.id, question.choices.first { it.choiceText == "C" }.id).reason shouldBe null
    }

    "answer: 맞히면 바로 목록에서 빠지고(cleared), 다시 답하면 REVIEW_ALREADY_CLEARED" {
        val userId = seedUser("review-clear@test.com")
        seedWrong(userId, 1)
        val question = service.listOpen(userId, cursor = null).questions.single()
        val correctId = question.choices.first { it.choiceText == "A" }.id

        val result = service.answer(userId, question.id, correctId)

        listOf(result.isCorrect, result.cleared, result.reason) shouldBe listOf(true, true, null)
        service.listOpen(userId, cursor = null).totalCount shouldBe 0
        transaction(db) { ReviewQuestionRow[question.id].clearedAt != null } shouldBe true // 지우지 않고 해결 시각만 남긴다
        shouldThrow<ReviewValidationException.AlreadyClearedException> { service.answer(userId, question.id, correctId) }
    }

    "answer: 남의 문제나 없는 문제는 REVIEW_QUESTION_NOT_FOUND, 다른 문제의 보기는 REVIEW_INVALID_ANSWER" {
        val me = seedUser("review-owner@test.com")
        val other = seedUser("review-intruder@test.com")
        seedWrong(me, 1)
        seedWrong(me, 2)
        val (q2, q1) = service.listOpen(me, cursor = null).questions

        shouldThrow<ReviewQuestionNotFoundException> { service.answer(other, q1.id, q1.choices.first().id) }
        shouldThrow<ReviewQuestionNotFoundException> { service.answer(me, 999_999, q1.choices.first().id) }
        shouldThrow<ReviewValidationException.InvalidAnswerException> { service.answer(me, q1.id, q2.choices.first().id) }
    }

    "copyWrongQuestionToReview: 같은 원본 문항은 한 번만 들어가고, 정답 보기가 없는 문제는 넣지 않는다" {
        val userId = seedUser("review-copy@test.com")
        seedWrong(userId, 1)
        seedWrong(userId, 1)
        seedWrong(userId, 1, source = ReviewSource.DAILY_QUIZ) // 출처가 다르면 다른 원본 문항
        transaction(db) {
            copyWrongQuestionToReview(userId, ReviewSource.TASK_QUIZ, 2, "정답 없는 문제", null, listOf(ReviewChoiceDraft("A", false)))
        }

        reviewQuestionCount() shouldBe 2L
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
