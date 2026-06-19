package com.github.nepyh.rooter.module.user.model

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

object StudentProfileTable : IntIdTable("student_profiles") {
    val user_id = reference("user_id", UserTable)
    val school_id = reference("school_id", SchoolTable)
    val grade = integer("grade")
    val classNumber = integer("class_number")
}

class StudentProfileRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<StudentProfileRow>(StudentProfileTable)

    var user_id by UserRow referencedOn StudentProfileTable.user_id
    var school_id by SchoolRow referencedOn StudentProfileTable.school_id
    var grade by StudentProfileTable.grade
    var classNumber by StudentProfileTable.classNumber
}