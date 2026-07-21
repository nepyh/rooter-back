package com.github.nepyh.rooter.module.planboard.model

import org.jetbrains.exposed.v1.core.Table

object Chapters : Table("chapters") {
    val id = integer("id").autoIncrement()
    val textbookId = integer("textbook_id") references Textbooks.id
    val parentId = integer("parent_id").nullable() // 자기참조(대단원-소단원), FK는 나중에
    val chapterName = varchar("chapter_name", 150)
    val chapterOrder = integer("chapter_order")

    override val primaryKey = PrimaryKey(id)
}