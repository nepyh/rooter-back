package com.github.nepyh.rooter.module.planboard

import org.jetbrains.exposed.v1.core.*
import com.github.nepyh.rooter.module.planboard.dto.ChapterResponse
import com.github.nepyh.rooter.module.planboard.dto.ChapterTreeResponse
import com.github.nepyh.rooter.module.planboard.dto.RecommendedTextbookResponse
import com.github.nepyh.rooter.module.planboard.dto.SubjectResponse
import com.github.nepyh.rooter.module.planboard.dto.TextbookDetailResponse
import com.github.nepyh.rooter.module.planboard.dto.TextbookResponse
import com.github.nepyh.rooter.module.planboard.model.ChapterTable
import com.github.nepyh.rooter.module.planboard.model.SchoolTextbookAdoptionTable
import com.github.nepyh.rooter.module.planboard.model.SubjectTable
import com.github.nepyh.rooter.module.planboard.model.TextbookTable
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction

class CatalogService {

    suspend fun getAllSubjects(): List<SubjectResponse> = newSuspendedTransaction {
        SubjectTable.selectAll().map {
            SubjectResponse(
                id = it[SubjectTable.id].value,
                name = it[SubjectTable.name]
            )
        }
    }

    suspend fun getTextbooksBySubject(subjectId: Int): List<TextbookResponse> = newSuspendedTransaction {
        TextbookTable.selectAll()
            .where { TextbookTable.subjectId eq subjectId }
            .map {
                TextbookResponse(
                    id = it[TextbookTable.id].value,
                    subjectId = it[TextbookTable.subjectId].value,
                    publisherId = it[TextbookTable.publisherId],
                    title = it[TextbookTable.title],
                    aiStatus = it[TextbookTable.aiStatus]
                )
            }
    }

    suspend fun getChaptersByTextbook(textbookId: Int): List<ChapterResponse> = newSuspendedTransaction {
        ChapterTable.selectAll()
            .where { ChapterTable.textbookId eq textbookId }
            .orderBy(ChapterTable.chapterOrder to SortOrder.ASC)
            .map {
                ChapterResponse(
                    id = it[ChapterTable.id].value,
                    textbookId = it[ChapterTable.textbookId].value,
                    parentId = it[ChapterTable.parentId],
                    chapterName = it[ChapterTable.chapterName],
                    chapterOrder = it[ChapterTable.chapterOrder]
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

        val adoptions = SchoolTextbookAdoptionTable.selectAll()
            .where { (SchoolTextbookAdoptionTable.schoolId eq schoolId) and (SchoolTextbookAdoptionTable.grade eq grade) }
            .map { it[SchoolTextbookAdoptionTable.subjectId] to it[SchoolTextbookAdoptionTable.textbookId] }

        if (adoptions.isEmpty()) return@newSuspendedTransaction emptyList()

        val subjectNames = SubjectTable.selectAll()
            .where { SubjectTable.id inList adoptions.map { it.first } }
            .associate { it[SubjectTable.id] to it[SubjectTable.name] }
        val textbookTitles = TextbookTable.selectAll()
            .where { TextbookTable.id inList adoptions.map { it.second } }
            .associate { it[TextbookTable.id] to it[TextbookTable.title] }

        adoptions.mapNotNull { (subjectId, textbookId) ->
            val subjectName = subjectNames[subjectId] ?: return@mapNotNull null
            val textbookTitle = textbookTitles[textbookId] ?: return@mapNotNull null
            RecommendedTextbookResponse(
                subjectId = subjectId.value,
                subjectName = subjectName,
                textbookId = textbookId.value,
                textbookTitle = textbookTitle
            )
        }
    }

    suspend fun getTextbookDetail(textbookId: Int): TextbookDetailResponse? = newSuspendedTransaction {
        // 1. 교과서 + 과목명 조회 (TextbookTable join SubjectTable)
        val row = (TextbookTable innerJoin SubjectTable)
            .selectAll()
            .where { TextbookTable.id eq textbookId }
            .firstOrNull()
            ?: return@newSuspendedTransaction null

        // 2. 이 교과서의 모든 단원을 flat 하게 조회 (order 순)
        val allChapters = ChapterTable.selectAll()
            .where { ChapterTable.textbookId eq textbookId }
            .orderBy(ChapterTable.chapterOrder to SortOrder.ASC)
            .map {
                ChapterRow(
                    id = it[ChapterTable.id].value,
                    parentId = it[ChapterTable.parentId],
                    name = it[ChapterTable.chapterName],
                    order = it[ChapterTable.chapterOrder]
                )
            }

        // 3. flat 리스트를 트리로 조립
        val tree = buildChapterTree(allChapters)

        TextbookDetailResponse(
            id = row[TextbookTable.id].value,
            subjectId = row[TextbookTable.subjectId].value,
            subjectName = row[SubjectTable.name],
            publisherId = row[TextbookTable.publisherId],
            title = row[TextbookTable.title],
            aiStatus = row[TextbookTable.aiStatus],
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