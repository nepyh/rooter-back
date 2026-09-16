package com.github.nepyh.rooter.module.planboard.model

import org.jetbrains.exposed.v1.core.Table

object Subjects : Table("subjects") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 30).uniqueIndex()

    override val primaryKey = PrimaryKey(id)
}