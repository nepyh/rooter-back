package com.github.nepyh.rooter.module.review

import com.github.nepyh.rooter.module.review.model.ReviewChoiceTable
import com.github.nepyh.rooter.module.review.model.ReviewQuestionTable
import com.github.nepyh.rooter.module.review.model.ReviewSource
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.insertIgnoreAndGetId
import java.time.OffsetDateTime

/** 복습 목록에 넣을 보기 하나. reason 은 이 오답 보기를 골랐을 때 보여줄 짧은 이유 */
data class ReviewChoiceDraft(val choiceText: String, val isCorrect: Boolean, val reason: String? = null)

/**
 * 퀴즈에서 틀린 문제를 복습 목록에 복사한다. 퀴즈 채점 트랜잭션 안에서 호출한다.
 * 같은 원본 문항은 한 번만 들어가고(uq_review_questions_source), 정답 보기가 없는 문제는 복습에서 맞힐 수 없으니 넣지 않는다.
 * 보기 순서는 원래 퀴즈와 다르게 다시 섞어 위치로 외워 맞히지 않게 한다.
 */
fun copyWrongQuestionToReview(
    userId: Int,
    source: ReviewSource,
    sourceQuestionId: Int,
    chapterName: String?,
    questionText: String,
    explanation: String?,
    choices: List<ReviewChoiceDraft>
) {
    if (choices.none { it.isCorrect }) return

    val reviewQuestionId = ReviewQuestionTable.insertIgnoreAndGetId {
        it[this.userId] = userId
        it[sourceType] = source
        it[this.sourceQuestionId] = sourceQuestionId
        it[this.chapterName] = chapterName
        it[this.questionText] = questionText
        it[this.explanation] = explanation?.ifBlank { null }
        it[createdAt] = OffsetDateTime.now()
    } ?: return // 이미 복사된 문항

    insertShuffledChoices(reviewQuestionId, choices)
}

/**
 * 약점 분석으로 만든 맞춤 문제를 복습 목록에 넣는다 (source=CUSTOM, 원본 문항 없음). 트랜잭션 안에서 호출한다.
 * 정답 보기가 없는 문제는 복습에서 맞힐 수 없으니 넣지 않고, 넣었으면 true.
 */
fun addCustomReviewQuestion(
    userId: Int,
    chapterName: String,
    questionText: String,
    explanation: String?,
    choices: List<ReviewChoiceDraft>
): Boolean {
    if (choices.none { it.isCorrect }) return false

    val reviewQuestionId = ReviewQuestionTable.insertAndGetId {
        it[this.userId] = userId
        it[sourceType] = ReviewSource.CUSTOM
        it[this.chapterName] = chapterName
        it[this.questionText] = questionText
        it[this.explanation] = explanation?.ifBlank { null }
        it[createdAt] = OffsetDateTime.now()
    }
    insertShuffledChoices(reviewQuestionId, choices)
    return true
}

private fun insertShuffledChoices(reviewQuestionId: EntityID<Int>, choices: List<ReviewChoiceDraft>) {
    choices.shuffled().forEach { choice ->
        ReviewChoiceTable.insert {
            it[this.reviewQuestionId] = reviewQuestionId
            it[choiceText] = choice.choiceText
            it[isCorrect] = choice.isCorrect
            it[reason] = if (choice.isCorrect) null else choice.reason?.ifBlank { null }
        }
    }
}
