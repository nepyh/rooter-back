package com.github.nepyh.rooter.module.notification

import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.module.notification.api.NotificationApi
import com.github.nepyh.rooter.module.notification.push.ExpoPushSender
import com.github.nepyh.rooter.module.notification.push.PushSender
import com.github.nepyh.rooter.module.scheduler.SchedulerJob
import org.koin.core.qualifier.named
import org.koin.dsl.module

fun NotificationModule(appConfig: AppConfig) = module {
    single<PushSender> { ExpoPushSender(appConfig.expoAccessToken) }
    single { DeviceTokenService(get()) }
    single { NotificationSettingService() }
    single { NotificationService(get(), get()) }
    // named 필수: SchedulerJob 을 이름 없이 등록하면 다른 모듈의 SchedulerJob 정의를 덮어써서
    // SchedulerEngine 의 getAll<SchedulerJob>() 에 하나만 잡히는 문제가 생김
    single<SchedulerJob>(named("taskReminderJob")) { TaskReminderJob(get()) }
    single(named("notificationApi")) { NotificationApi(get(), get(), get()) }
}
