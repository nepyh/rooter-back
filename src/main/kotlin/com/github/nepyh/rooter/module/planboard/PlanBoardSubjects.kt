package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.planboard.model.PlanSubjectRow
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectTable
import org.jetbrains.exposed.v1.core.eq

/** 플랜보드 학습 범위에 들어 있는 과목 (subjectId, 과목명). 같은 과목이 여러 번 있어도 한 번만, 등록 순서대로. 트랜잭션 안에서 부른다. */
fun planBoardSubjects(planBoardId: Int): List<Pair<Int, String>> =
    PlanSubjectRow.find { PlanSubjectTable.planBoardId eq planBoardId }
        .map { it.textbook.subject.id.value to it.textbook.subject.name }
        .distinctBy { it.first }

/**
 * 태스크가 어느 과목인지 추정한다. 태스크에는 과목 컬럼이 없어서 이름으로 찾는다.
 * 이름에 과목명이 하나만 들어 있으면 그 과목, 아니면 보드 과목이 하나뿐일 때 그 과목, 그 외엔 null.
 */
fun guessTaskSubject(taskName: String, subjects: List<Pair<Int, String>>): Pair<Int, String>? {
    val named = subjects.filter { (_, name) -> taskName.contains(name) }
    return when {
        named.size == 1 -> named.single()
        named.isEmpty() && subjects.size == 1 -> subjects.single()
        else -> null
    }
}
