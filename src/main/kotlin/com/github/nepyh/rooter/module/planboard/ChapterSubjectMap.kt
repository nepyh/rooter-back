package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.planboard.model.ChapterTable
import com.github.nepyh.rooter.module.planboard.model.TextbookTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select

/**
 * 주어진 교과서들 안에서 단원 이름 -> 과목 id 후보를 만든다. 퀴즈 문항에 저장된 단원(chapter_name)으로 과목을 되짚을 때 쓴다.
 * 단원 이름은 FK 가 아니라 문자열이라 같은 이름이 여러 교과서에 있을 수 있어 후보를 목록으로 돌려준다.
 * 과목 id 만 필요해 Table DSL 로 벌크 조회한다 (Row 로 읽으면 교과서마다 lazy SELECT 가 붙는다). 트랜잭션 안에서 부른다.
 */
fun chapterNameToSubjectIds(textbookIds: Collection<Int>): Map<String, List<Int>> {
    if (textbookIds.isEmpty()) return emptyMap()

    return (ChapterTable innerJoin TextbookTable)
        .select(ChapterTable.chapterName, TextbookTable.subjectId)
        .where { TextbookTable.id inList textbookIds.map { EntityID(it, TextbookTable) } }
        .groupBy({ it[ChapterTable.chapterName] }, { it[TextbookTable.subjectId].value })
}
