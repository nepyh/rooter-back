package com.github.nepyh.rooter.module.storage

import io.ktor.http.content.PartData


interface FileStorage {
    suspend fun upload(file: PartData.FileItem, directory: String): String
    suspend fun getFile(fileKey: String): PartData.FileItem?
    suspend fun getUrl(fileKey: String): String?
    suspend fun delete(fileKey: String): Boolean
}
