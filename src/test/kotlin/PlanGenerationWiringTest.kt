import com.github.nepyh.rooter.common.config.AgreementConfigEntry
import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.common.config.EnvironmentMode
import com.github.nepyh.rooter.module.AppModule
import com.github.nepyh.rooter.module.planboard.PLAN_GENERATION_JOB_TYPE
import com.github.nepyh.rooter.module.planboard.PlanGenerationContinuer
import com.github.nepyh.rooter.module.planboard.PlanGenerationService
import com.github.nepyh.rooter.module.scheduler.SchedulerJob
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldNotBe
import org.koin.dsl.koinApplication

/**
 * AI 계획 이어 만들기(#405)의 Koin 연결 — 컴파일로는 안 잡힌다.
 * 이어 만들기 잡이 다른 SchedulerJob 을 덮어쓰지 않고(named 등록) 함께 잡히는지, 서비스·Continuer 가 서로를 찾는지 확인한다.
 */
class PlanGenerationWiringTest : StringSpec({

    "이어 만들기 잡이 다른 스케줄러 잡과 함께 등록되고, 서비스와 Continuer 가 만들어진다" {
        val koin = koinApplication { modules(AppModule(wiringAppConfig())) }.koin

        val jobTypes = koin.getAll<SchedulerJob>().map { it.jobType }
        jobTypes shouldContain PLAN_GENERATION_JOB_TYPE
        jobTypes shouldContain "task_quiz_trigger"
        jobTypes shouldContain "feedback_replan"
        koin.get<PlanGenerationService>() shouldNotBe null
        koin.get<PlanGenerationContinuer>() shouldNotBe null
        koin.close()
    }
})

private fun wiringAppConfig() = AppConfig(
    environment = EnvironmentMode.DEV,
    jdbcUrl = "", dbUsername = "", dbPassword = "", dbMaxPoolSize = 1,
    corsAllowedHosts = emptyList(), corsMaxAgeSeconds = 0,
    storageType = "local", storageBaseDir = null, storageBaseUrl = null, storageBaseRoute = null,
    storageAwsRegion = null, storageAwsBucket = null,
    jwtSecret = "", jwtIssuer = "",
    llmBaseUrl = "", llmApiKey = "", llmModel = "",
    niceApiKey = "", niceBaseUrl = "",
    googleClientId = "", appleClientId = "",
    agreements = listOf(
        AgreementConfigEntry("TERMS", "이용약관", "1", "https://example.com/terms"),
        AgreementConfigEntry("PRIVACY", "개인정보", "1", "https://example.com/privacy")
    )
)
