package com.github.nepyh.rooter.module.leveltest.model

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

// DDL 에 없는 테이블 (반영 필요, 별도 전달함): 실력 테스트 문항의 보기.
object LevelTestChoiceTable : IntIdTable("level_test_choices") {
    val questionId = reference("question_id", LevelTestQuestionTable)
    val choiceText = varchar("choice_text", 200)
    val isCorrect = bool("is_correct").default(false)
    val explanation = text("explanation")
}

class LevelTestChoiceRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<LevelTestChoiceRow>(LevelTestChoiceTable)

    var question by LevelTestQuestionRow referencedOn LevelTestChoiceTable.questionId
    var choiceText by LevelTestChoiceTable.choiceText
    var isCorrect by LevelTestChoiceTable.isCorrect
    var explanation by LevelTestChoiceTable.explanation
}
