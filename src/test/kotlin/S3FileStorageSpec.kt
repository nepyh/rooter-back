import com.github.nepyh.rooter.module.storage.impl.s3.S3FileStorage
import org.junit.jupiter.api.Assumptions.assumeTrue


class S3FileStorageSpec : FileStorageSpec(
    beforeSetup = {
        assumeTrue(
            !System.getenv("TEST_S3_BUCKET").isNullOrBlank(),
            "TEST_S3_BUCKET 환경 변수가 없어 S3 테스트를 건너뜁니다.",
        )
    },
    makeStorage = {
        S3FileStorage(
            region = System.getenv("TEST_S3_REGION") ?: "ap-northeast-2",
            bucket = checkNotNull(System.getenv("TEST_S3_BUCKET")),
        )
    },
)
