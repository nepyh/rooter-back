package com.github.nepyh.rooter.module.quiz.model

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

object DailyQuizChoiceTable : IntIdTable("daily_quiz_choices") {
    val questionId = reference("question_id", DailyQuizQuestionTable)
    val choiceText = varchar("choice_text", 200)
    val isCorrect = bool("is_correct").default(false)
}

class DailyQuizChoiceRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DailyQuizChoiceRow>(DailyQuizChoiceTable)

    var question by DailyQuizQuestionRow referencedOn DailyQuizChoiceTable.questionId
    var choiceText by DailyQuizChoiceTable.choiceText
    var isCorrect by DailyQuizChoiceTable.isCorrect
}
