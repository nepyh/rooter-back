import com.github.nepyh.rooter.module.storage.impl.local.LocalFileStorageImpl
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import java.nio.file.Paths
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists


@OptIn(ExperimentalPathApi::class)
class FileStorageTest : StringSpec({
    val projectRoot = System.getProperty("user.dir")
    val workingDir = Paths.get("$projectRoot/build/test-run")

    beforeSpec {
        if (workingDir.exists()) workingDir.deleteRecursively()
    }

    val fileStorage = LocalFileStorageImpl(workingDir, "/")

    "바나나 사진 업로드 테스트" {
        val file = createMockUploadableFile("banana.png")
        shouldNotThrow<Exception> {
            fileStorage.upload(file, "asdf")
        }
    }

    "바나나 사진 업로드 후 fileKey 로 조회 테스트" {
        val file = createMockUploadableFile("banana.png")
        val fileKey = fileStorage.upload(file, "asdf")
        shouldNotThrow<Exception> {
            shouldNotBeNull {
                fileStorage.readFile(fileKey) { stream, _, _ -> stream.readBytes() }
            }
        }
    }
})
