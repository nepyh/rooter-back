package com.github.nepyh.rooter.config


enum class EnvironmentMode {
    DEV,
    PROD,
    ;

    companion object {
        fun fromString(string: String): EnvironmentMode =
            EnvironmentMode.valueOf(string.uppercase())
    }
}
