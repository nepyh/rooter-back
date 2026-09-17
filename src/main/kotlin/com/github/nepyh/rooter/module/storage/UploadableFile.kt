package com.github.nepyh.rooter.module.storage

import io.ktor.http.HttpHeaders
import io.ktor.http.content.PartData
import io.ktor.utils.io.ByteReadChannel


data class UploadableFile(
    val content: ByteReadChannel,
    val originalFileName: String?,
    val contentType: String?,
    val contentLength: Long?,
)

fun PartData.FileItem.toUploadableFile(): UploadableFile = UploadableFile(
    content = provider(),
    originalFileName = originalFileName,
    contentType = headers[HttpHeaders.ContentType],
    contentLength = headers[HttpHeaders.ContentLength]?.toLongOrNull(),
)
