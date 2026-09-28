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

/**
 * 파트를 그대로 업로드용 파일로 감싼다.
 * 내용을 이미 읽어놨거나(용량 제한 등) Content-Type 을 다르게 정해야 할 때는 각 인자를 넘긴다.
 */
fun PartData.FileItem.toUploadableFile(
    content: ByteReadChannel = provider(),
    contentLength: Long? = headers[HttpHeaders.ContentLength]?.toLongOrNull(),
    contentType: String? = headers[HttpHeaders.ContentType],
): UploadableFile = UploadableFile(
    content = content,
    originalFileName = originalFileName,
    contentType = contentType,
    contentLength = contentLength,
)
