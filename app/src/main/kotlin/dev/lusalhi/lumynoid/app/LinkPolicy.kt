package dev.lusalhi.lumynoid.app

import java.util.Locale

private val OPENABLE_SCHEMES = setOf("http", "https", "mailto")

/**
 * AD-9: the only links the app will ever hand to another app. Returns the trimmed URL when its scheme is
 * `http`, `https` or `mailto` (any case) and something follows the scheme, otherwise null.
 */
fun openableUri(url: String): String? {
    val trimmed = url.trim()
    val colon = trimmed.indexOf(':')
    if (colon <= 0 || colon == trimmed.length - 1) return null
    val scheme = trimmed.substring(0, colon).lowercase(Locale.ROOT)
    return if (scheme in OPENABLE_SCHEMES) trimmed else null
}

/**
 * Label for a remote image shown as a link. [template] is the localized "Image: %1$s" string; the alt text
 * fills it, or the URL when the alt text is missing or blank.
 */
fun imageLinkLabel(alt: String?, url: String, template: String): String =
    String.format(Locale.ROOT, template, alt?.trim()?.takeIf { it.isNotEmpty() } ?: url)
