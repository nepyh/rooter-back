package com.github.nepyh.rooter.module.planboard.model

import org.jetbrains.exposed.v1.core.Table

object PlanSubjects : Table("plan_subjects") {
    val id = integer("id").autoIncrement()
    val planBoardId = integer("plan_board_id") references PlanBoards.id
    val textbookId = integer("textbook_id") references Textbooks.id
    val startChapterId = integer("start_chapter_id") references Chapters.id
    val endChapterId = integer("end_chapter_id") references Chapters.id
    val customRangeText = text("custom_range_text").nullable()

    override val primaryKey = PrimaryKey(id)
}