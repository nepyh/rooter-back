package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.planboard.dto.BusyTimeItem
import com.github.nepyh.rooter.module.planboard.dto.BusyTimeResponse
import com.github.nepyh.rooter.module.planboard.dto.DailyBusyTimeResponse
import com.github.nepyh.rooter.module.planboard.dto.TimeRangeResponse
import com.github.nepyh.rooter.module.planboard.exception.BusyTimeValidationException
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.school.SchoolDataFetcher
import com.github.nepyh.rooter.module.user.model.StudentProfileRow
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
import com.github.nepyh.rooter.module.user.model.UnavailableTimeRow
import com.github.nepyh.rooter.module.user.model.UnavailableTimeTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private const val MAX_RANGE_DAYS = 31

private data class ExistingTask(
    val date: LocalDate,
    val item: BusyTimeItem,
    val start: Int,
    val end: Int
)

/**
 * 날짜별로 이미 막혀 있는 시간(취침·등교·사용자 등록 불가능 시간·기존 할일)과 남은 빈 시간을 알려준다.
 * 앱이 할일을 직접 추가하거나 끌어서 옮길 때 겹치지 않는 시간을 보여주는 용도.
 * 취침·등교 계산은 AI 계획 생성과 같은 [PlanTaskScheduler.buildTypedUnavailableRanges] 를 쓴다.
 */
class BusyTimeService(private val schoolDataFetcher: SchoolDataFetcher) {

    suspend fun getBusyTimes(userId: Int, startDate: LocalDate, endDate: LocalDate): BusyTimeResponse {
        if (endDate.isBefore(startDate) || ChronoUnit.DAYS.between(startDate, endDate) >= MAX_RANGE_DAYS) {
            throw BusyTimeValidationException.InvalidDateRangeException()
        }

        data class Context(
            val schoolId: String?,
            val classNumber: Int?,
            val grade: Int,
            val customRows: List<Pair<Int, Pair<Int, Int>>>,
            val tasks: List<ExistingTask>
        )

        val context = newSuspendedTransaction {
            val profile = StudentProfileRow.find { StudentProfileTable.user eq userId }.firstOrNull()
            val customRows = UnavailableTimeRow.find { UnavailableTimeTable.user eq userId }
                .map {
                    it.dayOfWeek.code.toInt() to
                        (PlanTaskScheduler.toMinutes(it.startTime) to PlanTaskScheduler.toMinutes(it.endTime))
                }
            val tasks = (PlanTaskTable innerJoin DailyPlanTable innerJoin PlanBoardTable)
                .select(
                    PlanTaskTable.id, PlanTaskTable.taskName, PlanTaskTable.startTime, PlanTaskTable.endTime,
                    DailyPlanTable.planDate, PlanBoardTable.id, PlanBoardTable.title
                )
                .where {
                    (PlanBoardTable.userId eq userId) and
                        (DailyPlanTable.planDate greaterEq startDate) and
                        (DailyPlanTable.planDate lessEq endDate)
                }
                .map {
                    val start = PlanTaskScheduler.toMinutes(it[PlanTaskTable.startTime])
                    val end = PlanTaskScheduler.toMinutes(it[PlanTaskTable.endTime])
                    ExistingTask(
                        date = it[DailyPlanTable.planDate],
                        item = BusyTimeItem(
                            type = "TASK",
                            startTime = formatMinutes(start),
                            endTime = formatMinutes(end),
                            taskId = it[PlanTaskTable.id].value,
                            taskName = it[PlanTaskTable.taskName],
                            planBoardId = it[PlanBoardTable.id].value,
                            planBoardTitle = it[PlanBoardTable.title]
                        ),
                        start = start,
                        end = end
                    )
                }
            Context(profile?.schoolId, profile?.classNumber, profile?.grade ?: 2, customRows, tasks)
        }

        // NICE 조회(외부 호출)는 트랜잭션 밖에서
        val unavailable = PlanTaskScheduler.buildTypedUnavailableRanges(
            schoolDataFetcher = schoolDataFetcher,
            startDate = startDate,
            endDate = endDate,
            schoolId = context.schoolId,
            classNumber = context.classNumber,
            grade = context.grade,
            customRows = context.customRows
        )
        val tasksByDate = context.tasks.groupBy { it.date }

        val days = generateSequence(startDate) { it.plusDays(1) }.takeWhile { !it.isAfter(endDate) }.map { date ->
            val ranges = unavailable[date].orEmpty()
            val tasks = tasksByDate[date].orEmpty()
            val busy = ranges.map { Triple(it.start, it.end, BusyTimeItem(it.type.name, formatMinutes(it.start), formatMinutes(it.end))) } +
                tasks.map { Triple(it.start, it.end, it.item) }
            DailyBusyTimeResponse(
                date = date.toString(),
                isSchoolDay = ranges.any { it.type == UnavailableType.SCHOOL },
                busyTimes = busy.sortedWith(compareBy({ it.first }, { it.second })).map { it.third },
                freeTimes = PlanTaskScheduler.freeIntervalsFromBusyRanges(busy.map { it.first to it.second })
                    .map { (start, end) -> TimeRangeResponse(formatMinutes(start), formatMinutes(end)) }
            )
        }.toList()

        return BusyTimeResponse(startDate = startDate.toString(), endDate = endDate.toString(), days = days)
    }
}

/** 분 → "HH:mm". 하루 끝(1440분)은 "24:00" 으로 낸다. */
internal fun formatMinutes(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
