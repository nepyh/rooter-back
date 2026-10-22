package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.SubjectRow
import com.github.nepyh.rooter.module.planboard.model.SubjectTable
import org.jetbrains.exposed.v1.core.inList

/*
 * 할일 응답(PlanTaskResponse)의 과목명 채우기. 할일 조회(PlanTaskService) 말고도
 * 캘린더 하루 요약·챗봇 재조정·미루기·퀴즈 실패 재배치가 같은 응답을 만들어서 여기 함께 둔다.
 */

/**
 * 목록 응답에 붙일 과목명 맵. 행마다 참조 엔티티를 읽으면 행 수만큼 SELECT 가 나가므로
 * subject id 만 모아 한 번에 조회한다 (CatalogService 의 교과서·과목 벌크 조회와 같은 방식). 트랜잭션 안에서 부른다.
 */
fun subjectNamesOf(rows: List<PlanTaskRow>): Map<Int, String> {
    val subjectIds = rows.mapNotNull { it.subjectId }.distinct()
    if (subjectIds.isEmpty()) return emptyMap()
    return SubjectRow.find { SubjectTable.id inList subjectIds }
        .associate { it.id.value to it.name }
}

/** 이 행의 과목명을 벌크로 만든 맵에서 꺼낸다 (과목 미지정이면 null) */
fun PlanTaskRow.subjectNameFrom(subjectNames: Map<Int, String>): String? =
    subjectId?.value?.let { subjectNames[it] }
