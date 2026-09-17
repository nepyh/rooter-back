import com.github.nepyh.rooter.module.storage.impl.s3.S3FileStorage
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.UUID


class S3FileStorageSpec : StringSpec({
    val region = System.getenv("TEST_S3_REGION")
    val bucket = System.getenv("TEST_S3_BUCKET")
    val s3Available = !region.isNullOrBlank() && !bucket.isNullOrBlank()

    // TEST_S3_REGION/TEST_S3_BUCKET env 가 없으면 테스트가 disabled 처리되어 건너뜀
    // (beforeSetup 의 assumeTrue 는 테스트 실패로 처리되므로 사용하지 않음)
    "S3 버킷 업로드/조회/presigned URL 라운드트립".config(enabled = s3Available) {
        val storage = S3FileStorage(region = region, bucket = bucket)
        val key = storage.upload(createMockUploadableFile("banana.png"), "avatars")

        try {
            key shouldNotBe ""
            storage.readFile(key) { stream, _, _ -> stream.readBytes() }.shouldNotBeNull()
            storage.getUrl(key).shouldNotBeNull()
        } finally {
            storage.delete(key)
        }
    }

    "존재하지 않는 키 조회 시 null 반환".config(enabled = s3Available) {
        val storage = S3FileStorage(region = region, bucket = bucket)
        storage.readFile("missing-${UUID.randomUUID()}") { stream, _, _ -> stream.readBytes() } shouldBe null
    }

    "존재하지 않는 키 delete 시 false 반환".config(enabled = s3Available) {
        val storage = S3FileStorage(region = region, bucket = bucket)
        storage.delete("missing-${UUID.randomUUID()}") shouldBe false
    }
})
