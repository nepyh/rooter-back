package com.github.nepyh.rooter.module.planboard.model

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.datetime

object TextbookTable : IntIdTable("textbooks") {
    val subjectId = reference("subject_id", SubjectTable)
    val publisherId = integer("publisher_id").nullable() // publishers 모델 나중에, 지금은 FK 생략
    val title = varchar("title", 150)
    val fileUrl = varchar("file_url", 500).nullable()
    val aiStatus = varchar("ai_status", 20).default("pending")
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
}

class TextbookRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<TextbookRow>(TextbookTable)

    var subject by SubjectRow referencedOn TextbookTable.subjectId
    var publisherId by TextbookTable.publisherId
    var title by TextbookTable.title
    var fileUrl by TextbookTable.fileUrl
    var aiStatus by TextbookTable.aiStatus
    var createdAt by TextbookTable.createdAt
}
