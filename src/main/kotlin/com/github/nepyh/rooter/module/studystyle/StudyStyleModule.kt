package com.github.nepyh.rooter.module.studystyle

import com.github.nepyh.rooter.module.studystyle.api.StudyStyleApi
import org.koin.core.qualifier.named
import org.koin.dsl.module

fun StudyStyleModule() = module {
    single { StudyStyleService() }

    single(named("studyStyleApi")) { StudyStyleApi(get()) }
}
