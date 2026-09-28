package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.planboard.exception.PlanBoardValidationException
import com.github.nepyh.rooter.module.planboard.model.ChapterRow
import com.github.nepyh.rooter.module.planboard.model.ChapterTable
import com.github.nepyh.rooter.module.planboard.model.TextbookRow
import org.jetbrains.exposed.v1.core.eq

/** 트리 순회 순서를 매긴 단원 (depth 는 대단원=0, 소단원=1 ...) */
private data class OrderedChapter(val row: ChapterRow, val depth: Int)

/**
 * 교과서의 단원을 대단원 순서 → 그 안의 소단원 순서(트리 전위 순회, pre-order)로 나열한다.
 *
 * chapters 는 parent_id 자기참조 계층이고 소단원의 chapter_order 는 대단원 안에서 다시 1부터 시작하므로,
 * chapter_order 만으로는 교과서 전체의 단원 순서를 알 수 없다. 순서 판정과 범위 선택은 전부 이 함수를 쓴다.
 *
 * - 루트(parent_id 가 null 인 행)를 chapter_order 오름차순으로 정렬하고, 각 루트의 자식을 같은 방식으로 이어붙인다.
 * - parent_id 는 FK 가 아니라 고아(존재하지 않는 부모를 가리키는 행)나 다른 교과서의 단원을 가리키는 행이 있을 수 있다.
 *   고아 행은 루트로 승격해서 순서 목록에 남긴다 (검증에서 조용히 사라지면 범위 판정이 달라진다).
 * - 순환(A → B → A)은 방문 집합으로 끊는다. 순환에 갇혀 트리 루트에서 도달하지 못한 행은
 *   순회가 끝난 뒤 chapter_order 순으로 뒤에 붙여 누락되지 않게 한다.
 * - 실제 데이터는 2단계(대단원/소단원)지만 재귀라 더 깊은 계층도 같은 규칙으로 동작한다.
 */
private fun orderedChaptersWithDepth(textbookId: Int): List<OrderedChapter> {
    val rows = ChapterRow.find { ChapterTable.textbookId eq textbookId }.toList()
    if (rows.isEmpty()) return emptyList()

    val rowIds = rows.map { it.id.value }.toSet()
    // 없는 부모를 가리키면(고아) 루트로 취급한다
    val childrenByParent = rows.groupBy { row -> row.parentId?.takeIf { it in rowIds } }

    val visited = mutableSetOf<Int>()
    val ordered = mutableListOf<OrderedChapter>()

    fun visit(row: ChapterRow, depth: Int) {
        if (!visited.add(row.id.value)) return // 순환 방어
        ordered += OrderedChapter(row, depth)
        childrenByParent[row.id.value]
            ?.sortedBy { it.chapterOrder }
            ?.forEach { visit(it, depth + 1) }
    }

    childrenByParent[null]?.sortedBy { it.chapterOrder }?.forEach { visit(it, 0) }
    // 고아·순환으로 트리에 붙지 못한 행은 chapter_order 순으로 뒤에 이어 붙인다 (행 누락 방지)
    rows.filterNot { it.id.value in visited }
        .sortedBy { it.chapterOrder }
        .forEach { ordered += OrderedChapter(it, 0) }

    return ordered
}

/** 교과서 단원을 트리 순회 순서(대단원 → 소단원)로 나열한 목록 */
fun orderedTextbookChapters(textbookId: Int): List<ChapterRow> =
    orderedChaptersWithDepth(textbookId).map { it.row }

/**
 * 끝 단원의 서브트리(그 단원과 하위 단원 전부)가 끝나는 위치.
 * 전위 순회 목록에서 서브트리는 연속 구간이므로, 뒤따르는 depth 가 더 깊은 행을 끝까지 소비한다.
 */
private fun subtreeEndIndex(ordered: List<OrderedChapter>, index: Int): Int {
    var end = index
    while (end + 1 < ordered.size && ordered[end + 1].depth > ordered[index].depth) end++
    return end
}

/**
 * 시작·끝 단원이 가리키는 구간의 인덱스(양 끝 포함).
 * 시작 단원이 끝 단원의 서브트리 뒤에 있으면 잘못된 범위이므로 null 을 돌려준다.
 * 끝 단원이 대단원이면 그 대단원의 소단원까지 범위에 포함한다 ("이 대단원부터 저 대단원까지" 요청의 자연스러운 해석).
 */
private fun chapterRangeIndices(textbookId: Int, startChapterId: Int, endChapterId: Int): IntRange? {
    val ordered = orderedChaptersWithDepth(textbookId)
    val startIndex = ordered.indexOfFirst { it.row.id.value == startChapterId }
    val endIndex = ordered.indexOfFirst { it.row.id.value == endChapterId }
    if (startIndex < 0 || endIndex < 0) return null
    val lastIndex = subtreeEndIndex(ordered, endIndex)
    if (startIndex > lastIndex) return null
    return startIndex..lastIndex
}

/**
 * 과목 범위로 받은 시작·끝 단원을 검증하고 엔티티로 돌려준다.
 * 과목 등록·수정(PlanBoardService)과 AI 계획 생성(PlanGenerationService)이 같은 규칙을 쓰도록 검증은 여기서만 한다.
 *
 * 거부 조건: 단원이 존재하지 않거나, 요청한 교과서의 단원이 아니거나, 시작 단원이 끝 단원보다 뒤인 경우.
 * 앞뒤 판정은 chapter_order 가 아니라 트리 순회 순서(대단원 → 소단원)로 한다 — 소단원의 chapter_order 는
 * 대단원 안에서 다시 1부터 시작해 교과서 전체 순서를 나타내지 못하기 때문이다.
 * 교과서 소속은 readValues 로 읽어 참조 엔티티 lazy SELECT 를 피한다.
 */
fun resolveChapterRange(textbook: TextbookRow, startChapterId: Int, endChapterId: Int): Pair<ChapterRow, ChapterRow> {
    val startChapter = ChapterRow.findById(startChapterId)
        ?: throw PlanBoardValidationException.InvalidSubjectRangeException()
    val endChapter = ChapterRow.findById(endChapterId)
        ?: throw PlanBoardValidationException.InvalidSubjectRangeException()

    val textbookId = textbook.id.value
    if (startChapter.readValues[ChapterTable.textbookId].value != textbookId ||
        endChapter.readValues[ChapterTable.textbookId].value != textbookId
    ) {
        throw PlanBoardValidationException.InvalidSubjectRangeException()
    }
    if (chapterRangeIndices(textbookId, startChapterId, endChapterId) == null) {
        throw PlanBoardValidationException.InvalidSubjectRangeException()
    }

    return startChapter to endChapter
}

/**
 * 검증된 시작·끝 단원 사이의 단원들을 트리 순회 순서대로 돌려준다 (양 끝 포함, 양 끝이 대단원이면 하위 소단원까지 포함).
 * AI 계획 생성(PlanGenerationService)과 퀴즈 범위 선택(QuizService)이 같은 범위를 쓰도록 슬라이스는 여기서만 한다.
 * `resolveChapterRange` 를 거친 값이면 항상 유효하다 — 아니면(직접 호출) 빈 목록으로 방어한다.
 */
fun orderedChaptersInRange(
    textbook: TextbookRow,
    startChapter: ChapterRow,
    endChapter: ChapterRow
): List<ChapterRow> {
    val ordered = orderedChaptersWithDepth(textbook.id.value)
    val indices = chapterRangeIndices(textbook.id.value, startChapter.id.value, endChapter.id.value)
        ?: return emptyList()
    return ordered.subList(indices.first, indices.last + 1).map { it.row }
}
