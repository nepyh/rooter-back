package com.github.nepyh.rooter.module.studystyle.model

import com.github.nepyh.rooter.module.user.model.UserTable
import org.jetbrains.exposed.v1.core.Table

object StudyStyleAnswers : Table("study_style_answers") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(UserTable.id)
    val questionNumber = short("question_number") // 1~7
    val answerOption = short("answer_option") // 1~3: 실제 보기, 4: "모르겠어요"

    override val primaryKey = PrimaryKey(id)
}
