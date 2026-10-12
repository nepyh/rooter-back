package com.github.nepyh.rooter.module.review

import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizAttemptTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizChoiceTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizQuestionTable
import com.github.nepyh.rooter.module.review.dto.ChapterWrongRateResponse
import com.github.nepyh.rooter.module.review.dto.ReviewAnswerResponse
import com.github.nepyh.rooter.module.review.dto.ReviewChoiceResponse
import com.github.nepyh.rooter.module.review.dto.ReviewQuestionListResponse
import com.github.nepyh.rooter.module.review.dto.ReviewQuestionResponse
import com.github.nepyh.rooter.module.review.dto.ReviewReportResponse
import com.github.nepyh.rooter.module.review.exception.ReviewQuestionNotFoundException
import com.github.nepyh.rooter.module.review.exception.ReviewValidationException
import com.github.nepyh.rooter.module.review.model.ReviewChoiceRow
import com.github.nepyh.rooter.module.review.model.ReviewChoiceTable
import com.github.nepyh.rooter.module.review.model.ReviewQuestionRow
import com.github.nepyh.rooter.module.review.model.ReviewQuestionTable
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizAttemptTable
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizChoiceTable
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizQuestionTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import kotlin.math.roundToInt

const val REVIEW_PAGE_SIZE = 50

class ReviewService {

    /**
     * 아직 해결하지 않은 복습 문제를 최근에 들어온 것부터 limit 개씩. cursor 는 앞 페이지 응답의 nextCursor.
     * 문제를 맞히면 목록에서 빠지므로 offset 대신 id 커서로 넘겨, 앞 페이지에서 해결한 문제 때문에 다음 페이지가 밀리지 않게 한다.
     */
    fun listOpen(userId: Int, cursor: Int?, limit: Int = REVIEW_PAGE_SIZE): ReviewQuestionListResponse = transaction {
        val open = (ReviewQuestionTable.userId eq userId) and ReviewQuestionTable.clearedAt.isNull()

        val rows = ReviewQuestionRow.find { if (cursor == null) open else open and (ReviewQuestionTable.id less cursor) }
            .orderBy(ReviewQuestionTable.id to SortOrder.DESC)
            .limit(limit + 1)
            .toList()
        val page = rows.take(limit)

        val choicesByQuestion = ReviewChoiceRow.find { ReviewChoiceTable.reviewQuestionId inList page.map { it.id } }
            .orderBy(ReviewChoiceTable.id to SortOrder.ASC)
            .groupBy { it.reviewQuestion.id.value }

        ReviewQuestionListResponse(
            questions = page.map { row ->
                ReviewQuestionResponse(
                    id = row.id.value,
                    source = row.source.name,
                    chapterName = row.chapterName,
                    questionText = row.questionText,
                    choices = choicesByQuestion[row.id.value].orEmpty().map { ReviewChoiceResponse(it.id.value, it.choiceText) },
                    wrongCount = row.wrongCount,
                    createdAt = row.createdAt.toString()
                )
            },
            totalCount = ReviewQuestionTable.selectAll().where { open }.count().toInt(),
            nextCursor = if (rows.size > limit) page.last().id.value else null
        )
    }

