package com.github.nepyh.rooter.module.example

import kotlin.random.Random


class ExampleService {
    fun getRandomNumber(): Int {
        return Random.nextInt()
    }
}
