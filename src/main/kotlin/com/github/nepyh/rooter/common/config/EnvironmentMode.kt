package com.github.nepyh.rooter.common.config


enum class EnvironmentMode {
    DEV,
    PROD,
    ;

    companion object {
        fun fromString(string: String): EnvironmentMode =
            EnvironmentMode.valueOf(string.uppercase())
    }
}
