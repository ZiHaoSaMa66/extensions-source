package eu.kanade.tachiyomi.extension.zh.jinmantiantang

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

@Serializable
class JmApiResponseDto(
    val data: String,
)

@Serializable
class AlbumDto(
    val id: Int,
    val name: String,
    @Serializable(with = StringOrListSerializer::class)
    val author: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val description: String? = null,
    val series: List<ChapterDto> = emptyList(),
)

@Serializable
class ChapterDto(
    val id: Int,
    val name: String,
    val sort: String,
)

@Serializable
class SearchResultDto(
    val content: List<SearchItemDto> = emptyList(),
    val total: Int,
)

@Serializable
class SearchItemDto(
    val id: String,
    val name: String,
    @Serializable(with = StringOrListSerializer::class)
    val author: List<String> = emptyList(),
    @Serializable(with = CategorySerializer::class)
    @SerialName("category")
    val category: List<String> = emptyList(),
)

@Serializable
class ChapterPageDto(
    val id: Int,
    val images: List<String> = emptyList(),
)

/**
 * 处理 JSON 中字段可能是字符串或字符串数组的情况
 * 例如 author 字段可能是 "Author1" 或 ["Author1", "Author2"]
 */
object StringOrListSerializer : KSerializer<List<String>> {
    override val descriptor: SerialDescriptor = ListSerializer(String.serializer()).descriptor

    override fun serialize(encoder: Encoder, value: List<String>) {
        ListSerializer(String.serializer()).serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): List<String> {
        val jsonDecoder = decoder as JsonDecoder
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonArray -> element.map { it.jsonPrimitive.content }
            is JsonPrimitive -> {
                val content = element.content
                if (content.isEmpty()) emptyList() else listOf(content)
            }
            else -> emptyList()
        }
    }
}

/**
 * 处理 JSON 中 category 字段可能是对象、对象数组或字符串的情况
 * API 返回格式可能是:
 * - 对象: {"id": "1", "title": "同人"}
 * - 对象数组: [{"id": "1", "title": "同人"}, ...]
 * - 字符串: "同人"
 * - 字符串数组: ["同人", "韩漫"]
 */
object CategorySerializer : KSerializer<List<String>> {
    override val descriptor: SerialDescriptor = ListSerializer(String.serializer()).descriptor

    override fun serialize(encoder: Encoder, value: List<String>) {
        ListSerializer(String.serializer()).serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): List<String> {
        val jsonDecoder = decoder as JsonDecoder
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonArray -> element.mapNotNull { item ->
                when (item) {
                    is JsonObject -> item["title"]?.jsonPrimitive?.content
                    is JsonPrimitive -> item.content
                    else -> null
                }
            }
            is JsonObject -> listOfNotNull(element["title"]?.jsonPrimitive?.content)
            is JsonPrimitive -> if (element.content.isEmpty()) emptyList() else listOf(element.content)
        }
    }
}
