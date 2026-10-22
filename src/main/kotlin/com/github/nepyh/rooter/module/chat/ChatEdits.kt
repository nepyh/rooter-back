package com.github.nepyh.rooter.module.chat

import com.github.nepyh.rooter.module.chat.dto.AiChatEdit
import com.github.nepyh.rooter.module.planboard.PlanTaskScheduler
import java.time.LocalTime

/*
 * 챗봇의 할일 직접 편집 (#306) — "수학 30분으로 줄여줘", "8시에 과학 추가해줘", "영어 빼줘", "수학 9시로 옮겨줘".
 * AI 는 동작(edits)만 고르고, 시간이 실제로 비어 있는지·지울 수 있는지는 여기서 서버가 판단한다.
 * 편집은 순서대로 적용하고, 앞 편집의 결과(옮긴 자리·추가한 할일)를 뒤 편집이 본다.
 * DB 접근이 없는 순수 로직이라 ChatService 가 결과를 받아 저장한다.
 */

private const val DAY_MINUTES = 24 * 60
private const val AUTO_SLOT_GAP_MINUTES = 10 // 시각을 안 정한 추가는 다른 할일과 이만큼 띄운다 (계획 배치와 같은 쉬는 시간)
internal const val EDIT_MIN_MINUTES = 5
internal const val EDIT_MAX_MINUTES = 240
private const val DEFAULT_ADD_MINUTES = 30
private const val MAX_TASK_NAME_LENGTH = 150

/** 그날 미완료 할일 하나. id 가 null 이면 이번 편집으로 새로 추가할 할일 */
internal data class EditableTask(
    val id: Int?,
    val name: String,
    val start: Int,
    val end: Int,
    val subjectId: Int? = null,
    val quizStarted: Boolean = false, // 태스크 퀴즈 차수가 하나라도 있음 → 지우면 퀴즈 기록이 사라져서 삭제 불가
    val quizFinalized: Boolean = false // 퀴즈로 결과가 확정됨 → 시간 변경도 불가 (PATCH 와 같은 규칙)
) {
    val minutes get() = end - start
}

/**
 * 편집을 판단할 그날의 조건.
 * @param blocked 할일을 둘 수 없는 시간(분) — 수면·등교·사용자 불가능 시간·이미 완료한 할일·다른 플랜보드의 할일
 * @param earliestMinute 오늘이면 지금 이후만 (10분 단위 올림), 다른 날이면 0
 * @param subjectIdsByName 플랜보드 과목명 → 과목 id (추가하는 할일에 과목을 붙일 때)
 */
internal class EditDay(
    val blocked: List<Pair<Int, Int>>,
    val earliestMinute: Int,
    val subjectIdsByName: Map<String, Int>
)

internal data class EditResult(
    val tasks: List<EditableTask>, // 적용 뒤 그날 미완료 할일 (id null = 새로 추가)
    val deletedIds: Set<Int>,
    val applied: List<String>, // 답변에 쓸 성공 문장
    val rejected: List<String> // 답변에 쓸 거절 문장 (이유 포함)
) {
    val changed get() = applied.isNotEmpty()
}

