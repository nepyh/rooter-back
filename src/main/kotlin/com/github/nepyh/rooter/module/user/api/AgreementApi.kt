package com.github.nepyh.rooter.module.user.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.user.UserService
import com.github.nepyh.rooter.module.user.dto.AgreementCatalogResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi


/** 인증 없는 조항 카탈로그. 가입 화면이 체크박스·링크를 그릴 때 쓴다. */
@OptIn(ExperimentalKtorApi::class)
fun AgreementApi(userService: UserService) = ApiRoute("agreements") {
    get("") {
        call.respond(HttpStatusCode.OK, userService.getAgreementCatalog())
    }.describe {
        tag("Agreement")
        summary = "현재 약관·개인정보 동의 항목 조회"
        description = "가입·동의 화면이 그릴 조항 목록. code 는 동의 저장·재동의 요청에서 그대로 쓰는 식별자이고, " +
            "required 가 true 인 항목은 가입 때 agreed=false 로 보낼 수 없다(400 REQUIRED_AGREEMENT_MISSING). " +
            "가입 뒤 철회(POST /users/{id}/agreements)는 필수 항목도 받으며, 철회한 필수 항목은 pendingAgreements 에 다시 나온다. " +
            "문서 본문은 url 이 가리키는 정적 페이지가 정본이며 버전마다 내용이 고정된다."
        responses {
            HttpStatusCode.OK {
                description = "조회 성공"
                ContentType.Application.Json {
                    schema = jsonSchema<AgreementCatalogResponse>()
                }
            }
            HttpStatusCode.InternalServerError {
                description = "서버 오류 (conf 의 agreements 설정이 비었거나 code 가 enum 과 어긋나면 기동 자체가 실패한다)"
            }
        }
    }
}
