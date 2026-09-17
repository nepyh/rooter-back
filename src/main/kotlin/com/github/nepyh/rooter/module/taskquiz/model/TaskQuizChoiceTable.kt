package com.github.nepyh.rooter.module.taskquiz.model

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

// DDL 에 없는 테이블 (반영 필요, 별도 전달함): 완료 확인 퀴즈 보기.
object TaskQuizChoiceTable : IntIdTable("task_quiz_choices") {
    val questionId = reference("question_id", TaskQuizQuestionTable)
    val choiceText = varchar("choice_text", 200)
    val isCorrect = bool("is_correct").default(false)
    val explanation = text("explanation")
}

class TaskQuizChoiceRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<TaskQuizChoiceRow>(TaskQuizChoiceTable)

    var question by TaskQuizQuestionRow referencedOn TaskQuizChoiceTable.questionId
    var choiceText by TaskQuizChoiceTable.choiceText
    var isCorrect by TaskQuizChoiceTable.isCorrect
    var explanation by TaskQuizChoiceTable.explanation
}
