package com.github.nepyh.rooter.module.notification

import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.module.notification.api.NotificationApi
import com.github.nepyh.rooter.module.notification.push.ExpoPushSender
import com.github.nepyh.rooter.module.notification.push.PushSender
import org.koin.core.qualifier.named
import org.koin.dsl.module

fun NotificationModule(appConfig: AppConfig) = module {
    single<PushSender> { ExpoPushSender(appConfig.expoAccessToken) }
    single { DeviceTokenService(get()) }
    single { NotificationSettingService() }
    single { NotificationService(get(), get()) }
    single(named("notificationApi")) { NotificationApi(get(), get(), get()) }
}
