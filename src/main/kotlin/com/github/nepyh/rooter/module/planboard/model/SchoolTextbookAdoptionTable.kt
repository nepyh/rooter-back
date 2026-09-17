package com.github.nepyh.rooter.module.planboard.model

import org.jetbrains.exposed.v1.core.Table

// DDL 에 없는 테이블 (반영 필요, 별도 전달함): "이 학교 이 학년은 이 교과서를 쓴다" 매핑.
// NICE API 는 학교가 채택한 교과서 정보를 제공하지 않아서, 데이터는 별도로 직접 채워 넣는다.
object SchoolTextbookAdoptions : Table("school_textbook_adoptions") {
    val id = integer("id").autoIncrement()
    val schoolId = char("school_id", 10)
    val grade = integer("grade")
    val subjectId = integer("subject_id") references Subjects.id
    val textbookId = integer("textbook_id") references Textbooks.id

    override val primaryKey = PrimaryKey(id)
}
