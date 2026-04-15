package com.nekospeak.tts.engine.misaki

import android.util.Log

/**
 * Faithful port of upstream Misaki's Chinese text normalization (zh_normalization/).
 * Reference: https://github.com/hexgrad/misaki/tree/main/misaki/zh_normalization
 * Original: https://github.com/PaddlePaddle/PaddleSpeech (Apache 2.0)
 *
 * Sub-modules ported:
 * - char_convert.py: Traditional→Simplified Chinese conversion
 * - num.py: Number verbalization into Chinese characters
 * - chronology.py: Date/time verbalization
 * - phonecode.py: Phone number verbalization
 * - quantifier.py: Temperature and measurement unit verbalization
 * - constants.py: Full-width→half-width character maps
 * - text_normalization.py: Main normalization pipeline
 *
 * Used by ZhG2P for Chinese text preprocessing before pinyin lookup.
 */
object ZhNormalization {
    private const val TAG = "ZhNormalization"

    // === num.py: Number verbalization ===
    private val DIGITS = mapOf(
        '0' to '零', '1' to '一', '2' to '二', '3' to '三', '4' to '四',
        '5' to '五', '6' to '六', '7' to '七', '8' to '八', '9' to '九'
    )

    private val UNITS = mapOf(
        1 to '十', 2 to '百', 3 to '千', 4 to '万', 8 to '亿'
    )

    /** Verbalize a cardinal number string to Chinese characters. */
    fun verbalizeCardinal(num: String): String {
        val n = num.toLongOrNull() ?: return num
        if (n == 0L) return "零"
        return num2str(n.toString())
    }

    /** Verbalize number digit by digit. */
    fun verbalizeDigit(num: String, altOne: Boolean = false): String {
        return num.map { ch ->
            when {
                ch == '1' && altOne -> '幺'
                ch in DIGITS -> DIGITS[ch]!!
                ch == '.' -> '点'
                else -> ch
            }
        }.joinToString("")
    }

    /** Convert number string to Chinese reading. */
    fun num2str(num: String): String {
        val n = num.toLongOrNull() ?: return num
        if (n == 0L) return "零"

        val negative = n < 0
        var absStr = if (n < 0) (-n).toString() else n.toString()

        val result = StringBuilder()
        if (negative) result.append("负")

        // Group into 4-digit chunks from right
        val groups = absStr.reversed().chunked(4).map { it.reversed() }.reversed()

        for ((gi, group) in groups.withIndex()) {
            val unitIndex = (groups.size - 1 - gi) * 4
            val groupVal = group.toLongOrNull() ?: continue
            if (groupVal == 0L) {
                result.append('零')
                continue
            }
            result.append(verbalizeGroup(group, groupVal))
            // Add unit (万, 亿)
            when {
                unitIndex + 4 <= 4 && groups.size > 1 -> { /* no unit for thousands */ }
                unitIndex + 4 == 8 -> result.append('亿')
                unitIndex + 4 == 4 && groups.size > 1 -> result.append('万')
            }
        }

        return result.toString()
    }

    private fun verbalizeGroup(digits: String, value: Long): String {
        val result = StringBuilder()
        val len = digits.length
        var prevZero = false

        for (i in digits.indices) {
            val d = digits[i]
            val unitPos = len - 1 - i

            if (d == '0') {
                if (!prevZero && i < len - 1) {
                    result.append('零')
                    prevZero = true
                }
                continue
            }

            prevZero = false
            if (d == '1' && unitPos == 1 && len == 2 && digits.length <= 2) {
                // 十一 → 十一, not 一十一
                result.append(UNITS[unitPos])
            } else if (d == '1' && unitPos == 1 && len == 2 && value < 20) {
                result.append(UNITS[unitPos])
            } else {
                result.append(DIGITS[d])
                if (unitPos in UNITS) {
                    result.append(UNITS[unitPos])
                }
            }
        }
        return result.toString()
    }

    // === chronology.py: Date/time verbalization ===
    private val RE_DATE = Regex("""(\d{4})[年/\-.](\d{1,2})[月/\-.](\d{1,2})[日号]?""")
    private val RE_DATE2 = Regex("""(\d{4})[年/\-.](\d{1,2})[月/\-.]?""")
    private val RE_TIME = Regex("""(\d{1,2}):(\d{1,2})(?::(\d{1,2}))?""")
    private val RE_TIME_RANGE = Regex("""(\d{1,2})[点时](\d{1,2})?[分]?[~—\-至到](\d{1,2})[点时](\d{1,2})?[分]?""")

    fun replaceDate(text: String): String {
        return RE_DATE.replace(text) { m ->
            val y = verbalizeDigit(m.groupValues[1])
            val mo = verbalizeDigit(m.groupValues[2])
            val d = verbalizeDigit(m.groupValues[3])
            "${y}年${mo}月${d}日"
        }
    }

    fun replaceDate2(text: String): String {
        return RE_DATE2.replace(text) { m ->
            val y = verbalizeDigit(m.groupValues[1])
            val mo = verbalizeDigit(m.groupValues[2])
            "${y}年${mo}月"
        }
    }

    fun replaceTime(text: String): String {
        return RE_TIME.replace(text) { m ->
            val h = verbalizeDigit(m.groupValues[1], altOne = true)
            val min = verbalizeDigit(m.groupValues[2], altOne = true)
            val sec = m.groupValues[3]?.let { verbalizeDigit(it, altOne = true) }
            if (sec != null && sec.isNotEmpty()) "${h}点${min}分${sec}秒"
            else "${h}点${min}分"
        }
    }

