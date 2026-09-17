package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.planboard.dto.ChapterResponse
import com.github.nepyh.rooter.module.planboard.dto.ChapterTreeResponse
import com.github.nepyh.rooter.module.planboard.dto.RecommendedTextbookResponse
import com.github.nepyh.rooter.module.planboard.dto.SubjectResponse
import com.github.nepyh.rooter.module.planboard.dto.TextbookDetailResponse
import com.github.nepyh.rooter.module.planboard.dto.TextbookResponse
import com.github.nepyh.rooter.module.planboard.model.ChapterRow
import com.github.nepyh.rooter.module.planboard.model.ChapterTable
import com.github.nepyh.rooter.module.planboard.model.SchoolTextbookAdoptionRow
import com.github.nepyh.rooter.module.planboard.model.SchoolTextbookAdoptionTable
import com.github.nepyh.rooter.module.planboard.model.SubjectRow
import com.github.nepyh.rooter.module.planboard.model.SubjectTable
import com.github.nepyh.rooter.module.planboard.model.TextbookRow
import com.github.nepyh.rooter.module.planboard.model.TextbookTable
import com.github.nepyh.rooter.module.user.model.StudentProfileRow
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction

class CatalogService {

    suspend fun getAllSubjects(): List<SubjectResponse> = newSuspendedTransaction {
        SubjectRow.all().map {
            SubjectResponse(
                id = it.id.value,
                name = it.name
            )
        }
    }

    suspend fun getTextbooksBySubject(subjectId: Int): List<TextbookResponse> = newSuspendedTransaction {
        TextbookRow.find { TextbookTable.subjectId eq subjectId }
            .map {
                TextbookResponse(
                    id = it.id.value,
                    subjectId = subjectId,
                    publisherId = it.publisherId,
                    title = it.title,
                    aiStatus = it.aiStatus
                )
            }
    }

    suspend fun getChaptersByTextbook(textbookId: Int): List<ChapterResponse> = newSuspendedTransaction {
        ChapterRow.find { ChapterTable.textbookId eq textbookId }
            .orderBy(ChapterTable.chapterOrder to SortOrder.ASC)
            .map {
                ChapterResponse(
                    id = it.id.value,
                    textbookId = textbookId,
                    parentId = it.parentId,
                    chapterName = it.chapterName,
                    chapterOrder = it.chapterOrder
                )
            }
    }

    /**
     * 본인 student_profiles 의 school_id/grade 로 school_textbook_adoptions 를 조회해 추천 교과서를 내려준다.
     * 프로필이 없거나, 학교/학년에 매핑 데이터가 없는 과목은 결과에서 그냥 빠진다 (에러 아님) —
     * 프론트는 빈 목록/일부 누락 시 기존 교과서 직접 선택 플로우(getTextbooksBySubject 등)로 폴백해야 한다.
     *
     * 과목명/교과서명은 FK id 목록으로 한 번에 읽는다 (Row 의 EntityID 속성이라 추가 조회 없음).
     */
    suspend fun getRecommendedTextbooks(userId: Int): List<RecommendedTextbookResponse> = newSuspendedTransaction {
        val profile = StudentProfileRow.find { StudentProfileTable.user eq userId }
            .firstOrNull() ?: return@newSuspendedTransaction emptyList()

        val adoptions = SchoolTextbookAdoptionRow.find {
            (SchoolTextbookAdoptionTable.schoolId eq profile.schoolId) and
                (SchoolTextbookAdoptionTable.grade eq profile.grade)
        }.toList()

        if (adoptions.isEmpty()) return@newSuspendedTransaction emptyList()

        val subjectNames = SubjectRow.find { SubjectTable.id inList adoptions.map { it.subjectId } }
            .associate { it.id.value to it.name }
        val textbookTitles = TextbookRow.find { TextbookTable.id inList adoptions.map { it.textbookId } }
            .associate { it.id.value to it.title }

        adoptions.mapNotNull { adoption ->
            val subjectId = adoption.subjectId.value
            val textbookId = adoption.textbookId.value
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
        val textbook = TextbookRow.findById(textbookId) ?: return@newSuspendedTransaction null

        val allChapters = ChapterRow.find { ChapterTable.textbookId eq textbookId }
            .orderBy(ChapterTable.chapterOrder to SortOrder.ASC)
            .toList()

        TextbookDetailResponse(
            id = textbook.id.value,
            subjectId = textbook.subject.id.value,
            subjectName = textbook.subject.name,
            publisherId = textbook.publisherId,
            title = textbook.title,
            aiStatus = textbook.aiStatus,
            chapters = buildChapterTree(allChapters)
        )
    }

    private fun buildChapterTree(rows: List<ChapterRow>): List<ChapterTreeResponse> {
        val childrenByParent = rows.groupBy { it.parentId }

        fun build(parentId: Int?): List<ChapterTreeResponse> {
            return childrenByParent[parentId]
                ?.sortedBy { it.chapterOrder }
                ?.map { row ->
                    ChapterTreeResponse(
                        id = row.id.value,
                        chapterName = row.chapterName,
                        chapterOrder = row.chapterOrder,
                        children = build(row.id.value)
                    )
                }
                ?: emptyList()
        }

        return build(null)
    }
}
