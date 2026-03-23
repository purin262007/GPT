package com.tiktokdownloader.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

class VideoDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Language", "ja-JP,ja;q=0.9,en;q=0.8")
                .header("Referer", "https://www.tiktok.com/")
                .build()
            chain.proceed(request)
        }
        .build()

    data class VideoInfo(
        val videoUrl: String,
        val title: String,
        val author: String
    )

    suspend fun resolveShortUrl(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        val response = client.newCall(request).execute()
        response.use {
            it.request.url.toString()
        }
    }

    suspend fun fetchVideoInfo(url: String): VideoInfo = withContext(Dispatchers.IO) {
        val resolvedUrl = if (url.contains("vm.tiktok.com") ||
            url.contains("vt.tiktok.com") ||
            url.contains("/t/")
        ) {
            resolveShortUrl(url)
        } else {
            url
        }

        val doc = Jsoup.connect(resolvedUrl)
            .userAgent(USER_AGENT)
            .header("Accept-Language", "ja-JP,ja;q=0.9")
            .followRedirects(true)
            .timeout(30000)
            .get()

        val title = doc.select("meta[property=og:description]").attr("content")
            .ifEmpty { doc.title() }
        val author = doc.select("meta[property=og:title]").attr("content")
            .ifEmpty { "unknown" }

        // 動画URLをメタタグから取得
        val videoUrl = doc.select("meta[property=og:video]").attr("content")
            .ifEmpty {
                doc.select("meta[property=og:video:url]").attr("content")
                    .ifEmpty {
                        doc.select("video source").attr("src")
                            .ifEmpty { extractVideoUrlFromPage(resolvedUrl) }
                    }
            }

        if (videoUrl.isEmpty()) {
            throw Exception("動画URLを取得できませんでした。URLを確認してください。")
        }

        VideoInfo(
            videoUrl = videoUrl,
            title = title.take(100),
            author = author
        )
    }

    private suspend fun extractVideoUrlFromPage(url: String): String = withContext(Dispatchers.IO) {
        // APIを使用して動画URLを取得する方法
        val apiUrl = "https://www.tiktok.com/oembed?url=$url"
        val request = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", USER_AGENT)
            .build()

        val response = client.newCall(request).execute()
        response.use { resp ->
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                // oEmbedからthumbnailは取れるが、動画URLは直接取れないため、
                // 元のURLからリダイレクトで取得を試みる
                val videoRequest = Request.Builder()
                    .url(url)
                    .header("User-Agent", MOBILE_USER_AGENT)
                    .build()

                val videoResponse = client.newCall(videoRequest).execute()
                videoResponse.use { vResp ->
                    val html = vResp.body?.string() ?: ""
                    val regex = "\"playAddr\":\"([^\"]+)\"".toRegex()
                    val match = regex.find(html)
                    match?.groupValues?.get(1)
                        ?.replace("\\u002F", "/")
                        ?.replace("\\u0026", "&")
                        ?: ""
                }
            } else {
                ""
            }
        }
    }

    suspend fun downloadVideo(
        videoUrl: String,
        fileName: String,
        onProgress: (Int) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(videoUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.tiktok.com/")
            .build()

        val response = client.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw Exception("ダウンロードに失敗しました (HTTP ${resp.code})")
            }

            val body = resp.body ?: throw Exception("レスポンスが空です")
            val contentLength = body.contentLength()
            val sanitizedFileName = fileName.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
                .take(80) + ".mp4"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(body.byteStream(), sanitizedFileName, contentLength, onProgress)
            } else {
                saveToExternalStorage(body.byteStream(), sanitizedFileName, contentLength, onProgress)
            }
        }
    }

    private fun saveToMediaStore(
        inputStream: java.io.InputStream,
        fileName: String,
        contentLength: Long,
        onProgress: (Int) -> Unit
    ): String {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/TikTokDownloader")
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw Exception("ファイルの作成に失敗しました")

        resolver.openOutputStream(uri)?.use { outputStream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytesRead = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                if (contentLength > 0) {
                    val progress = ((totalBytesRead * 100) / contentLength).toInt()
                    onProgress(progress)
                }
            }
        }

        return uri.toString()
    }

    @Suppress("DEPRECATION")
    private fun saveToExternalStorage(
        inputStream: java.io.InputStream,
        fileName: String,
        contentLength: Long,
        onProgress: (Int) -> Unit
    ): String {
        val downloadDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "TikTokDownloader"
        )
        if (!downloadDir.exists()) downloadDir.mkdirs()

        val outputFile = File(downloadDir, fileName)
        FileOutputStream(outputFile).use { outputStream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytesRead = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                if (contentLength > 0) {
                    val progress = ((totalBytesRead * 100) / contentLength).toInt()
                    onProgress(progress)
                }
            }
        }

        return outputFile.absolutePath
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    }
}
