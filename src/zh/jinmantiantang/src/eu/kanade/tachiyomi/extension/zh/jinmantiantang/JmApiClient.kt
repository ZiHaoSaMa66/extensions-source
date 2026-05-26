package eu.kanade.tachiyomi.extension.zh.jinmantiantang

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object JmApiClient {
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

    data class AlbumDetail(
        val id: Int,
        val title: String,
        val author: String,
        val genre: String,
        val description: String,
    )

    data class ChapterEntry(
        val id: Int,
        val name: String,
        val sort: Int,
    )

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

    fun parseAlbumDetail(json: JSONObject): SManga {
        val detail = parseAlbumDetailRaw(json)
        return SManga.create().apply {
            url = "/album/${detail.id}/"
            title = detail.title
            author = detail.author
            genre = detail.genre
            description = detail.description
            thumbnail_url = "https://${getImageDomain()}/media/albums/${detail.id}_3x4.jpg"
        }
    }

    fun parseAlbumDetailRaw(json: JSONObject): AlbumDetail = AlbumDetail(
        id = json.optInt("id", json.optString("id", "0").toIntOrNull() ?: 0),
        title = json.optString("name", ""),
        author = json.optJSONArray("author")?.let { arr ->
            (0 until arr.length()).joinToString(", ") { arr.getString(it) }
        } ?: json.optString("author", ""),
        genre = json.optJSONArray("tags")?.let { arr ->
            (0 until arr.length()).joinToString(", ") { arr.getString(it) }
        } ?: "",
        description = if (json.isNull("description")) "" else json.optString("description", ""),
    )

    fun parseChapterList(json: JSONObject): List<SChapter> {
        val albumId = json.optString("id", json.optInt("id", 0).toString())
        val series = json.optJSONArray("series")

        if (series == null || series.length() == 0) {
            return listOf(
                SChapter.create().apply {
                    url = "/photo/$albumId"
                    name = json.getString("name")
                    chapter_number = 1f
                },
            )
        }

        return (0 until series.length()).map { i ->
            val chap = series.getJSONObject(i)
            val chapId = chap.optString("id", chap.optInt("id", 0).toString())
            SChapter.create().apply {
                url = "/photo/$chapId"
                name = chap.getString("name")
                chapter_number = chap.optString("sort", "${i + 1}").toFloatOrNull() ?: (i + 1).toFloat()
            }
        }.reversed()
    }

    fun parseChapterListRaw(json: JSONObject): List<ChapterEntry> {
        val albumId = json.getInt("id")
        val series = json.optJSONArray("series")

        if (series == null || series.length() == 0) {
            return listOf(ChapterEntry(id = albumId, name = json.getString("name"), sort = 1))
        }

        return (0 until series.length()).map { i ->
            val chap = series.getJSONObject(i)
            ChapterEntry(
                id = chap.getInt("id"),
                name = chap.getString("name"),
                sort = chap.optInt("sort", i + 1),
            )
        }.reversed()
    }

    fun parseSearchPage(json: JSONObject): MangasPage {
        val content = json.optJSONArray("content")
        if (content == null || content.length() == 0) {
            return MangasPage(emptyList(), false)
        }

        val mangas = (0 until content.length()).map { i ->
            val item = content.getJSONObject(i)
            SManga.create().apply {
                val id = item.getString("id")
                url = "/album/$id/"
                title = item.getString("name")
                author = item.optJSONArray("author")?.let { arr ->
                    (0 until arr.length()).joinToString(", ") { idx -> arr.getString(idx) }
                } ?: item.optString("author", "")
                genre = item.optJSONArray("category")?.let { arr ->
                    (0 until arr.length()).joinToString(", ") { idx -> arr.getString(idx) }
                } ?: ""
                thumbnail_url = "https://${getImageDomain()}/media/albums/${id}_3x4.jpg"
            }
        }

        val total = json.optInt("total", 0)
        val hasNextPage = mangas.size < total

        return MangasPage(mangas, hasNextPage)
    }

    fun buildSearchUrl(query: String, page: Int, orderBy: String, time: String, mainTag: Int): String = buildApiUrl("$API_SEARCH?search_query=$query&page=$page&o=$orderBy&t=$time&main_tag=$mainTag")

    fun buildCategoriesFilterUrl(page: Int, category: String, orderBy: String, time: String): String {
        val o = if (time != "a") "${orderBy}_$time" else orderBy
        return buildApiUrl("$API_CATEGORIES_FILTER?page=$page&order=&c=$category&o=$o")
    }

    fun buildPopularUrl(page: Int): String = buildCategoriesFilterUrl(page, "", "mv", "a")

    fun buildLatestUrl(page: Int): String = buildCategoriesFilterUrl(page, "", "mr", "a")

    fun buildApiUrl(path: String, domain: String = "www.cdnhjk.net"): String = "https://$domain$path"

    fun decryptApiResponse(responseBody: String, ts: Long): String {
        val json = JSONObject(responseBody)
        val data = json.getString("data")
        return JmCrypto.decryptData(data, ts)
    }

    /**
     * 从响应中提取请求时使用的时间戳，用于解密
     * 时间戳存储在请求的 tokenparam header 中，格式为 "ts,version"
     */
    fun extractTsFromResponse(response: Response): Long {
        val tokenparam = response.request.header("tokenparam") ?: return System.currentTimeMillis() / 1000
        return tokenparam.substringBefore(",").toLongOrNull() ?: (System.currentTimeMillis() / 1000)
    }
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
