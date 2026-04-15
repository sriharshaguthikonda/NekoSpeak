package com.nekospeak.tts.engine.misaki

/**
 * Faithful port of upstream Misaki's Japanese Num2Kana (num2kana.py).
 * Reference: https://github.com/hexgrad/misaki/blob/main/misaki/num2kana.py
 * Original: https://github.com/Greatdane/Convert-Numbers-to-Japanese (MIT License)
 *
 * Converts Arabic numbers to Japanese kana/romaji/kanji readings.
 * Works up to 9 figures (999,999,999).
 */
object Num2Kana {

    private val ROMAJI = mapOf(
        "." to "ten", "0" to "zero", "1" to "ichi", "2" to "ni", "3" to "san",
        "4" to "yon", "5" to "go", "6" to "roku", "7" to "nana", "8" to "hachi",
        "9" to "kyuu", "10" to "juu", "100" to "hyaku", "1000" to "sen",
        "10000" to "man", "100000000" to "oku", "300" to "sanbyaku",
        "600" to "roppyaku", "800" to "happyaku", "3000" to "sanzen",
        "8000" to "hassen", "01000" to "issen"
    )

    private val KANJI = mapOf(
        "." to "点", "0" to "零", "1" to "一", "2" to "二", "3" to "三",
        "4" to "四", "5" to "五", "6" to "六", "7" to "七", "8" to "八",
        "9" to "九", "10" to "十", "100" to "百", "1000" to "千",
        "10000" to "万", "100000000" to "億", "300" to "三百", "600" to "六百",
        "800" to "八百", "3000" to "三千", "8000" to "八千", "01000" to "一千"
    )

    private val HIRAGANA = mapOf(
        "." to "てん", "0" to "ゼロ", "1" to "いち", "2" to "に", "3" to "さん",
        "4" to "よん", "5" to "ご", "6" to "ろく", "7" to "なな", "8" to "はち",
        "9" to "きゅう", "10" to "じゅう", "100" to "ひゃく", "1000" to "せん",
        "10000" to "まん", "100000000" to "おく", "300" to "さんびゃく",
        "600" to "ろっぴゃく", "800" to "はっぴゃく", "3000" to "さんぜん",
        "8000" to "はっせん", "01000" to "いっせん"
    )

    private val KEY_DICT = mapOf("kanji" to KANJI, "hiragana" to HIRAGANA, "romaji" to ROMAJI)

    private fun lenOne(num: String, dict: Map<String, String>): String = dict[num]!!

    private fun lenTwo(num: String, dict: Map<String, String>): String {
        if (num[0] == '0') return lenOne(num[1].toString(), dict)
        if (num == "10") return dict["10"]!!
        if (num[0] == '1') return dict["10"] + " " + lenOne(num[1].toString(), dict)
        if (num[1] == '0') return lenOne(num[0].toString(), dict) + " " + dict["10"]
        return "${dict[num[0].toString()]} ${dict["10"]} ${dict[num[1].toString()]}"
    }

    private fun lenThree(num: String, dict: Map<String, String>): String {
        val parts = mutableListOf<String>()
        when (num[0]) {
            '1' -> parts.add(dict["100"]!!)
            '3' -> parts.add(dict["300"]!!)
            '6' -> parts.add(dict["600"]!!)
            '8' -> parts.add(dict["800"]!!)
            else -> { parts.add(dict[num[0].toString()]!!); parts.add(dict["100"]!!) }
        }
        if (num.substring(1) != "00") {
            if (num[1] == '0') parts.add(dict[num[2].toString()]!!)
            else parts.add(lenTwo(num.substring(1), dict))
        }
        return parts.joinToString(" ")
    }

    private fun lenFour(num: String, dict: Map<String, String>, standAlone: Boolean): String {
        var n = num
        if (n == "0000") return ""
        while (n.startsWith("0")) n = n.drop(1)
        if (n.length == 1) return lenOne(n, dict)
        if (n.length == 2) return lenTwo(n, dict)
        if (n.length == 3) return lenThree(n, dict)

        val parts = mutableListOf<String>()
        when {
            n[0] == '1' && standAlone -> parts.add(dict["1000"]!!)
            n[0] == '1' -> parts.add(dict["01000"]!!)
            n[0] == '3' -> parts.add(dict["3000"]!!)
            n[0] == '8' -> parts.add(dict["8000"]!!)
            else -> { parts.add(dict[n[0].toString()]!!); parts.add(dict["1000"]!!) }
        }
        if (n.substring(1) != "000") {
            if (n[1] == '0') parts.add(lenTwo(n.substring(2), dict))
            else parts.add(lenThree(n.substring(1), dict))
        }
        return parts.joinToString(" ")
    }

    private fun lenX(num: String, dict: Map<String, String>): String {
        val parts = mutableListOf<String>()
        val prefix = num.dropLast(4)
        when (prefix.length) {
            1 -> { parts.add(dict[prefix]!!); parts.add(dict["10000"]!!) }
            2 -> { parts.add(lenTwo(prefix, dict)); parts.add(dict["10000"]!!) }
            3 -> { parts.add(lenThree(prefix, dict)); parts.add(dict["10000"]!!) }
            4 -> { parts.add(lenFour(prefix, dict, false)); parts.add(dict["10000"]!!) }
            5 -> {
                parts.add(dict[prefix[0].toString()]!!)
                parts.add(dict["100000000"]!!)
                parts.add(lenFour(prefix.substring(1), dict, false))
                if (prefix.substring(1) != "0000") parts.add(dict["10000"]!!)
            }
        }
        parts.add(lenFour(num.takeLast(4), dict, false))
        return parts.joinToString(" ")
    }

    private fun doConvert(num: String, dict: Map<String, String>): String = when (num.length) {
        1 -> lenOne(num, dict)
        2 -> lenTwo(num, dict)
        3 -> lenThree(num, dict)
        4 -> lenFour(num, dict, true)
        else -> lenX(num, dict)
    }

    private fun splitPoint(num: String, dictChoice: String): String {
        val parts = num.split(".")
        val intPart = parts[0]
        val decPart = parts[1]
        val dict = KEY_DICT[dictChoice]!!
        val decWords = decPart.map { dict[it.toString()]!! }.joinToString(" ")

        // Small tsu exception for hiragana/romaji when ending in 0
        if (intPart.length >= 2 && intPart.last() == '0' && intPart[intPart.length - 2] != '0') {
            if (dictChoice == "hiragana") {
                var converted = convert(intPart, dictChoice)
                converted = converted.dropLast(1) + "っ"
                return converted + dict["."] + " $decWords"
            }
            if (dictChoice == "romaji") {
                var converted = convert(intPart, dictChoice)
                converted = converted.dropLast(1) + "t"
                return converted + dict["."] + " $decWords"
            }
        }
        return convert(intPart, dictChoice) + " " + dict["."] + " $decWords"
    }

    /** Convert an Arabic number string to Japanese reading. */
    fun convert(num: String, dictChoice: String = "hiragana"): String {
        var n = num.replace(",", "")
        if (n.length > 9) return "Number too long (max 9 digits)"

        while (n.startsWith("0") && n.length > 1) n = n.drop(1)

        val result = if ("." in n) splitPoint(n, dictChoice) else doConvert(n, KEY_DICT[dictChoice]!!)
        return if (dictChoice != "romaji") result.replace(" ", "") else result
    }

    /** Convert a Long to hiragana. */
    fun convert(num: Long): String = convert(num.toString(), "hiragana")
}