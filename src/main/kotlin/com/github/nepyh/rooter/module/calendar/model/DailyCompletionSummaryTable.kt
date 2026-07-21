package com.github.nepyh.rooter.module.calendar.model

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date

object DailyCompletionSummary : Table("daily_completion_summary") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id")
    val summaryDate = date("summary_date")
    val completionRate = decimal("completion_rate", 5, 2)

    override val primaryKey = PrimaryKey(id)
}