internal fun applyChatEdits(initial: List<EditableTask>, edits: List<AiChatEdit>, day: EditDay): EditResult {
    val tasks = initial.toMutableList()
    val deletedIds = mutableSetOf<Int>()
    val applied = mutableListOf<String>()
    val rejected = mutableListOf<String>()

    for (edit in edits) {
        when (edit.type.trim().lowercase()) {
            "resize" -> {
                val index = findTask(tasks, edit.task_name) ?: run { rejected += notFound(edit.task_name); null } ?: continue
                val task = tasks[index]
                val minutes = edit.minutes
                when {
                    minutes == null || minutes !in EDIT_MIN_MINUTES..EDIT_MAX_MINUTES ->
                        rejected += "'${task.name}'은(는) ${EDIT_MIN_MINUTES}~${EDIT_MAX_MINUTES}분 사이로만 바꿀 수 있어요."
                    task.quizFinalized -> rejected += finalizedReply(task.name)
                    else -> {
                        val end = task.start + minutes
                        // 줄이기는 항상 들어간다. 늘리면 늘어난 자리가 비어 있어야 한다
                        val conflict = if (end > task.end) conflictOf(task.end, end, tasks, index, day) else null
                        if (conflict != null) {
                            rejected += "'${task.name}'을(를) ${minutes}분으로 늘리지 못했어요 — $conflict.${freeHint(tasks, day)}"
                        } else {
                            tasks[index] = task.copy(end = end)
                            applied += "'${task.name}'을(를) ${minutes}분(${hhmm(task.start)}~${hhmm(end)})으로 ${if (end < task.end) "줄였어요" else "바꿨어요"}."
                        }
                    }
                }
            }

            "move" -> {
                val index = findTask(tasks, edit.task_name) ?: run { rejected += notFound(edit.task_name); null } ?: continue
                val task = tasks[index]
                val start = parseMinutes(edit.start_time)
                when {
                    start == null -> rejected += "'${task.name}'을(를) 몇 시로 옮길지 알려 주세요."
                    task.quizFinalized -> rejected += finalizedReply(task.name)
                    else -> {
                        val end = start + task.minutes
                        val conflict = conflictOf(start, end, tasks, index, day)
                        if (conflict != null) {
                            rejected += "'${task.name}'을(를) ${hhmm(start)}로 옮기지 못했어요 — $conflict.${freeHint(tasks, day)}"
                        } else {
                            tasks[index] = task.copy(start = start, end = end)
                            applied += "'${task.name}'을(를) ${hhmm(start)}~${hhmm(end)}로 옮겼어요."
                        }
                    }
                }
            }

            "delete" -> {
                val index = findTask(tasks, edit.task_name) ?: run { rejected += notFound(edit.task_name); null } ?: continue
                val task = tasks[index]
                if (task.quizStarted || task.quizFinalized) {
                    rejected += "'${task.name}'은(는) 이미 퀴즈를 시작한 할일이라 뺄 수 없어요."
                } else {
                    tasks.removeAt(index)
                    task.id?.let { deletedIds += it }
                    applied += "'${task.name}'을(를) 오늘 계획에서 뺐어요."
                }
            }

            "add" -> {
                val name = edit.task_name?.trim().orEmpty().take(MAX_TASK_NAME_LENGTH)
                val minutes = edit.minutes ?: DEFAULT_ADD_MINUTES
                if (name.isBlank()) {
                    rejected += "추가할 할일 이름을 알려 주세요."
                    continue
                }
                if (minutes !in EDIT_MIN_MINUTES..EDIT_MAX_MINUTES) {
                    rejected += "'$name'은(는) ${EDIT_MIN_MINUTES}~${EDIT_MAX_MINUTES}분 사이로만 추가할 수 있어요."
                    continue
                }
                val subjectId = edit.subject_name?.trim()?.let { day.subjectIdsByName[it] }
                val requestedStart = edit.start_time?.let { parseMinutes(it) }
                if (edit.start_time != null && requestedStart == null) {
                    rejected += "'$name'을(를) 몇 시에 추가할지 다시 알려 주세요."
                    continue
                }
                if (requestedStart != null) {
                    val end = requestedStart + minutes
                    val conflict = conflictOf(requestedStart, end, tasks, null, day)
                    if (conflict != null) {
                        rejected += "'$name'을(를) ${hhmm(requestedStart)}에 추가하지 못했어요 — $conflict.${freeHint(tasks, day)}"
                    } else {
                        tasks += EditableTask(null, name, requestedStart, end, subjectId)
                        applied += "'$name'을(를) ${hhmm(requestedStart)}~${hhmm(end)}에 추가했어요."
                    }
                } else {
                    // 시각을 안 정했으면 그날 가장 이른 빈 시간 (다른 할일과 쉬는 시간만큼 띄워서)
                    val busy = day.blocked + (0 to day.earliestMinute) + tasks.map {
                        (it.start - AUTO_SLOT_GAP_MINUTES).coerceAtLeast(0) to (it.end + AUTO_SLOT_GAP_MINUTES).coerceAtMost(DAY_MINUTES)
                    }
                    val slot = PlanTaskScheduler.freeIntervalsFromBusyRanges(busy).firstOrNull { (s, e) -> e - s >= minutes }
                    if (slot == null) {
                        rejected += "오늘은 '$name'(${minutes}분)이 들어갈 빈 시간이 없어요."
                    } else {
                        val end = slot.first + minutes
                        tasks += EditableTask(null, name, slot.first, end, subjectId)
                        applied += "'$name'을(를) ${hhmm(slot.first)}~${hhmm(end)}에 추가했어요."
                    }
                }
            }

            else -> rejected += "그 요청은 처리하지 못했어요."
        }
    }

    return EditResult(tasks.sortedBy { it.start }, deletedIds, applied, rejected)
}

