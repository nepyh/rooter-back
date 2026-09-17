package com.github.nepyh.rooter.module.planboard.model

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

// DDL 에 없는 테이블 (반영 필요, 별도 전달함): "이 학교 이 학년은 이 교과서를 쓴다" 매핑.
// NICE API 는 학교가 채택한 교과서 정보를 제공하지 않아서, 데이터는 별도로 직접 채워 넣는다.
object SchoolTextbookAdoptionTable : IntIdTable("school_textbook_adoptions") {
    val schoolId = char("school_id", 10)
    val grade = integer("grade")
    val subjectId = reference("subject_id", SubjectTable)
    val textbookId = reference("textbook_id", TextbookTable)
}

class SchoolTextbookAdoptionRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<SchoolTextbookAdoptionRow>(SchoolTextbookAdoptionTable)

    var schoolId by SchoolTextbookAdoptionTable.schoolId
    var grade by SchoolTextbookAdoptionTable.grade
    var subject by SubjectRow referencedOn SchoolTextbookAdoptionTable.subjectId
    var textbook by TextbookRow referencedOn SchoolTextbookAdoptionTable.textbookId
}
