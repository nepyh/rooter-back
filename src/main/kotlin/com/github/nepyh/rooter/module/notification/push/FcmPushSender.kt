package com.github.nepyh.rooter.module.notification.push

import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FcmPushSender : PushSender {
    override suspend fun send(deviceToken: String, title: String, body: String) {
        // firebase.credentialsPath 가 설정 안 돼있으면 FirebaseApp 자체가 초기화 안 됨 -> 조용히 스킵
        if (FirebaseApp.getApps().isEmpty()) {
            return
        }

        withContext(Dispatchers.IO) {
            val message = Message.builder()
                .setToken(deviceToken)
                .setNotification(
                    Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build()
                )
                .build()

            FirebaseMessaging.getInstance().send(message)
        }
    }
}
