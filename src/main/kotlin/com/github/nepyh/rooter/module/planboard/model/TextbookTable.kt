package com.github.nepyh.rooter.module.planboard.model

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object TextbookTable : IntIdTable("textbooks") {
    val subjectId = reference("subject_id", SubjectTable)
    val publisherId = integer("publisher_id").nullable() // publishers 모델 나중에, 지금은 FK 생략
    val title = varchar("title", 150)
    val coverImageKey = varchar("cover_image_key", 255).nullable()
    val aiStatus = varchar("ai_status", 20).default("pending")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone) // DDL: timestamptz
}

class TextbookRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<TextbookRow>(TextbookTable)

    var subject by SubjectRow referencedOn TextbookTable.subjectId
    var publisherId by TextbookTable.publisherId
    var title by TextbookTable.title
    var coverImageKey by TextbookTable.coverImageKey
    var aiStatus by TextbookTable.aiStatus
    var createdAt by TextbookTable.createdAt
}
