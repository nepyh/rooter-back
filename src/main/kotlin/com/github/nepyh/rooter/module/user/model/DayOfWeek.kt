package com.github.nepyh.rooter.module.user.model


enum class DayOfWeek(val code: Short) {
    MONDAY(1),
    TUESDAY(2),
    WEDNESDAY(3),
    THURSDAY(4),
    FRIDAY(5),
    SATURDAY(6),
    SUNDAY(7)
    ;

    companion object {
        fun fromCode(code: Short): DayOfWeek =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unknown DayOfWeek code: $code")

        fun fromName(name: String, ignoreCase: Boolean = true): DayOfWeek =
            entries.firstOrNull {
                name.equals(it.name, ignoreCase = ignoreCase)
            } ?: throw IllegalArgumentException("Unknown DayOfWeek name: $name")
    }
}
