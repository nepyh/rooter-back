package com.github.nepyh.rooter.module.planboard.model

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

object ChapterTable : IntIdTable("chapters") {
    val textbookId = reference("textbook_id", TextbookTable)
    val parentId = integer("parent_id").nullable() // 자기참조(대단원-소단원), FK는 나중에
    val chapterName = varchar("chapter_name", 150)
    val chapterOrder = integer("chapter_order")
}

class ChapterRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ChapterRow>(ChapterTable)

    var textbook by TextbookRow referencedOn ChapterTable.textbookId
    var parentId by ChapterTable.parentId
    var chapterName by ChapterTable.chapterName
    var chapterOrder by ChapterTable.chapterOrder
}
