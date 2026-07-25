import com.github.nepyh.rooter.module.storage.impl.local.LocalFileStorageImpl
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.test.TestCaseOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import java.nio.file.Paths
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists


@OptIn(ExperimentalPathApi::class)
class FileStorageTest : StringSpec({
    isolationMode = IsolationMode.SingleInstance
    testCaseOrder = TestCaseOrder.Sequential

    val projectRoot = System.getProperty("user.dir")
    val workingDir = Paths.get("$projectRoot/build/test-run")

    beforeSpec {
        if (workingDir.exists()) workingDir.deleteRecursively()
    }

    val fileStorage = LocalFileStorageImpl(workingDir, "/")
    var fileKey = "게스"

    "로컬 스토리지 서비스 바나나 사진 업로드 및 테스트" {
        val file = createMockUploadableFile("banana.png")

        shouldNotThrow<Exception> {
            fileKey = fileStorage.upload(file, "asdf")
        }
    }

    "파일을 올리면서 얻어진 fileKey 로 바나나 사진 파일 떙겨와 지는지 테스트" {
        shouldNotThrow<Exception> {
            shouldNotBeNull {
                fileStorage.readFile(fileKey) { stream, _, _ -> stream.readBytes() }
            }
        }
    }
})
