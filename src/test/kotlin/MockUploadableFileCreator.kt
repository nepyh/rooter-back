import com.github.nepyh.rooter.module.storage.UploadableFile
import io.ktor.http.*
import io.ktor.utils.io.*


fun createMockUploadableFile(resourcePath: String): UploadableFile {
    val bytes = Thread.currentThread().contextClassLoader
        .getResourceAsStream(resourcePath)!!
        .readBytes()

    val fileName = resourcePath.substringAfterLast('/')
    val contentType = ContentType.defaultForFileExtension(fileName.substringAfterLast('.')).toString()

    return UploadableFile(
        content = ByteReadChannel(bytes),
        originalFileName = fileName,
        contentType = contentType,
        contentLength = bytes.size.toLong(),
    )
}