    // === phonecode.py: Phone number verbalization ===
    private val RE_MOBILE = Regex("""(?<!\d)((\+?86 ?)?1([38]\d|5[0-35-9]|7[678]|9[89])\d{8})(?!\d)""")
    private val RE_TELEPHONE = Regex("""(?<!\d)((0(10|2[1-3]|[3-9]\d{2})-?)?[1-9]\d{6,7})(?!\d)""")

    fun replacePhone(text: String): String {
        var result = RE_MOBILE.replace(text) { m ->
            verbalizeDigit(m.groupValues[1].replace("+86", "").replace(" ", ""), altOne = true)
        }
        result = RE_TELEPHONE.replace(result) { m ->
            val phone = m.groupValues[1].replace("-", "")
            verbalizeDigit(phone, altOne = true)
        }
        return result
    }

    // === quantifier.py: Temperature and measurement ===
    private val RE_TEMPERATURE = Regex("""(-?)(\d+(\.\d+)?)(°C|℃|度|摄氏度)""")
    private val MEASURE_DICT = mapOf(
        "cm2" to "平方厘米", "cm²" to "平方厘米", "cm3" to "立方厘米", "cm³" to "立方厘米",
        "cm" to "厘米", "db" to "分贝", "ds" to "毫秒", "kg" to "千克", "km" to "千米",
        "m2" to "平方米", "m²" to "平方米", "m³" to "立方米", "m3" to "立方米",
        "ml" to "毫升", "m" to "米", "mm" to "毫米", "s" to "秒", "h" to "小时", "mg" to "毫克"
    )

    fun replaceTemperature(text: String): String {
        return RE_TEMPERATURE.replace(text) { m ->
            val sign = if (m.groupValues[1] == "-") "零下" else ""
            val temp = num2str(m.groupValues[2].replace(".", "").toLongOrNull()?.toString() ?: m.groupValues[2])
            val unit = if (m.groupValues[4] == "摄氏度") "摄氏度" else "度"
            "$sign${temp}${unit}"
        }
    }

    fun replaceMeasure(text: String): String {
        var result = text
        for ((notation, chinese) in MEASURE_DICT.sortedByDescending { it.key.length }) {
            result = result.replace(notation, chinese)
        }
        return result
    }

    // === char_convert.py: Traditional→Simplified Chinese ===
    // Full mapping loaded from the inline character strings in char_convert.py
    // We store the simplified→traditional pairs for t2s conversion
    private var t2sMap: Map<Char, Char> = emptyMap()
    private var s2tMap: Map<Char, Char> = emptyMap()
    @Volatile private var charConvertLoaded = false

    fun loadCharConvert(context: android.content.Context) {
        if (charConvertLoaded) return
        try {
            context.assets.open("zh_t2s.txt").bufferedReader().use { reader ->
                val map = mutableMapOf<Char, Char>()
                reader.forEachLine { line ->
                    val parts = line.split("\t")
                    if (parts.size == 2 && parts[0].length == 1 && parts[1].length == 1) {
                        map[parts[0][0]] = parts[1][0]
                    }
                }
                t2sMap = map
                s2tMap = map.entries.associate { it.value to it.key }
            }
            charConvertLoaded = true
            Log.d(TAG, "Loaded t2s map: ${t2sMap.size} entries")
        } catch (e: Exception) {
            Log.w(TAG, "zh_t2s.txt not found, traditional→simplified conversion unavailable")
            charConvertLoaded = true
        }
    }

    fun traditionalToSimplified(text: String): String {
        if (t2sMap.isEmpty()) return text
        return text.map { ch -> t2sMap[ch] ?: ch }.joinToString("")
    }

    fun simplifiedToTraditional(text: String): String {
        if (s2tMap.isEmpty()) return text
        return text.map { ch -> s2tMap[ch] ?: ch }.joinToString("")
    }

    // === constants.py: Full-width→half-width ===
    private val F2H_DIGITS = (0..9).associate { (0xFF10 + it).toChar() to ('0' + it) }
    private val F2H_ASCII = (('A'..'Z') + ('a'..'z')).associate { (it.code + 0xFEE0).toChar() to it }
    private val F2H_SPACE = mapOf('\u3000' to ' ')

    // === Main normalization pipeline ===
    fun normalize(text: String): String {
        var result = text

        // 1. Traditional → Simplified Chinese
        result = traditionalToSimplified(result)

        // 2. Full-width → Half-width
        result = result.map { ch ->
            F2H_DIGITS[ch] ?: F2H_ASCII[ch] ?: F2H_SPACE[ch] ?: ch
        }.joinToString("")

        // 3. Replace dates
        result = replaceDate(result)
        result = replaceDate2(result)

        // 4. Replace times
        result = replaceTime(result)

        // 5. Replace phone numbers
        result = replacePhone(result)

        // 6. Replace temperature
        result = replaceTemperature(result)

        // 7. Replace measurements
        result = replaceMeasure(result)

        // 8. Replace cardinal numbers (only standalone numbers)
        result = result.replace(Regex("""\b(\d+)\b""")) { m ->
            verbalizeCardinal(m.groupValues[1])
        }

        return result
    }
}