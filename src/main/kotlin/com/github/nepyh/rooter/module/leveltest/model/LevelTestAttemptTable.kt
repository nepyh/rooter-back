package com.github.nepyh.rooter.module.leveltest.model

import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

// DDL 에 없는 테이블: 생성-제출 2단계 흐름을 위해 시도(attempt)를 저장할 곳이 필요해서 추가.
// rooter-ddl 에는 최종 채점 결과를 담는 level_test_results 만 존재함 (반영 필요, 별도 전달함).
object LevelTestAttemptTable : IntIdTable("level_test_attempts") {
    val userId = reference("user_id", UserTable)
    val grade = integer("grade")
    val referenceGradeLabel = varchar("reference_grade_label", 30)
    val isSubmitted = bool("is_submitted").default(false)
    val createdAt = timestampWithTimeZone("created_at")
}

class LevelTestAttemptRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<LevelTestAttemptRow>(LevelTestAttemptTable)

    var user by UserRow referencedOn LevelTestAttemptTable.userId
    var grade by LevelTestAttemptTable.grade
    var referenceGradeLabel by LevelTestAttemptTable.referenceGradeLabel
    var isSubmitted by LevelTestAttemptTable.isSubmitted
    var createdAt by LevelTestAttemptTable.createdAt
}
