package com.github.nepyh.rooter.module.storage

import java.io.InputStream


interface FileStorage {
    suspend fun upload(file: UploadableFile, directory: String): String
    suspend fun <T> readFile(
        fileKey: String,
        block: suspend (stream: InputStream, contentType: String?, contentLength: Long?) -> T,
    ): T?
    suspend fun getUrl(fileKey: String): String?
    suspend fun delete(fileKey: String): Boolean
}
