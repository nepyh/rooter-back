package com.github.nepyh.rooter.module.planboard

import org.jetbrains.exposed.v1.core.*
import com.github.nepyh.rooter.module.planboard.dto.ChapterResponse
import com.github.nepyh.rooter.module.planboard.dto.SubjectResponse
import com.github.nepyh.rooter.module.planboard.dto.TextbookResponse
import com.github.nepyh.rooter.module.planboard.model.Chapters
import com.github.nepyh.rooter.module.planboard.model.Subjects
import com.github.nepyh.rooter.module.planboard.model.Textbooks
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction

class CatalogService {

    suspend fun getAllSubjects(): List<SubjectResponse> = newSuspendedTransaction {
        Subjects.selectAll().map {
            SubjectResponse(
                id = it[Subjects.id],
                name = it[Subjects.name]
            )
        }
    }

    suspend fun getTextbooksBySubject(subjectId: Int): List<TextbookResponse> = newSuspendedTransaction {
        Textbooks.selectAll()
            .where { Textbooks.subjectId eq subjectId }
            .map {
                TextbookResponse(
                    id = it[Textbooks.id],
                    subjectId = it[Textbooks.subjectId],
                    publisherId = it[Textbooks.publisherId],
                    title = it[Textbooks.title],
                    aiStatus = it[Textbooks.aiStatus]
                )
            }
    }

    suspend fun getChaptersByTextbook(textbookId: Int): List<ChapterResponse> = newSuspendedTransaction {
        Chapters.selectAll()
            .where { Chapters.textbookId eq textbookId }
            .orderBy(Chapters.chapterOrder to SortOrder.ASC)
            .map {
                ChapterResponse(
                    id = it[Chapters.id],
                    textbookId = it[Chapters.textbookId],
                    parentId = it[Chapters.parentId],
                    chapterName = it[Chapters.chapterName],
                    chapterOrder = it[Chapters.chapterOrder]
                )
            }
    }
}