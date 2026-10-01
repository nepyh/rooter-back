package com.github.nepyh.rooter.module.planboard.model

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

object PublisherTable : IntIdTable("publishers") {
    val name = varchar("name", 50).uniqueIndex()
}

class PublisherRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<PublisherRow>(PublisherTable)

    var name by PublisherTable.name
}
