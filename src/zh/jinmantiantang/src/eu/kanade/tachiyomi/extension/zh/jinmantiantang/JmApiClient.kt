package eu.kanade.tachiyomi.extension.zh.jinmantiantang

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

object JmApiClient {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val IMAGE_DOMAINS = listOf(
        "cdn-msp.jmapiproxy1.cc",
        "cdn-msp.jmapiproxy2.cc",
        "cdn-msp2.jmapiproxy2.cc",
        "cdn-msp3.jmapiproxy2.cc",
        "cdn-msp.jmapinodeudzn.net",
        "cdn-msp3.jmapinodeudzn.net",
    )

    const val API_SEARCH = "/search"
    const val API_CATEGORIES_FILTER = "/categories/filter"
    const val API_ALBUM = "/album"
    const val API_CHAPTER = "/chapter"

    // 记录失败的图片CDN域名（线程安全）
    private val failedImageDomains = ConcurrentHashMap<String, Long>()
    private const val IMAGE_DOMAIN_FAILURE_EXPIRY_MS = 5 * 60 * 1000L

    fun getImageDomain(): String {
        val now = System.currentTimeMillis()
        // 清理过期的失败记录
        failedImageDomains.entries.removeAll { (_, time) -> now - time > IMAGE_DOMAIN_FAILURE_EXPIRY_MS }
        // 优先选择未被标记失败的域名
        val available = IMAGE_DOMAINS.filter { domain -> !failedImageDomains.containsKey(domain) }
        return if (available.isNotEmpty()) available.random() else IMAGE_DOMAINS.random()
    }

    fun getImageDomains(): List<String> = IMAGE_DOMAINS

    /**
     * 获取当前可用的CDN域名列表（排除近期失败的域名）
     */
    fun getAvailableDomains(excludeDomain: String? = null): List<String> {
        val now = System.currentTimeMillis()
        // 清理过期的失败记录
        failedImageDomains.entries.removeAll { (_, time) -> now - time > IMAGE_DOMAIN_FAILURE_EXPIRY_MS }
        return IMAGE_DOMAINS.filter { domain ->
            domain != excludeDomain && !failedImageDomains.containsKey(domain)
        }
    }

    fun markImageDomainFailed(domain: String) {
        failedImageDomains[domain] = System.currentTimeMillis()
    }

    fun clearFailedDomains() {
        failedImageDomains.clear()
    }

    fun parseAlbumDetail(dto: AlbumDto): SManga = SManga.create().apply {
        url = "/album/${dto.id}/"
        title = dto.name
        author = dto.author.joinToString()
        genre = dto.tags.joinToString()
        description = dto.description ?: ""
        thumbnail_url = "https://${getImageDomain()}/media/albums/${dto.id}_3x4.jpg"
    }

    fun parseChapterList(dto: AlbumDto): List<SChapter> {
        if (dto.series.isEmpty()) {
            return listOf(
                SChapter.create().apply {
                    url = "/photo/${dto.id}"
                    name = dto.name
                    chapter_number = 1f
                },
            )
        }

        return dto.series.mapIndexed { i, chap ->
            SChapter.create().apply {
                url = "/photo/${chap.id}"
                name = chap.name
                chapter_number = chap.sort.toFloatOrNull() ?: (i + 1).toFloat()
            }
        }.reversed()
    }

    fun parseSearchPage(dto: SearchResultDto): MangasPage {
        if (dto.content.isEmpty()) {
            return MangasPage(emptyList(), false)
        }

        val mangas = dto.content.map { item ->
            SManga.create().apply {
                url = "/album/${item.id}/"
                title = item.name
                author = item.author.joinToString()
                genre = item.category.joinToString()
                thumbnail_url = "https://${getImageDomain()}/media/albums/${item.id}_3x4.jpg"
            }
        }

        val hasNextPage = mangas.size < dto.total

        return MangasPage(mangas, hasNextPage)
    }

    fun parsePageList(dto: ChapterPageDto): List<Page> {
        val photoId = dto.id.toString()
        if (photoId == "0") {
            throw Exception("章节数据无效")
        }
        if (dto.images.isEmpty()) {
            throw Exception("无法获取章节图片列表 (photo_id=$photoId)")
        }
        val imageDomain = getImageDomain()
        return dto.images.mapIndexedNotNull { i, imgName ->
            if (imgName.isEmpty()) return@mapIndexedNotNull null
            val imageUrl = "https://$imageDomain/media/photos/$photoId/$imgName?scramble_id=$SCRAMBLE_ID_DEFAULT&aid=$photoId"
            Page(i, imageUrl = imageUrl)
        }
    }

    fun buildSearchUrl(query: String, page: Int, orderBy: String, time: String, mainTag: Int): String = buildApiUrl("$API_SEARCH?search_query=$query&page=$page&o=$orderBy&t=$time&main_tag=$mainTag")

    fun buildCategoriesFilterUrl(page: Int, category: String, orderBy: String, time: String): String {
        val o = if (time != "a") "${orderBy}_$time" else orderBy
        return buildApiUrl("$API_CATEGORIES_FILTER?page=$page&order=&c=$category&o=$o")
    }

    fun buildPopularUrl(page: Int): String = buildCategoriesFilterUrl(page, "", "mv", "a")

    fun buildLatestUrl(page: Int): String = buildCategoriesFilterUrl(page, "", "mr", "a")

    fun buildApiUrl(path: String, domain: String = "www.cdnhjk.net"): String = "https://$domain$path"

    /**
     * 解密 API 响应：先提取加密的 data 字段，再用 AES 解密
     */
    fun decryptResponseBody(responseBody: String, ts: Long): String {
        val apiResponse = json.decodeFromString<JmApiResponseDto>(responseBody)
        return JmCrypto.decryptData(apiResponse.data, ts)
    }

    /**
     * 从响应中提取请求时使用的时间戳，用于解密
     * 时间戳存储在请求的 tokenparam header 中，格式为 "ts,version"
     */
    fun extractTsFromResponse(response: Response): Long {
        val tokenparam = response.request.header("tokenparam") ?: return System.currentTimeMillis() / 1000
        return tokenparam.substringBefore(",").toLongOrNull() ?: (System.currentTimeMillis() / 1000)
    }

    private const val SCRAMBLE_ID_DEFAULT = 220980
}

class JmTokenInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val ts = System.currentTimeMillis() / 1000

        val newRequest = request.newBuilder()
            .header("token", JmCrypto.generateToken(ts))
            .header("tokenparam", JmCrypto.generateTokenParam(ts))
            .build()

        return chain.proceed(newRequest)
    }
}
