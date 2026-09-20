package com.localai.runtime.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Serves the single-file web console at `GET /` from [htmlProvider] (backed by
 * [com.localai.runtime.server.api.ServerEnv.webConsoleHtml]). Responds `404` with a short plain
 * text explanation when the console is disabled. The page is always served `Cache-Control:
 * no-store` so console updates ship immediately.
 */
fun Route.webConsole(htmlProvider: () -> String?) {
    get("/") {
        val html = htmlProvider()
        if (html == null) {
            call.respondText("Web console is disabled", ContentType.Text.Html, HttpStatusCode.NotFound)
        } else {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText(html, ContentType.Text.Html)
        }
    }
}
