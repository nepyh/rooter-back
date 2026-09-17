package com.github.nepyh.rooter.module.planboard.model

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

object PlanSubjectTable : IntIdTable("plan_subjects") {
    // DDL 의 fk_plan_subjects_board 와 동일 (on delete cascade)
    val planBoardId = reference("plan_board_id", PlanBoardTable, onDelete = ReferenceOption.CASCADE)
    val textbookId = reference("textbook_id", TextbookTable)
    val startChapterId = reference("start_chapter_id", ChapterTable)
    val endChapterId = reference("end_chapter_id", ChapterTable)
    val customRangeText = text("custom_range_text").nullable()
}

class PlanSubjectRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<PlanSubjectRow>(PlanSubjectTable)

    var planBoard by PlanBoardRow referencedOn PlanSubjectTable.planBoardId
    var textbook by TextbookRow referencedOn PlanSubjectTable.textbookId
    var startChapter by ChapterRow referencedOn PlanSubjectTable.startChapterId
    var endChapter by ChapterRow referencedOn PlanSubjectTable.endChapterId
    var customRangeText by PlanSubjectTable.customRangeText
}
