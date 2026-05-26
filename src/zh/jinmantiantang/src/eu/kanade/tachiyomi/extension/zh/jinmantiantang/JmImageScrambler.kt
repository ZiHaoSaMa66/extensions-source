package eu.kanade.tachiyomi.extension.zh.jinmantiantang

object JmImageScrambler {
    private const val SCRAMBLE_ID = 220980
    private const val SCRAMBLE_268850 = 268850
    private const val SCRAMBLE_421926 = 421926

    fun getSegmentNum(scrambleId: Int, aid: Int, filename: String): Int {
        if (aid < scrambleId) return 0
        if (aid < SCRAMBLE_268850) return 10

        val x = if (aid < SCRAMBLE_421926) 10 else 8
        val s = "$aid$filename"
        val md5 = JmCrypto.md5Hex(s)
        val num = md5.last().code % x
        return num * 2 + 2
    }
}
