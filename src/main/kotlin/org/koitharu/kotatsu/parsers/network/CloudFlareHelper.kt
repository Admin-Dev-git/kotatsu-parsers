package org.koitharu.kotatsu.parsers.network

import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.Jsoup
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.net.HttpURLConnection.HTTP_UNAVAILABLE

public object CloudFlareHelper {

    public const val PROTECTION_NOT_DETECTED: Int = 0
    public const val PROTECTION_CAPTCHA: Int = 1
    public const val PROTECTION_BLOCKED: Int = 2

    private const val CF_CLEARANCE = "cf_clearance"

    public fun checkResponseForProtection(response: Response): Int {
        if (response.code != HTTP_FORBIDDEN && response.code != HTTP_UNAVAILABLE) {
            // Also check 429 (rate limited) which CF sometimes uses
            if (response.code != 429) {
                return PROTECTION_NOT_DETECTED
            }
        }
        // Check CF server header
        val server = response.header("server") ?: ""
        val isCfServer = server.contains("cloudflare", ignoreCase = true)
        val content = try {
            response.peekBody(Long.MAX_VALUE).use {
                Jsoup.parse(it.byteStream(), Charsets.UTF_8.name(), response.request.url.toString())
            }
        } catch (_: IllegalStateException) {
            return if (isCfServer && response.code == HTTP_FORBIDDEN) {
                PROTECTION_CAPTCHA
            } else {
                PROTECTION_NOT_DETECTED
            }
        }
        return when {
            // Blocked page
            content.selectFirst("h2[data-translate=\"blocked_why_headline\"]") != null -> PROTECTION_BLOCKED
            content.selectFirst("div.cf-error-details") != null -> PROTECTION_BLOCKED

            // Challenge/Captcha page — classic and Turnstile variants
            content.getElementById("challenge-error-title") != null -> PROTECTION_CAPTCHA
            content.getElementById("challenge-error-text") != null -> PROTECTION_CAPTCHA
            content.getElementById("challenge-running") != null -> PROTECTION_CAPTCHA
            content.getElementById("challenge-stage") != null -> PROTECTION_CAPTCHA
            content.getElementById("challenge-form") != null -> PROTECTION_CAPTCHA
            content.selectFirst("div#turnstile-wrapper") != null -> PROTECTION_CAPTCHA
            content.selectFirst("div.cf-turnstile") != null -> PROTECTION_CAPTCHA
            content.selectFirst("script[src*=\"challenges.cloudflare.com\"]") != null -> PROTECTION_CAPTCHA
            content.selectFirst("script[src*=\"turnstile\"]") != null -> PROTECTION_CAPTCHA
            // Managed challenge (IUAM - "I'm Under Attack Mode")
            content.selectFirst("div#cf-please-wait") != null -> PROTECTION_CAPTCHA
            content.selectFirst("form[id=\"challenge-form\"][action*=\"__cf_chl\"]") != null -> PROTECTION_CAPTCHA
            // Fallback: CF server + 403/503 with challenge-like page title
            isCfServer && content.title().contains("Just a moment", ignoreCase = true) -> PROTECTION_CAPTCHA
            isCfServer && content.title().contains("Attention Required", ignoreCase = true) -> PROTECTION_CAPTCHA

            else -> PROTECTION_NOT_DETECTED
        }
    }

    public fun getClearanceCookie(cookieJar: CookieJar, url: String): String? {
        return cookieJar.loadForRequest(url.toHttpUrl()).find { it.name == CF_CLEARANCE }?.value
    }

    public fun isCloudFlareCookie(name: String): Boolean {
        return name.startsWith("cf_")
            || name.startsWith("_cf")
            || name.startsWith("__cf")
            || name == "csrftoken"
    }
}
