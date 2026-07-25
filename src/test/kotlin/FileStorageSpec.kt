import com.github.nepyh.rooter.module.storage.FileStorage
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull


abstract class FileStorageSpec(
    private val makeStorage: () -> FileStorage,
    private val beforeSetup: suspend () -> Unit = {},
) : StringSpec() {
    private lateinit var storage: FileStorage

    init {
        beforeSpec {
            beforeSetup()
            storage = makeStorage()
        }

        "바나나 사진 업로드 테스트" {
            val file = createMockUploadableFile("banana.png")
            shouldNotThrow<Exception> {
                storage.upload(file, "asdf")
            }
        }

        "바나나 사진 업로드 후 fileKey 로 조회 테스트" {
            val file = createMockUploadableFile("banana.png")
            val fileKey = storage.upload(file, "asdf")
            shouldNotThrow<Exception> {
                shouldNotBeNull {
                    storage.readFile(fileKey) { stream, _, _ -> stream.readBytes() }
                }
            }
        }
    }
}
