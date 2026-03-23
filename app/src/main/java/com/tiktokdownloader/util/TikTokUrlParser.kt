package com.tiktokdownloader.util

import java.util.regex.Pattern

object TikTokUrlParser {

    private val TIKTOK_PATTERNS = listOf(
        Pattern.compile("https?://(?:www\\.)?tiktok\\.com/@[^/]+/video/(\\d+)"),
        Pattern.compile("https?://(?:vm|vt)\\.tiktok\\.com/([a-zA-Z0-9]+)"),
        Pattern.compile("https?://(?:www\\.)?tiktok\\.com/t/([a-zA-Z0-9]+)"),
        Pattern.compile("https?://m\\.tiktok\\.com/v/(\\d+)")
    )

    fun isValidTikTokUrl(url: String): Boolean {
        val trimmed = url.trim()
        return TIKTOK_PATTERNS.any { it.matcher(trimmed).find() }
    }

    fun extractUrlFromText(text: String): String? {
        val urlPattern = Pattern.compile("https?://[\\w.-]+\\.tiktok\\.com/[^\\s]+")
        val matcher = urlPattern.matcher(text)
        return if (matcher.find()) matcher.group() else null
    }

    fun cleanUrl(url: String): String {
        return url.trim().split("?").first().split("#").first()
    }
}
