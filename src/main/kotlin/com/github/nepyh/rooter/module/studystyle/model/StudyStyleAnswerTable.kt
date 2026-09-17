package com.github.nepyh.rooter.module.studystyle.model

import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

object StudyStyleAnswerTable : IntIdTable("study_style_answers") {
    val userId = reference("user_id", UserTable)
    val questionNumber = short("question_number") // 1~7
    val answerOption = short("answer_option") // 1~3: 실제 보기, 4: "모르겠어요"
}

class StudyStyleAnswerRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<StudyStyleAnswerRow>(StudyStyleAnswerTable)

    var user by UserRow referencedOn StudyStyleAnswerTable.userId
    var questionNumber by StudyStyleAnswerTable.questionNumber
    var answerOption by StudyStyleAnswerTable.answerOption
}
