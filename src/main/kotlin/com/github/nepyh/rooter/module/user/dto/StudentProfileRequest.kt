package com.github.nepyh.rooter.module.user.dto

import kotlinx.serialization.Serializable

@Serializable
data class StudentProfileRequest(
    val schoolId: String,
    val grade: Int,
    val classNumber: Int
)
