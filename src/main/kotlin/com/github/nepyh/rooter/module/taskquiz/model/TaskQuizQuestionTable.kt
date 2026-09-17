package com.github.nepyh.rooter.module.taskquiz.model

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

// DDL 에 없는 테이블 (반영 필요, 별도 전달함): 완료 확인 퀴즈 문항.
object TaskQuizQuestionTable : IntIdTable("task_quiz_questions") {
    val attemptId = reference("attempt_id", TaskQuizAttemptTable)
    val questionText = text("question_text")
}

class TaskQuizQuestionRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<TaskQuizQuestionRow>(TaskQuizQuestionTable)

    var attempt by TaskQuizAttemptRow referencedOn TaskQuizQuestionTable.attemptId
    var questionText by TaskQuizQuestionTable.questionText
}
