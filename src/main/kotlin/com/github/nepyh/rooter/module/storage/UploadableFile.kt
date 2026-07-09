package com.github.nepyh.rooter.module.storage

import io.ktor.utils.io.ByteReadChannel


data class UploadableFile(
    val content: ByteReadChannel,
    val originalFileName: String?,
    val contentType: String?,
    val contentLength: Long?,
)
