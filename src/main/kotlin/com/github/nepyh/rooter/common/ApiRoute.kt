package com.github.nepyh.rooter.common

import io.ktor.server.routing.*


class ApiRoute(val baseRoute: String? = null, val routeProvider: Route.() -> Unit) {
    fun Route.configureRoute() {
        val targetRouter = if (baseRoute != null) {
            createRouteFromPath(baseRoute)
        } else {
            this
        }

        targetRouter.apply(routeProvider)
    }
}
