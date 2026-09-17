package com.github.nepyh.rooter.module.planboard.model

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

object SubjectTable : IntIdTable("subjects") {
    val name = varchar("name", 30).uniqueIndex()
}

class SubjectRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<SubjectRow>(SubjectTable)

    var name by SubjectTable.name
}
