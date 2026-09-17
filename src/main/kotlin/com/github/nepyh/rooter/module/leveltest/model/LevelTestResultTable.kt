package com.github.nepyh.rooter.module.leveltest.model

import com.github.nepyh.rooter.module.planboard.model.SubjectRow
import com.github.nepyh.rooter.module.planboard.model.SubjectTable
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

// DDL 에 이미 존재하는 테이블 (feature/everything-ddl 기준): 과목별 최종 점수만 저장, 등급(상/중/하)은
// score 로부터 매번 계산하는 파생값이라 컬럼으로 저장하지 않음.
object LevelTestResultTable : IntIdTable("level_test_results") {
    val userId = reference("user_id", UserTable)
    val subjectId = reference("subject_id", SubjectTable)
    val score = integer("score") // 0~100 정답률(%). 시도마다 문항 수가 달라질 수 있어 원시 정답 개수가 아닌 비율로 저장
    val createdAt = timestampWithTimeZone("created_at")
}

class LevelTestResultRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<LevelTestResultRow>(LevelTestResultTable)

    var user by UserRow referencedOn LevelTestResultTable.userId
    var subject by SubjectRow referencedOn LevelTestResultTable.subjectId
    var score by LevelTestResultTable.score
    var createdAt by LevelTestResultTable.createdAt
}