    /**
     * 단원별 오답률 리포트. 일일 퀴즈 제출과 태스크 퀴즈 답변을 문항의 단원(chapter_name)으로 묶어 틀린 수 ÷ 푼 수를 낸다.
     * 단원을 모르는 문항(단원 기록 전에 만든 퀴즈, AI 가 범위 밖 단원을 쓴 문항)은 빼고 untaggedAnsweredCount 로만 센다.
     * 복습에서 다시 푼 기록은 넣지 않는다 (같은 문제를 맞힐 때까지 푸는 거라 오답률을 부풀린다).
     */
    fun chapterReport(userId: Int): ReviewReportResponse = transaction {
        // 일일 퀴즈: 제출 기록(학생이 고른 보기) → 문항의 단원. (단원, 맞았나) 목록
        val dailyAnswers = (DailyQuizAttemptTable innerJoin DailyQuizChoiceTable innerJoin DailyQuizQuestionTable)
            .select(DailyQuizQuestionTable.chapterName, DailyQuizChoiceTable.isCorrect)
            .where { DailyQuizAttemptTable.userId eq userId }
            .map { it[DailyQuizQuestionTable.chapterName] to it[DailyQuizChoiceTable.isCorrect] }

        // 태스크 퀴즈: 답한 문항만. 소유자 확인이 plan_tasks → daily_plans → plan_boards 조인이라 Table DSL
        val taskQuestions = (TaskQuizQuestionTable innerJoin TaskQuizAttemptTable innerJoin PlanTaskTable innerJoin DailyPlanTable innerJoin PlanBoardTable)
            .select(TaskQuizQuestionTable.chapterName, TaskQuizQuestionTable.selectedChoiceId)
            .where { (PlanBoardTable.userId eq userId) and TaskQuizQuestionTable.selectedChoiceId.isNotNull() }
            .map { it[TaskQuizQuestionTable.chapterName] to it[TaskQuizQuestionTable.selectedChoiceId]!! }
        val correctTaskChoiceIds = if (taskQuestions.isEmpty()) emptySet() else {
            TaskQuizChoiceTable.select(TaskQuizChoiceTable.id)
                .where { (TaskQuizChoiceTable.id inList taskQuestions.map { it.second }) and (TaskQuizChoiceTable.isCorrect eq true) }
                .map { it[TaskQuizChoiceTable.id].value }
                .toSet()
        }
        val taskAnswers = taskQuestions.map { (chapter, choiceId) -> chapter to (choiceId in correctTaskChoiceIds) }

        val answers = dailyAnswers + taskAnswers
        val openReviewByChapter = ReviewQuestionTable.select(ReviewQuestionTable.chapterName)
            .where { (ReviewQuestionTable.userId eq userId) and ReviewQuestionTable.clearedAt.isNull() and ReviewQuestionTable.chapterName.isNotNull() }
            .groupingBy { it[ReviewQuestionTable.chapterName]!! }
            .eachCount()

        val chapters = answers.mapNotNull { (chapter, correct) -> chapter?.let { it to correct } }
            .groupBy({ it.first }, { it.second })
            .map { (chapterName, results) ->
                val wrong = results.count { !it }
                ChapterWrongRateResponse(
                    chapterName = chapterName,
                    answeredCount = results.size,
                    wrongCount = wrong,
                    wrongRate = (wrong * 100.0 / results.size).roundToInt(),
                    openReviewCount = openReviewByChapter[chapterName] ?: 0
                )
            }
            .sortedWith(compareByDescending<ChapterWrongRateResponse> { it.wrongRate }.thenByDescending { it.wrongCount }.thenBy { it.chapterName })

        ReviewReportResponse(chapters = chapters, untaggedAnsweredCount = answers.count { it.first == null })
    }

    /** 복습 문제 하나에 답한다. 맞히면 목록에서 빠지고(cleared), 틀리면 그대로 남아 다시 풀 수 있다 */
    fun answer(userId: Int, reviewQuestionId: Int, selectedChoiceId: Int): ReviewAnswerResponse = transaction {
        val question = ReviewQuestionRow.findById(reviewQuestionId)
            ?.takeIf { it.userId.value == userId }
            ?: throw ReviewQuestionNotFoundException()
        if (question.clearedAt != null) throw ReviewValidationException.AlreadyClearedException()

        val choices = ReviewChoiceRow.find { ReviewChoiceTable.reviewQuestionId eq question.id }.toList()
        val selected = choices.find { it.id.value == selectedChoiceId }
            ?: throw ReviewValidationException.InvalidAnswerException()
        val correct = choices.first { it.isCorrect } // 복사할 때 정답 보기가 없는 문제는 넣지 않는다

        if (selected.isCorrect) {
            // 연달아 눌러 요청이 겹쳐도 한 번만 해결 처리되게, 아직 안 풀린 때만 바꾼다
            val cleared = ReviewQuestionTable.update({
                (ReviewQuestionTable.id eq question.id) and ReviewQuestionTable.clearedAt.isNull()
            }) { it[clearedAt] = OffsetDateTime.now() }
            if (cleared == 0) throw ReviewValidationException.AlreadyClearedException()
        } else {
            ReviewQuestionTable.update({ ReviewQuestionTable.id eq question.id }) {
                it[wrongCount] = wrongCount + 1
            }
        }

        ReviewAnswerResponse(
            questionId = question.id.value,
            isCorrect = selected.isCorrect,
            correctChoiceId = correct.id.value,
            reason = if (selected.isCorrect) null else selected.reason,
            explanation = question.explanation,
            cleared = selected.isCorrect
        )
    }
}
