package com.github.nepyh.rooter.module.review

import com.github.nepyh.rooter.module.review.api.ReviewApi
import org.koin.core.qualifier.named
import org.koin.dsl.module

fun ReviewModule() = module {
    single { ReviewService() }

    single(named("reviewApi")) { ReviewApi(get()) }
}
