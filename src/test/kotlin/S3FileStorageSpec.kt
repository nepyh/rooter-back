import com.github.nepyh.rooter.module.storage.impl.s3.S3FileStorage
import org.junit.jupiter.api.Assumptions.assumeTrue


class S3FileStorageSpec : FileStorageSpec(
    beforeSetup = {
        assumeTrue(
            !System.getenv("TEST_S3_BUCKET").isNullOrBlank(),
            "TEST_S3_BUCKET 환경 변수가 없음. 3SFileStorageSpec 테스트 건너뜀. " +
                    "만약 이 에러를 봤다면 리드미의 테스트 부분 참고하삼",
        )
        assumeTrue(
            !System.getenv("TEST_S3_REGION").isNullOrBlank(),
            "TEST_S3_REGION 환경 변수가 없음. 3SFileStorageSpec 테스트 건너뜀. " +
                    "만약 이 에러를 봤다면 리드미의 테스트 부분 참고하삼",
        )
    },
    makeStorage = {
        S3FileStorage(
            region = System.getenv("TEST_S3_REGION"),
            bucket = System.getenv("TEST_S3_BUCKET"),
        )
    },
)
