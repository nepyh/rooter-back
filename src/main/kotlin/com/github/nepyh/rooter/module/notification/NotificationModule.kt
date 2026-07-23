package com.github.nepyh.rooter.module.notification

import com.github.nepyh.rooter.module.notification.api.NotificationApi
import com.github.nepyh.rooter.module.notification.push.FcmPushSender
import com.github.nepyh.rooter.module.notification.push.PushSender
import org.koin.core.qualifier.named
import org.koin.dsl.module

fun NotificationModule() = module {
    single<PushSender> { FcmPushSender() }
    single { NotificationService(get()) }
    single { NotificationScheduler(get()) }
    single(named("notificationApi")) { NotificationApi(get()) }
}
