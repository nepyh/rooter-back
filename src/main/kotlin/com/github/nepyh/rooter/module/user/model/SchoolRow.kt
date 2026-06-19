package com.github.nepyh.rooter.module.user.model

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

object SchoolTable : IntIdTable("schools") {
    val name = varchar("name", 30).uniqueIndex()
}

class SchoolRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<SchoolRow>(SchoolTable)

    var name by SchoolTable.name
}
