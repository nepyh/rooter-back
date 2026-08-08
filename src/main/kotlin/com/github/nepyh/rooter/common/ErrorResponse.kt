package com.github.nepyh.rooter.common

import kotlinx.serialization.Serializable


@Serializable
data class ErrorResponse(
    val code: String,
    val message: String
)
