package com.github.nepyh.rooter.module.review.model

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

// 복습 문제의 보기 (rooter-ddl review_choices)
object ReviewChoiceTable : IntIdTable("review_choices") {
    val reviewQuestionId = reference("review_question_id", ReviewQuestionTable, onDelete = ReferenceOption.CASCADE)
    val choiceText = varchar("choice_text", 200)
    val isCorrect = bool("is_correct").default(false)
    val reason = text("reason").nullable() // 이 오답 보기를 골랐을 때 보여줄 짧은 이유. 정답 보기·일일 퀴즈 복사분은 null
}

class ReviewChoiceRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ReviewChoiceRow>(ReviewChoiceTable)

    var reviewQuestion by ReviewQuestionRow referencedOn ReviewChoiceTable.reviewQuestionId
    var choiceText by ReviewChoiceTable.choiceText
    var isCorrect by ReviewChoiceTable.isCorrect
    var reason by ReviewChoiceTable.reason
}
