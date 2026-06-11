package com.github.nepyh.rooter.common

import io.ktor.server.routing.Route


interface ApiRoute {
    fun Route.configureRoute()
}

fun ApiRoute(routeProvider: Route.() -> Unit): ApiRoute = object : ApiRoute {
    override fun Route.configureRoute() {
        this.apply(routeProvider)
    }
}
