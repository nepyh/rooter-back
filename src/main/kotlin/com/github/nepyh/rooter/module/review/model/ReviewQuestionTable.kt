package com.github.nepyh.rooter.module.review.model

import com.github.nepyh.rooter.module.user.model.UserTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/** 복습 문제의 출처 */
enum class ReviewSource {
    DAILY_QUIZ, // 일일 퀴즈에서 틀린 문제
    TASK_QUIZ, // 태스크 완료 퀴즈에서 틀린 문제
    CUSTOM // 약점 분석으로 만든 맞춤 문제
}

// 복습 목록. 퀴즈에서 틀린 문제를 복사해 두고, 복습에서 맞히면 cleared_at 을 채워 목록에서 뺀다 (rooter-ddl review_questions)
object ReviewQuestionTable : IntIdTable("review_questions") {
    val userId = reference("user_id", UserTable, onDelete = ReferenceOption.CASCADE)
    val sourceType = enumerationByName("source", 20, ReviewSource::class) // ColumnSet.source 와 이름이 겹쳐 sourceType
    // 원본 문항 id (DAILY_QUIZ = daily_quiz_questions.id, TASK_QUIZ = task_quiz_questions.id, CUSTOM = null). 원본이 지워져도 남도록 FK 없음
    val sourceQuestionId = integer("source_question_id").nullable()
    val chapterName = varchar("chapter_name", 150).nullable() // 문제의 단원. 맞춤 문제는 약점 단원, 틀린 문제 복사분은 원본 문항의 단원 (없으면 null)
    val questionText = text("question_text")
    val explanation = text("explanation").nullable() // 자세한 풀이 (예전 일일 퀴즈는 null)
    val wrongCount = integer("wrong_count").default(0) // 복습에서 틀린 횟수
    val createdAt = timestampWithTimeZone("created_at")
    val clearedAt = timestampWithTimeZone("cleared_at").nullable() // 복습에서 맞힌 시각. null = 아직 목록에 있음

    init {
        // DDL 의 uq_review_questions_source 와 동일 — 같은 원본 문항을 두 번 복사하지 않게
        uniqueIndex("uq_review_questions_source", sourceType, sourceQuestionId)
    }
}

class ReviewQuestionRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ReviewQuestionRow>(ReviewQuestionTable)

    var userId by ReviewQuestionTable.userId
    var source by ReviewQuestionTable.sourceType
    var sourceQuestionId by ReviewQuestionTable.sourceQuestionId
    var chapterName by ReviewQuestionTable.chapterName
    var questionText by ReviewQuestionTable.questionText
    var explanation by ReviewQuestionTable.explanation
    var wrongCount by ReviewQuestionTable.wrongCount
    var createdAt by ReviewQuestionTable.createdAt
    var clearedAt by ReviewQuestionTable.clearedAt
}
