package eu.kanade.tachiyomi.extension.zh.jinmantiantang

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Log
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream

class JmImageInterceptor : Interceptor {
    companion object {
        private const val TAG = "JmImageInterceptor"

        // 需要进行CDN故障转移的HTTP状态码
        private val RETRY_STATUS_CODES = setOf(502, 503, 504, 520, 521, 522, 523, 524)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        // 判断是否是图片CDN请求（包含 /media/photos/ 或 /media/albums/）
        val isImageCdnRequest = url.contains("/media/photos/") || url.contains("/media/albums/")

        if (!isImageCdnRequest) {
            return chain.proceed(request)
        }

        val currentDomain = request.url.host
        val response = chain.proceed(request)

        // 如果请求成功，进行图片解扰处理
        if (response.isSuccessful) {
            return processScrambledImage(response, request)
        }

        // 如果是需要重试的状态码，尝试CDN故障转移
        if (response.code in RETRY_STATUS_CODES) {
            Log.d(TAG, "CDN请求失败: $currentDomain, 状态码: ${response.code}, URL: $url")
            JmApiClient.markImageDomainFailed(currentDomain)
            response.close()

            val availableDomains = JmApiClient.getAvailableDomains(excludeDomain = currentDomain)

            if (availableDomains.isEmpty()) {
                // 所有CDN都失败了，清空失败记录后用全部域名重试一次
                Log.d(TAG, "所有CDN域名都已标记失败，清空记录重试")
                JmApiClient.clearFailedDomains()
                val fallbackDomains = JmApiClient.getImageDomains().filter { it != currentDomain }
                if (fallbackDomains.isEmpty()) {
                    // 只有一个CDN域名，直接重试原始请求
                    return chain.proceed(request)
                }
                return tryAlternativeDomains(chain, request, currentDomain, fallbackDomains)
            }

            return tryAlternativeDomains(chain, request, currentDomain, availableDomains)
        }

        // 其他错误码直接返回
        return response
    }

    /**
     * 尝试使用备选CDN域名请求图片
     */
    private fun tryAlternativeDomains(
        chain: Interceptor.Chain,
        originalRequest: Request,
        failedDomain: String,
        availableDomains: List<String>,
    ): Response {
        val originalUrl = originalRequest.url.toString()

        for (domain in availableDomains) {
            val newUrl = originalUrl.replace(failedDomain, domain)
            Log.d(TAG, "尝试备选CDN: $domain, URL: $newUrl")

            val newRequest = originalRequest.newBuilder()
                .url(newUrl)
                .build()

            try {
                val response = chain.proceed(newRequest)
                if (response.isSuccessful) {
                    Log.d(TAG, "CDN故障转移成功: $domain")
                    return processScrambledImage(response, newRequest)
                }

                if (response.code in RETRY_STATUS_CODES) {
                    Log.d(TAG, "备选CDN也失败: $domain, 状态码: ${response.code}")
                    JmApiClient.markImageDomainFailed(domain)
                    response.close()
                    continue
                }

                // 非重试状态码，直接返回
                return response
            } catch (e: Exception) {
                Log.d(TAG, "备选CDN请求异常: $domain, 错误: ${e.message}")
                JmApiClient.markImageDomainFailed(domain)
                continue
            }
        }

        // 所有备选CDN都失败了，最后用原始请求再试一次（给用户一个明确的错误响应）
        Log.d(TAG, "所有备选CDN均失败，使用原始请求最终重试")
        val finalResponse = chain.proceed(originalRequest)
        if (finalResponse.isSuccessful) {
            return processScrambledImage(finalResponse, originalRequest)
        }
        return finalResponse
    }

    /**
     * 处理图片解扰（scramble）逻辑
     */
    private fun processScrambledImage(response: Response, request: Request): Response {
        val url = request.url.toString()
        val scrambleId = request.url.queryParameter("scramble_id")?.toIntOrNull()
        val aid = request.url.queryParameter("aid")?.toIntOrNull()
        val fullFilename = url.substringAfterLast("/").substringBefore("?")
        val filename = fullFilename.substringBeforeLast(".")

        if (scrambleId == null || aid == null || (!fullFilename.endsWith(".webp") && !fullFilename.endsWith(".jpg"))) {
            return response
        }

        val num = JmImageScrambler.getSegmentNum(scrambleId, aid, filename)
        if (num == 0) return response

        val srcBitmap = response.body.byteStream().use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: return response
        val decodedBitmap = Bitmap.createBitmap(srcBitmap.width, srcBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(decodedBitmap)

        val w = srcBitmap.width
        val h = srcBitmap.height
        val over = h % num
        val move = h / num

        for (i in 0 until num) {
            val ySrc = h - (move * (i + 1)) - over
            var yDst = move * i
            var currentMove = move

            if (i == 0) {
                currentMove += over
            } else {
                yDst += over
            }

            val slice = Bitmap.createBitmap(srcBitmap, 0, ySrc, w, currentMove)
            canvas.drawBitmap(slice, 0f, yDst.toFloat(), null)
            slice.recycle()
        }

        srcBitmap.recycle()

        val output = ByteArrayOutputStream()
        decodedBitmap.compress(Bitmap.CompressFormat.WEBP, 100, output)
        decodedBitmap.recycle()

        return response.newBuilder()
            .body(output.toByteArray().toResponseBody("image/webp".toMediaType()))
            .build()
    }
}
