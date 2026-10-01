package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.planboard.dto.ChapterResponse
import com.github.nepyh.rooter.module.planboard.dto.ChapterTreeResponse
import com.github.nepyh.rooter.module.planboard.dto.RecommendedTextbookResponse
import com.github.nepyh.rooter.module.planboard.dto.SubjectResponse
import com.github.nepyh.rooter.module.planboard.dto.TextbookDetailResponse
import com.github.nepyh.rooter.module.planboard.dto.TextbookResponse
import com.github.nepyh.rooter.module.planboard.model.ChapterRow
import com.github.nepyh.rooter.module.planboard.model.ChapterTable
import com.github.nepyh.rooter.module.planboard.model.PublisherRow
import com.github.nepyh.rooter.module.planboard.model.PublisherTable
import com.github.nepyh.rooter.module.planboard.model.SchoolTextbookAdoptionRow
import com.github.nepyh.rooter.module.planboard.model.SchoolTextbookAdoptionTable
import com.github.nepyh.rooter.module.planboard.model.SubjectRow
import com.github.nepyh.rooter.module.planboard.model.SubjectTable
import com.github.nepyh.rooter.module.planboard.model.TextbookRow
import com.github.nepyh.rooter.module.planboard.model.TextbookTable
import com.github.nepyh.rooter.module.storage.FileStorage
import com.github.nepyh.rooter.module.user.model.StudentProfileRow
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction

class CatalogService(private val fileStorage: FileStorage) {

    suspend fun getAllSubjects(): List<SubjectResponse> = newSuspendedTransaction {
        SubjectRow.all().map {
            SubjectResponse(
                id = it.id.value,
                name = it.name
            )
        }
    }

    suspend fun getTextbooksBySubject(subjectId: Int): List<TextbookResponse> {
        val (textbooks, publisherNames) = newSuspendedTransaction {
            val rows = TextbookRow.find { TextbookTable.subjectId eq subjectId }.toList()
            rows to publisherNamesOf(rows.mapNotNull { it.publisherId })
        }

        return textbooks.map {
            TextbookResponse(
                id = it.id.value,
                subjectId = subjectId,
                publisherId = it.publisherId,
                publisherName = it.publisherId?.let(publisherNames::get),
                title = it.title,
                aiStatus = it.aiStatus,
                coverImageUrl = resolveCoverImageUrl(it.coverImageKey)
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
     * 표지 이미지 URL 은 DB 값이 아니라 파일 스토리지 조회 결과라 트랜잭션 밖에서 붙인다.
     */
    suspend fun getRecommendedTextbooks(userId: Int): List<RecommendedTextbookResponse> {
        val recommendations: List<Pair<RecommendedTextbookResponse, String?>> = newSuspendedTransaction {
            val profile = StudentProfileRow.find { StudentProfileTable.user eq userId }
                .firstOrNull() ?: return@newSuspendedTransaction emptyList()

            val adoptions = SchoolTextbookAdoptionRow.find {
                (SchoolTextbookAdoptionTable.schoolId eq profile.schoolId) and
                    (SchoolTextbookAdoptionTable.grade eq profile.grade)
            }.toList()

            if (adoptions.isEmpty()) return@newSuspendedTransaction emptyList()

            val subjectNames = SubjectRow.find { SubjectTable.id inList adoptions.map { it.subjectId } }
                .associate { it.id.value to it.name }
            val textbookRows = TextbookRow.find { TextbookTable.id inList adoptions.map { it.textbookId } }.toList()
            val textbooks = textbookRows.associate { it.id.value to (it.title to it.coverImageKey) }
            val publisherNames = publisherNamesOf(textbookRows.mapNotNull { it.publisherId })
            val publisherIdByTextbook = textbookRows.associate { it.id.value to it.publisherId }

            adoptions.mapNotNull { adoption ->
                val subjectId = adoption.subjectId.value
                val textbookId = adoption.textbookId.value
                val subjectName = subjectNames[subjectId] ?: return@mapNotNull null
                val (textbookTitle, coverImageKey) = textbooks[textbookId] ?: return@mapNotNull null
                RecommendedTextbookResponse(
                    subjectId = subjectId,
                    subjectName = subjectName,
                    textbookId = textbookId,
                    textbookTitle = textbookTitle,
                    publisherName = publisherIdByTextbook[textbookId]?.let(publisherNames::get),
                    coverImageUrl = null // 아래에서 채운다
                ) to coverImageKey
            }
        }

        return recommendations.map { (response, coverImageKey) ->
            response.copy(coverImageUrl = resolveCoverImageUrl(coverImageKey))
        }
    }

    suspend fun getTextbookDetail(textbookId: Int): TextbookDetailResponse? {
        val found: Pair<TextbookDetailResponse, String?>? = newSuspendedTransaction {
            val textbook = TextbookRow.findById(textbookId) ?: return@newSuspendedTransaction null

            val allChapters = ChapterRow.find { ChapterTable.textbookId eq textbookId }
                .orderBy(ChapterTable.chapterOrder to SortOrder.ASC)
                .toList()

            TextbookDetailResponse(
                id = textbook.id.value,
                subjectId = textbook.subject.id.value,
                subjectName = textbook.subject.name,
                publisherId = textbook.publisherId,
                publisherName = textbook.publisherId?.let { PublisherRow.findById(it)?.name },
                title = textbook.title,
                aiStatus = textbook.aiStatus,
                coverImageUrl = null, // 아래에서 채운다
                chapters = buildChapterTree(allChapters)
            ) to textbook.coverImageKey
        }

        val (detail, coverImageKey) = found ?: return null
        return detail.copy(coverImageUrl = resolveCoverImageUrl(coverImageKey))
    }

    /** 출판사 id 목록 → 이름. textbooks.publisher_id 는 Exposed 에서 FK 없이 정수로만 두고 있어 따로 한 번에 읽는다. 트랜잭션 안에서 부른다. */
    private fun publisherNamesOf(publisherIds: List<Int>): Map<Int, String> =
        if (publisherIds.isEmpty()) emptyMap()
        else PublisherRow.find { PublisherTable.id inList publisherIds.distinct() }.associate { it.id.value to it.name }

    /** 표지 이미지 키를 파일 스토리지 URL 로 바꾼다 — 외부 호출(S3 presign 등)이라 DB 트랜잭션 밖에서 부른다. */
    private suspend fun resolveCoverImageUrl(coverImageKey: String?): String? =
        coverImageKey?.let { fileStorage.getUrl(it) }

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
