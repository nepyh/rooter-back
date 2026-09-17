package com.github.nepyh.rooter.module.planboard

import org.jetbrains.exposed.v1.core.*
import com.github.nepyh.rooter.module.planboard.dto.ChapterResponse
import com.github.nepyh.rooter.module.planboard.dto.ChapterTreeResponse
import com.github.nepyh.rooter.module.planboard.dto.RecommendedTextbookResponse
import com.github.nepyh.rooter.module.planboard.dto.SubjectResponse
import com.github.nepyh.rooter.module.planboard.dto.TextbookDetailResponse
import com.github.nepyh.rooter.module.planboard.dto.TextbookResponse
import com.github.nepyh.rooter.module.planboard.model.Chapters
import com.github.nepyh.rooter.module.planboard.model.SchoolTextbookAdoptions
import com.github.nepyh.rooter.module.planboard.model.Subjects
import com.github.nepyh.rooter.module.planboard.model.Textbooks
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
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

    /**
     * 본인 student_profiles 의 school_id/grade 로 school_textbook_adoptions 를 조회해 추천 교과서를 내려준다.
     * 프로필이 없거나, 학교/학년에 매핑 데이터가 없는 과목은 결과에서 그냥 빠진다 (에러 아님) —
     * 프론트는 빈 목록/일부 누락 시 기존 교과서 직접 선택 플로우(getTextbooksBySubject 등)로 폴백해야 한다.
     */
    suspend fun getRecommendedTextbooks(userId: Int): List<RecommendedTextbookResponse> = newSuspendedTransaction {
        val profile = StudentProfileTable.selectAll()
            .where { StudentProfileTable.user eq userId }
            .firstOrNull() ?: return@newSuspendedTransaction emptyList()

        val schoolId = profile[StudentProfileTable.schoolId]
        val grade = profile[StudentProfileTable.grade]

        val adoptions = SchoolTextbookAdoptions.selectAll()
            .where { (SchoolTextbookAdoptions.schoolId eq schoolId) and (SchoolTextbookAdoptions.grade eq grade) }
            .map { it[SchoolTextbookAdoptions.subjectId] to it[SchoolTextbookAdoptions.textbookId] }

        if (adoptions.isEmpty()) return@newSuspendedTransaction emptyList()

        val subjectNames = Subjects.selectAll()
            .where { Subjects.id inList adoptions.map { it.first } }
            .associate { it[Subjects.id] to it[Subjects.name] }
        val textbookTitles = Textbooks.selectAll()
            .where { Textbooks.id inList adoptions.map { it.second } }
            .associate { it[Textbooks.id] to it[Textbooks.title] }

        adoptions.mapNotNull { (subjectId, textbookId) ->
            val subjectName = subjectNames[subjectId] ?: return@mapNotNull null
            val textbookTitle = textbookTitles[textbookId] ?: return@mapNotNull null
            RecommendedTextbookResponse(
                subjectId = subjectId,
                subjectName = subjectName,
                textbookId = textbookId,
                textbookTitle = textbookTitle
            )
        }
    }

    suspend fun getTextbookDetail(textbookId: Int): TextbookDetailResponse? = newSuspendedTransaction {
        // 1. 교과서 + 과목명 조회 (Textbooks join Subjects)
        val row = (Textbooks innerJoin Subjects)
            .selectAll()
            .where { Textbooks.id eq textbookId }
            .firstOrNull()
            ?: return@newSuspendedTransaction null

        // 2. 이 교과서의 모든 단원을 flat 하게 조회 (order 순)
        val allChapters = Chapters.selectAll()
            .where { Chapters.textbookId eq textbookId }
            .orderBy(Chapters.chapterOrder to SortOrder.ASC)
            .map {
                ChapterRow(
                    id = it[Chapters.id],
                    parentId = it[Chapters.parentId],
                    name = it[Chapters.chapterName],
                    order = it[Chapters.chapterOrder]
                )
            }

        // 3. flat 리스트를 트리로 조립
        val tree = buildChapterTree(allChapters)

        TextbookDetailResponse(
            id = row[Textbooks.id],
            subjectId = row[Textbooks.subjectId],
            subjectName = row[Subjects.name],
            publisherId = row[Textbooks.publisherId],
            title = row[Textbooks.title],
            aiStatus = row[Textbooks.aiStatus],
            chapters = tree
        )
    }

    private data class ChapterRow(
        val id: Int,
        val parentId: Int?,
        val name: String,
        val order: Int
    )

    private fun buildChapterTree(rows: List<ChapterRow>): List<ChapterTreeResponse> {
        val childrenByParent = rows.groupBy { it.parentId }

        fun build(parentId: Int?): List<ChapterTreeResponse> {
            return childrenByParent[parentId]
                ?.sortedBy { it.order }
                ?.map { row ->
                    ChapterTreeResponse(
                        id = row.id,
                        chapterName = row.name,
                        chapterOrder = row.order,
                        children = build(row.id)
                    )
                }
                ?: emptyList()
        }

        return build(null)
    }
}