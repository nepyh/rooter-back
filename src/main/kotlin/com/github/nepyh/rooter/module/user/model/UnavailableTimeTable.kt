package com.github.nepyh.rooter.module.user.model

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.time


object UnavailableTimeTable : IntIdTable("user_unavailable_times") {
    val user = reference("user_id", UserTable)
    val dayOfWeek: Column<DayOfWeek> = customEnumeration(
        name = "day_of_week",
        sql = "SMALLINT",
        fromDb = { DayOfWeek.fromCode((it as Number).toShort()) },
        toDb = { it.code }
    )
    val startTime = time("start_time")
    val endTime = time("end_time")
    val kind = enumerationByName("kind", 20, UnavailableKind::class).default(UnavailableKind.CUSTOM)
    val label = varchar("label", 50).nullable()
}

class UnavailableTimeRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<UnavailableTimeRow>(UnavailableTimeTable)

    var user by UserRow referencedOn UnavailableTimeTable.user
    var dayOfWeek by UnavailableTimeTable.dayOfWeek
    var startTime by UnavailableTimeTable.startTime
    var endTime by UnavailableTimeTable.endTime
    var kind by UnavailableTimeTable.kind
    var label by UnavailableTimeTable.label
}
