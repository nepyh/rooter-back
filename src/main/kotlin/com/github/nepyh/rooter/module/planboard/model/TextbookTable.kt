package com.github.nepyh.rooter.module.planboard.model

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.datetime

object Textbooks : Table("textbooks") {
    val id = integer("id").autoIncrement()
    val subjectId = integer("subject_id") references Subjects.id
    val publisherId = integer("publisher_id").nullable() // publishers 모델 나중에, 지금은 FK 생략
    val title = varchar("title", 150)
    val fileUrl = varchar("file_url", 500).nullable()
    val aiStatus = varchar("ai_status", 20).default("pending")
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}