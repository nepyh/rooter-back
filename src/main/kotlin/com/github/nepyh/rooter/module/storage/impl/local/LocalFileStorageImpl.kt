package com.github.nepyh.rooter.module.storage.impl.local

import com.github.nepyh.rooter.module.storage.FileStorage
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.io.path.createDirectories


class LocalFileStorageImpl(
    val baseDir: Path,
    val baseUrl: String
) : FileStorage {
    init {
        if (!Files.exists(baseDir)) {
            baseDir.createDirectories()
        }
    }

    override suspend fun upload(file: PartData.FileItem, directory: String): String =
        withContext(IO) {
            val targetDir = baseDir.resolve(directory)
            if (!Files.exists(targetDir)) {
                Files.createDirectories(targetDir)
            }

            val originalName = file.originalFileName ?: "unknown_file"
            val fileExtension = originalName.substringAfterLast('.', "")
            val uniqueFileName = if (fileExtension.isNotEmpty()) {
                "${UUID.randomUUID()}.$fileExtension"
            } else {
                UUID.randomUUID().toString()
            }

            val targetFile = targetDir.resolve(uniqueFileName).toFile()

            val writeChannel = targetFile.writeChannel()
            val readChannel = file.provider()

            readChannel.copyTo(writeChannel)

            return@withContext if (directory.isEmpty()) uniqueFileName else "$directory/$uniqueFileName"
        }

    override suspend fun getFile(fileKey: String): PartData.FileItem? =
        withContext(IO) {
            val file = baseDir.resolve(fileKey).toFile()
            if (!file.exists() || !file.isFile) return@withContext null

            PartData.FileItem(
                provider = { file.readChannel() },
                dispose = {},
                partHeaders = Headers.build {
                    append(
                        HttpHeaders.ContentDisposition,
                        "attachment; filename=\"${file.name}\""
                    )
                    append(
                        HttpHeaders.ContentLength,
                        file.length().toString()
                    )
                }
            )
        }

    override suspend fun getUrl(fileKey: String): String? {
        val file = baseDir.resolve(fileKey).toFile()
        if (!file.exists()) return null

        return "${baseUrl.removeSuffix("/")}/${fileKey.removePrefix("/")}"
    }

    override suspend fun delete(fileKey: String): Boolean =
        withContext(IO) {
            val file = baseDir.resolve(fileKey).toFile()
            if (file.exists() && file.isFile) {
                file.delete()
            } else {
                false
            }
        }
}
