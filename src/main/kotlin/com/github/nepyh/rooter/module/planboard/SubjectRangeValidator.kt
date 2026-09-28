package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.planboard.exception.PlanBoardValidationException
import com.github.nepyh.rooter.module.planboard.model.ChapterRow
import com.github.nepyh.rooter.module.planboard.model.ChapterTable
import com.github.nepyh.rooter.module.planboard.model.TextbookRow

/**
 * 과목 범위로 받은 시작·끝 단원을 검증하고 엔티티로 돌려준다.
 * 과목 등록·수정(PlanBoardService)과 AI 계획 생성(PlanGenerationService)이 같은 규칙을 쓰도록 검증은 여기서만 한다.
 *
 * 거부 조건: 단원이 존재하지 않거나, 요청한 교과서의 단원이 아니거나, 시작 단원이 끝 단원보다 뒤인 경우.
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
    if (startChapter.chapterOrder > endChapter.chapterOrder) {
        throw PlanBoardValidationException.InvalidSubjectRangeException()
    }

    return startChapter to endChapter
}
