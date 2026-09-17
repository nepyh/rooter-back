package com.github.nepyh.rooter.module.user

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

/**
 * Google/Apple 이 발급한 OIDC id_token 을 JWKS 로 서명 검증한다.
 * 두 provider 모두 RS256 + JWKS 방식이라 공용으로 뺌.
 */
class OidcTokenVerifier(jwksUrl: String) {

    private val jwkProvider = JwkProviderBuilder(URI(jwksUrl).toURL())
        .cached(10, 1, TimeUnit.HOURS)
        .build()

    fun verify(idToken: String, issuer: String, audience: String): DecodedJWT {
        val kid = JWT.decode(idToken).keyId
        val publicKey = jwkProvider.get(kid).publicKey as RSAPublicKey
        val algorithm = Algorithm.RSA256(publicKey, null)

        return JWT.require(algorithm)
            .withIssuer(issuer)
            .withAudience(audience)
            .build()
            .verify(idToken)
    }
}
