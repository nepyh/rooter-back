package com.github.nepyh.rooter.module.notification.push

interface PushSender {
    suspend fun send(deviceToken: String, title: String, body: String)
}