/** AI 가 준 이름으로 할일을 찾는다. 글자 그대로 같은 게 우선, 없으면 한쪽이 다른 쪽을 포함하는 할일이 하나뿐일 때만 */
private fun findTask(tasks: List<EditableTask>, name: String?): Int? {
    val wanted = name?.trim().orEmpty()
    if (wanted.isBlank()) return null
    tasks.indexOfFirst { it.name.trim() == wanted }.takeIf { it >= 0 }?.let { return it }
    val partial = tasks.withIndex().filter { (_, t) -> t.name.contains(wanted) || wanted.contains(t.name.trim()) }
    return partial.singleOrNull()?.index
}

/** [start, end) 에 할일을 둘 수 없는 이유. 둘 수 있으면 null. [selfIndex] 는 자기 자신이라 겹침 판단에서 뺀다 */
private fun conflictOf(start: Int, end: Int, tasks: List<EditableTask>, selfIndex: Int?, day: EditDay): String? {
    if (start < 0 || end > DAY_MINUTES) return "하루를 넘겨요"
    if (start < day.earliestMinute) return "이미 지난 시간이에요"
    tasks.withIndex()
        .firstOrNull { (i, t) -> i != selfIndex && t.start < end && start < t.end }
        ?.let { return "그 시간에 '${it.value.name}' 할일이 있어요" }
    if (day.blocked.any { (s, e) -> s < end && start < e }) return "그 시간은 잠·학교·학원 같은 다른 일정과 겹쳐요"
    return null
}

/** 거절할 때 알려줄 그날 빈 시간 (10분 이상, 앞에서 세 개) */
private fun freeHint(tasks: List<EditableTask>, day: EditDay): String {
    val free = PlanTaskScheduler.freeIntervalsFromBusyRanges(day.blocked + (0 to day.earliestMinute) + tasks.map { it.start to it.end })
        .filter { (s, e) -> e - s >= 10 }
        .take(3)
    return if (free.isEmpty()) " 오늘은 남은 빈 시간이 없어요." else " 오늘 빈 시간: ${free.joinToString(", ") { (s, e) -> "${hhmm(s)}~${hhmm(e)}" }}"
}

private fun notFound(name: String?): String =
    if (name.isNullOrBlank()) "어떤 할일을 바꿀지 알려 주세요." else "'${name.trim()}' 할일을 오늘 계획에서 찾지 못했어요."

private fun finalizedReply(name: String): String = "'$name'은(는) 퀴즈로 결과가 확정된 할일이라 바꿀 수 없어요."

private fun parseMinutes(time: String?): Int? =
    time?.trim()?.let { runCatching { LocalTime.parse(it) }.getOrNull() }?.let { PlanTaskScheduler.toMinutes(it) }

internal fun hhmm(minutes: Int): String = if (minutes >= DAY_MINUTES) "24:00" else "%02d:%02d".format(minutes / 60, minutes % 60)
