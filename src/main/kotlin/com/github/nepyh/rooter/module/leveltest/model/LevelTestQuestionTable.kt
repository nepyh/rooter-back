package com.github.nepyh.rooter.module.leveltest.model

import com.github.nepyh.rooter.module.planboard.model.SubjectRow
import com.github.nepyh.rooter.module.planboard.model.SubjectTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

// DDL 에 없는 테이블 (반영 필요, 별도 전달함): 실력 테스트 문항.
object LevelTestQuestionTable : IntIdTable("level_test_questions") {
    val attemptId = reference("attempt_id", LevelTestAttemptTable)
    val subjectId = reference("subject_id", SubjectTable)
    val questionText = text("question_text")
}

class LevelTestQuestionRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<LevelTestQuestionRow>(LevelTestQuestionTable)

    var attempt by LevelTestAttemptRow referencedOn LevelTestQuestionTable.attemptId
    var subject by SubjectRow referencedOn LevelTestQuestionTable.subjectId
    var questionText by LevelTestQuestionTable.questionText
}
