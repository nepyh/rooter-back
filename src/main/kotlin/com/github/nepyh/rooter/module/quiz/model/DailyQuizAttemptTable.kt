package com.github.nepyh.rooter.module.quiz.model

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object DailyQuizAttemptTable : IntIdTable("daily_quiz_attempts") {
    val userId = integer("user_id") // DDL에 있는 필수 유저 외래키 (DDL 상 FK 없음)
    val selectedChoiceId = reference("selected_choice_id", DailyQuizChoiceTable)
    val createdAt = timestampWithTimeZone("created_at") // DB default current_timestamp, 코드에서 명시적으로 세팅
}

class DailyQuizAttemptRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DailyQuizAttemptRow>(DailyQuizAttemptTable)

    var userId by DailyQuizAttemptTable.userId
    var selectedChoice by DailyQuizChoiceRow referencedOn DailyQuizAttemptTable.selectedChoiceId
    var createdAt by DailyQuizAttemptTable.createdAt
}
