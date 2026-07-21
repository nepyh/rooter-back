package com.github.nepyh.rooter.module.planboard.model

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.datetime

object PlanBoards : Table("plan_boards") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id") // DDL에 있는 필수 유저 외래키
    val title = varchar("title", 100)
    val startDate = date("start_date") // DDL 스펙: date
    val endDate = date("end_date")     // DDL 스펙: date
    val examDate = date("exam_date").nullable() // 실제 시험 날짜 (end_date 와 별개일 수 있음)
    val isCramMode = bool("is_cram_mode").default(false)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}
