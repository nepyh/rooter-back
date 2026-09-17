package com.github.nepyh.rooter.module.calendar

import com.github.nepyh.rooter.module.calendar.api.CalendarApi
import org.koin.core.qualifier.named
import org.koin.dsl.module

fun CalendarModule() = module {
    single { CalendarService() }
    single(named("calendarApi")) { CalendarApi(get()) }
}
