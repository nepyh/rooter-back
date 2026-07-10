package com.github.nepyh.rooter.module.storage.impl.s3

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.DeleteObjectRequest
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.NoSuchKey
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.smithy.kotlin.runtime.content.asByteStream
import aws.smithy.kotlin.runtime.content.toInputStream
import com.github.nepyh.rooter.module.storage.FileStorage
import com.github.nepyh.rooter.module.storage.UploadableFile
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes


class S3FileStorage(
    region: String,
    private val bucket: String,
    private val presignDuration: Duration = 15.minutes,
) : FileStorage {
    private val s3 = S3Client {
        this.region = region
    }

    override suspend fun upload(file: UploadableFile, directory: String): String {
        val extension = file.originalFileName
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotEmpty() }

        val key = buildString {
            append(directory.trim('/'))
            append('/')
            append(UUID.randomUUID())
            extension?.let { append('.'); append(it) }
        }

        file.content.toInputStream().use { inputStream ->
            s3.putObject(
                PutObjectRequest {
                    bucket = this@S3FileStorage.bucket
                    this.key = key
                    body = inputStream.asByteStream(file.contentLength)
                    contentType = file.contentType
                }
            )
        }

        return key
    }

    override suspend fun <T> readFile(
        fileKey: String,
        block: suspend (stream: InputStream, contentType: String?, contentLength: Long?) -> T
    ): T? = withContext(Dispatchers.IO) {
        try {
            s3.getObject(
                GetObjectRequest {
                    bucket = this@S3FileStorage.bucket
                    key = fileKey
                }
            ) { resp ->
                resp.body?.toInputStream()?.use { stream ->
                    block.invoke(stream, resp.contentType, resp.contentLength)
                }
            }
        } catch (_: NoSuchKey) {
            null
        }
    }

    override suspend fun getUrl(fileKey: String): String? {
        return try {
            val presigned = s3.presignGetObject(
                GetObjectRequest {
                    bucket = this@S3FileStorage.bucket
                    key = fileKey
                },
                presignDuration,
            )
            presigned.url.toString()
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun delete(fileKey: String): Boolean {
        return try {
            s3.deleteObject(
                DeleteObjectRequest {
                    bucket = this@S3FileStorage.bucket
                    key = fileKey
                }
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    fun close() {
        s3.close()
    }
}
