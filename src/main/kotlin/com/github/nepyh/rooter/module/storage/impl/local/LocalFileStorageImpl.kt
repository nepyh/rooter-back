package com.github.nepyh.rooter.module.storage.impl.local

import com.github.nepyh.rooter.module.storage.FileStorage
import com.github.nepyh.rooter.module.storage.UploadableFile
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import java.io.InputStream
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

    override suspend fun upload(file: UploadableFile, directory: String): String =
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

            file.content.toInputStream().use { inputStream ->
                targetFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            if (directory.isEmpty()) uniqueFileName else "$directory/$uniqueFileName"
        }

    override suspend fun <T> readFile(
        fileKey: String,
        block: suspend (stream: InputStream, contentType: String?, contentLength: Long?) -> T,
    ): T? = withContext(IO) {
        val file = baseDir.resolve(fileKey).toFile()
        if (!file.exists() || !file.isFile) return@withContext null

        file.inputStream().use { stream ->
            block.invoke(stream, null, file.length())
        }
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
