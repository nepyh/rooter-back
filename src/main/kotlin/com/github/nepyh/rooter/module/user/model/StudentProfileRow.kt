package com.github.nepyh.rooter.module.user.model

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

object StudentProfileTable : IntIdTable("student_profiles") {
    val user = reference("user_id", UserTable)
    val school = char("school_id", 10)
    val grade = integer("grade")
    val classNumber = integer("class_number")
}

class StudentProfileRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<StudentProfileRow>(StudentProfileTable)

    var user by UserRow referencedOn StudentProfileTable.user
    var school by StudentProfileTable.school
    var grade by StudentProfileTable.grade
    var classNumber by StudentProfileTable.classNumber
}