import com.github.nepyh.rooter.module.storage.impl.local.LocalFileStorageImpl
import io.kotest.core.spec.style.StringSpec


class FileStorageTest : StringSpec({
    "스토리지 서비스 바나나 사진 업로드 및 테스트" {
        val fileStorage = LocalFileStorageImpl("run/store", "/")

        val fileItem = createMockFileItem("banana.png")

        fileStorage.upload(fileItem, "asdf")
    }
})
