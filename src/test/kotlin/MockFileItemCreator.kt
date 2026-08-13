import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.jvm.javaio.*
import java.io.InputStream


fun createMockFileItem(resourcePath: String): PartData.FileItem {
    val inputStream: InputStream = Thread.currentThread().contextClassLoader
        .getResourceAsStream(resourcePath)!!

    val fileName = resourcePath.substringAfterLast('/')

    return PartData.FileItem(
        provider = { inputStream.toByteReadChannel() },
        dispose = { inputStream.close() },
        partHeaders = Headers.build {
            append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"$fileName\"")
            val contentType = ContentType.defaultForFileExtension(fileName.substringAfterLast('.'))
            append(HttpHeaders.ContentType, contentType.toString())
        }
    )
}
