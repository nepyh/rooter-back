package com.github.nepyh.rooter.module.user.dto

import com.github.nepyh.rooter.module.user.model.DayOfWeek
import com.github.nepyh.rooter.module.user.model.UnavailableKind
import kotlinx.serialization.Serializable


@Serializable
data class UnavailableTimeRequest(
    val dayOfWeek: Short,
    val startTime: String,
    val endTime: String,
    val kind: UnavailableKind = UnavailableKind.CUSTOM, // 식사(BREAKFAST/LUNCH/DINNER)·학원(ACADEMY)·기타(CUSTOM)
    val label: String? = null // 사용자가 붙인 이름, 50자 이하. 비우면 앱이 kind 로 이름을 보여준다
)

@Serializable
data class UnavailableTimeResponse(
    val id: Int,
    val dayOfWeek: DayOfWeek,
    val startTime: String,
    val endTime: String,
    val kind: UnavailableKind,
    val label: String?
)
