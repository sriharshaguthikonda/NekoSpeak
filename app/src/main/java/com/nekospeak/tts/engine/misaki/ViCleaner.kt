package com.nekospeak.tts.engine.misaki

import android.util.Log
import java.util.Locale

/**
 * Faithful port of upstream Misaki's ViCleaner (vi_cleaner/).
 * Reference: https://github.com/hexgrad/misaki/tree/main/misaki/vi_cleaner
 *
 * Vietnamese text normalization pipeline:
 * 1. Whitespace collapse
 * 2. Abbreviation expansion (ko→không, v/v→về việc, etc.)
 * 3. Roman number conversion (III→3)
 * 4. Acronym expansion (TP.HCM→thành phố hồ chí minh, TV→ti vi, etc.)
 * 5. Date/time normalization (15/4/2026→ngày mười lăm tháng tư năm hai nghìn hai mươi sáu)
 * 6. Measurement unit expansion (km→ki lô mét, GB→gi ga bai, etc.)
 * 7. Currency expansion ($→đô la, €→ơ rô, ₫→đồng, etc.)
 * 8. Number expansion (123→một trăm hai mươi ba, phone numbers digit-by-digit)
 * 9. Letter spelling (chữ a→ây, chữ b→bê, etc.)
 * 10. Slash normalization (/→trên)
 * 11. Lowercase
 *
 * All sub-modules ported from the 14 Python files in vi_cleaner/.
 */
class ViCleaner(
    private val cleanAbbr: Boolean = true,
    private val cleanAcronym: Boolean = true
) {
    companion object {
        private const val TAG = "ViCleaner"

        // --- Dynamic dictionaries (loaded from JSON assets) ---
        private var viAcronyms: Map<String, String> = emptyMap()
        private var viTeencode: Map<String, String> = emptyMap()
        private var viSymbols: Map<String, String> = emptyMap()
        @Volatile private var dictsLoaded = false

        /** Load Vietnamese dictionaries from assets. Call once during init. */
        fun loadDictionaries(context: android.content.Context) {
            if (dictsLoaded) return
            try {
                viAcronyms = loadJsonDict(context, "vi_acronyms.json")
                viTeencode = loadJsonDict(context, "vi_teencode.json")
                viSymbols = loadJsonDict(context, "vi_symbols.json")
                dictsLoaded = true
                Log.d(TAG, "Loaded vi_acronyms: ${viAcronyms.size}, vi_teencode: ${viTeencode.size}, vi_symbols: ${viSymbols.size}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load Vietnamese dictionaries: ${e.message}")
                dictsLoaded = true // Don't retry
            }
        }

        private fun loadJsonDict(context: android.content.Context, filename: String): Map<String, String> {
            val map = mutableMapOf<String, String>()
            context.assets.open(filename).bufferedReader().use { reader ->
                val json = reader.readText()
                // Simple JSON object parser: {"key": "value", ...}
                val entries = json.trim().removeSurrounding("{", "}")
                val regex = Regex("""\s*"([^"]+)"\s*:\s*"([^"]*)"\s*""")
                for (line in entries.split(",")) {
                    val match = regex.find(line)
                    if (match != null) {
                        map[match.groupValues[1]] = match.groupValues[2]
                    }
                }
            }
            return map
        }

        // --- Vietnamese character sets (from symbol_vi.py) ---
        private val VI_CHARS_SET = (
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXY" +
            "ỹỷỵỳựữửừứủụợỡởờớộỗổồốỏọịỉệễểềếẽẻẹặẵẳằắậẫẩầấảạươũĩđăýúùõôóòíìêéèãâàá" +
            "ÀÁÂÃÈÉÊÌÍÒÓÔÕÙÚÝĂĐĨŨƠƯẠẢẤẦẨẪẬẮẰẴẶẸẺẼẾỀỂỄỆỈỊỌỎỐỒỔỖỘỚỜỞỠỢỤỦỨỪỬỮỰỲỴỶỸ"
        ).toSet()

        // --- Number to Vietnamese words (from num2vi.py) ---
        private val UNITS = mapOf(
            '0' to "không", '1' to "một", '2' to "hai", '3' to "ba",
            '4' to "bốn", '5' to "năm", '6' to "sáu", '7' to "bảy",
            '8' to "tám", '9' to "chín"
        )

        /** Convert number string to Vietnamese words. Works up to 12 digits. */
        fun n2w(number: String): String {
            val clean = number.replace(" ", "").replace("-", "").replace(".", "").replace(",", "")
            if (!clean.all { it.isDigit() } || clean.isEmpty()) return number
            return n2wLarge(clean)
        }

        /** Single-digit conversion. */
        private fun n2wUnits(n: String): String = UNITS[n[0]] ?: n

        /** Convert digit-by-digit (for phone numbers). */
        fun n2wSingle(number: String): String {
            var n = number
            if (n.startsWith("+84")) n = "0" + n.drop(3)
            return n.map { UNITS[it] ?: it.toString() }.joinToString(" ")
        }

        /** Hundreds: 0-999 */
        private fun n2wHundreds(numbers: String): String {
            if (numbers.length <= 1) return n2wUnits(numbers)
            val reversed = numbers.reversed()
            val parts = mutableListOf<String>()
            for ((e, ch) in reversed.withIndex()) {
                when (e) {
                    0 -> parts.add(UNITS[ch] ?: "")
                    1 -> parts.add((UNITS[ch] ?: "") + " mươi ")
                    2 -> parts.add((UNITS[ch] ?: "") + " trăm ")
                }
            }
            // Apply Vietnamese grammar rules
            val total = parts.toMutableList()
            // "không" at units place + "không mươi" → drop both
            if (total[0] == "không") {
                if (total.getOrNull(1)?.contains("không mươi") == true) total[1] = ""
                total[0] = ""
            }
            // "một" at units when not after "mươi" → "mốt"
            if (total[0] == "một" && total.getOrNull(1)?.contains("một mươi") != true &&
                total.getOrNull(1)?.contains("không mươi") != true && total.size > 1) {
                total[0] = "mốt"
            }
            // "năm" at units when not after "không mươi" → "lăm"
            if (total[0] == "năm" && total.getOrNull(1)?.contains("không mươi") != true && total.size > 1) {
                total[0] = "lăm"
            }
            // "không mươi" → "lẻ"
            for (i in total.indices) {
                if (total[i].contains("không mươi")) total[i] = "lẻ "
                if (total[i].contains("một mươi")) total[i] = "mười "
            }
            return total.reversed().joinToString("").trim()
        }

        /** Large numbers: 1000+ */
        private fun n2wLarge(numbers: String): String {
            if (numbers.length <= 3) return n2wHundreds(numbers)
            val reversed = numbers.reversed()
            val chunks = reversed.chunked(3)
            val parts = mutableListOf<String>()
            for ((e, chunk) in chunks.withIndex()) {
                val value = chunk.reversed()
                when (e) {
                    0 -> parts.add(n2wHundreds(value))
                    1 -> parts.add(n2wHundreds(value) + " nghìn ")
                    2 -> parts.add(n2wHundreds(value) + " triệu ")
                    3 -> parts.add(n2wHundreds(value) + " tỷ ")
                }
            }
            return parts.reversed().joinToString("").trim()
        }

        // --- Abbreviations (from abbreviation_vi.py) ---
        private val ABBREVIATIONS = mapOf(
            "v.v" to " vân vân. ", "v/v" to "về việc", "đ/c" to "địa chỉ",
            "k/g" to "kính gửi", "th/g" to "thân gửi", "ko" to "không",
            "bit" to "biết", "bik" to "biết"
        )

        // --- Acronyms (from acronym_vi.py) ---
        private val ACRONYMS_VI = mapOf(
            "CĐV" to "cổ động viên", "TV" to "ti vi", "HĐND" to "hội đồng nhân dân",
            "TAND" to "toàn án nhân dân", "BHXH" to "bảo hiểm xã hội",
            "BHTN" to "bảo hiểm thất nghiệp", "TP.HCM" to "thành phố hồ chí minh",
            "VN" to "việt nam", "BCHTW" to "ban chấp hành trung ương",
            "UBND" to "uỷ ban nhân dân", "TPHCM" to "thành phố hồ chí minh",
            "TP" to "thành phố", "HCM" to "hồ chí minh", "SG" to "Sài Gòn",
            "HN" to "hà nội", "BTC" to "ban tổ chức", "CLB" to "câu lạc bộ",
            "HTX" to "hợp tác xã", "NXB" to "nhà xuất bản", "ÔBA" to "ông bà",
            "QLTT" to "quản lý thị trường", "BST" to "bộ sưu tập",
            "TTTM" to "trung tâm thương mại", "TW" to "trung ương",
            "GD-ĐT" to "giáo dục và đào tạo", "BCH" to "bản chấp hành",
            "CHXHCNVN" to "cộng hòa xã hội chủ nghĩa việt nam",
            "CSGT" to "cảnh sát giao thông", "QDND" to "quân đội nhân dân",
            "LHQ" to "liên hợp quốc", "UBKT" to "uỷ ban kinh tế",
            "THCS" to "trung học cơ sở", "THPT" to "trung học phổ thông",
            "ĐH" to "đại học", "HLV" to "huấn luyện viên",
            "PGS" to "phó giáo sư", "GS" to "giáo sư", "ThS" to "thạc sĩ",
            "TS" to "tiến sĩ", "TNHH" to "trách nhiệm hữu hạn",
            "CSKH" to "chăm sóc khách hàng", "VĐV" to "vận động viên",
            "NSND" to "nghệ sĩ nhân dân", "NSƯT" to "nghệ sĩ ưu tú",
            "PCCC" to "phòng cháy chữa cháy", "PGĐ" to "phó giám đốc",
            "GĐ" to "giám đốc", "BS" to "bác sỹ", "GDP" to "gi đi pi",
            "FDI" to "ép đê i", "ODA" to "ô đê a",
            "VKSND" to "viện kiểm soát nhân dân", "HĐXX" to "hội đồng xét xử",
            "TTXVN" to "thông tấn xã việt nam",
            "1-0-2" to "một không hai", "SN" to "sinh năm",
            "môtô" to "mô tô", "ôtô" to "ô tô", "êkip" to "ê kíp",
            "youtube" to "du túp", "facebook" to "phây búc", "tiktok" to "tíc tót",
            "KQXS" to "kết quả sổ xố", "XSMB" to "xổ số miền bắc",
            "XSMN" to "xổ số miền nam", "XSMT" to "xổ số miền tây",
            "sars-cov" to "sát cô vi", "covid" to "cô vít",
            "coronavirus" to "cô rô na vai rớt"
        )

        private val HARDCODED_ACRONYMS = setOf(
            "BMW", "MVD", "WDSU", "GOP", "UK", "AI", "GPS", "BP", "FBI",
            "HD", "CES", "PC", "NBA", "OS", "IRS", "UV", "CEO", "TV", "CNN",
            "DNA", "TSA", "US", "GPU", "USA", "CIA", "WTO", "WHO", "IMF", "UN",
            "BBC", "IQ", "EQ"
        )

        // --- Currency (from currency_vi.py) ---
        private val CURRENCY_KEY = mapOf(
            "\$" to "đô la", "£" to "bảng", "€" to "ơ rô", "₩" to "uân",
            "₫" to "đồng", "usd" to "đô la", "euro" to "ơ rô", "eur" to "ơ rô",
            "vnd" to "đồng", "đ" to "đồng", "¥" to "yên", "ndt" to "nhân dân tệ",
            "%" to "phần trăm"
        )

        // --- Measurement units (from measurement_vi.py) ---
        private val MEASUREMENT_KEY = mapOf(
            "p" to "phút", "s" to "giây", "TB" to "tê ra bai", "GB" to "gi ga bai",
            "MB" to "mê ga bai", "KB" to "ki lô bai", "b" to "bai",
            "GHz" to "gi ga héc", "MHz" to "mê ga héc", "kHz" to "ki lô héc",
            "Hz" to "héc", "m2" to "mét vuông", "km2" to "ki lô mét vuông",
            "m3" to "mét khối", "nm" to "na nô mét", "mm" to "mi li mét",
            "cm" to "xen ti mét", "dm" to "đề xi mét", "km" to "ki lô mét",
            "kg" to "ki lô gam", "mg" to "mi li gam", "ml" to "mi li lít",
            "l" to "lít", "L" to "lít", "g" to "gam", "m" to "mét",
            "in" to "inch", "h" to "giờ", "ha" to "héc ta"
        )

        // --- Letter pronunciation (from letter_vi.py) ---
        private val LETTER_KEY_VI = mapOf(
            "a" to "ây", "b" to "bê", "c" to "xê", "d" to "dê", "đ" to "đê",
            "f" to "ép", "g" to "gờ", "h" to "hát", "i" to "ai", "j" to "chây",
            "k" to "kây", "l" to "lờ", "m" to "em mờ", "n" to "en nờ",
            "o" to "ô", "p" to "pê", "q" to "kiu", "r" to "rờ", "s" to "ét",
            "t" to "ti", "v" to "vi", "w" to "vê kép", "x" to "ít", "z" to "dét"
        )

        // --- Roman numerals ---
        private val ROMAN_VALS = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
    }

    fun cleanText(text: String): String {
        var result = text

        // 1. Collapse whitespace
        result = result.replace(Regex("(\\s)\\1+"), "$1")
        result = result.replace(Regex("\\t+"), " ")

        // 2. Normalize NFC
        result = java.text.Normalizer.normalize(result, java.text.Normalizer.Form.NFC)

        // 3. Expand teencode/slang (from vi_teencode.json)
        if (viTeencode.isNotEmpty()) {
            for ((slang, proper) in viTeencode) {
                result = result.replace(Regex("\\b${Regex.escape(slang)}\\b", RegexOption.IGNORE_CASE), proper)
            }
        }

        // 4. Expand abbreviations
        if (cleanAbbr) result = expandAbbreviations(result)

        // 5. Expand Roman numbers
        result = expandRomanNumbers(result)

        // 6. Expand acronyms
        if (cleanAcronym) result = expandAcronyms(result)

        // 6. Expand date/time
        result = expandDate(result)
        result = expandTime(result)

        // 7. Expand measurement units
        result = expandMeasurements(result)

        // 8. Expand currency
        result = expandCurrency(result)

        // 9. Expand numbers
        result = expandNumbers(result)

        // 10. Expand letters
        result = expandLetters(result)

        // 11. Normalize slash
        result = result.replace("/", " trên ")

        // 12. Remove left hyphens
        result = result.replace(Regex("([^\\s])(-)([^\\s])"), "$1 $3")

        // 13. Final whitespace collapse
        result = result.replace(Regex("(\\s)\\1+"), "$1").trim()

        // 14. "tháng bốn" → "tháng tư"
        result = result.replace("tháng bốn", "tháng tư")

        // 15. Lowercase
        result = result.lowercase(Locale.ROOT)

        return result
    }

    private fun expandAbbreviations(text: String): String {
        var result = text
        // Special symbols first
        result = result.replace(Regex("[ ]?%"), " phần trăm")
        result = result.replace("&", " và ")
        result = result.replace("@", " a còng ")
        result = result.replace("+", " cộng ")
        result = result.replace("//", " xuyệt ")
        // URLs
        result = result.replace(Regex("([a-zA-Z])\\.(com|gov|org|vn|com\\.vn|edu\\.vn)"), "$1 chấm $2")
        // Abbreviations
        for ((abbr, expanded) in ABBREVIATIONS) {
            result = result.replace(Regex("\\b${Regex.escape(abbr)}\\b", RegexOption.IGNORE_CASE), expanded)
        }
        return result
    }

    private fun expandAcronyms(text: String): String {
        var result = text
        // Dynamic acronyms from vi_acronyms.json (highest priority)
        if (viAcronyms.isNotEmpty()) {
            for ((acr, expanded) in viAcronyms) {
                result = result.replace(Regex("\\b${Regex.escape(acr)}\\b", RegexOption.IGNORE_CASE), expanded)
            }
        }
        // Vietnamese-specific acronyms from code
        for ((acr, expanded) in ACRONYMS_VI) {
            result = result.replace(Regex("\\b${Regex.escape(acr)}\\b", RegexOption.IGNORE_CASE), expanded)
        }
        // General uppercase acronyms: letter-by-letter if hardcoded
        for (acr in HARDCODED_ACRONYMS) {
            result = result.replace(Regex("\\b${acr}s?\\.?\\b")) { match ->
                match.value.map { it.toString() }.joinToString("  ")
            }
        }
        return result
    }

    private fun expandRomanNumbers(text: String): String {
        val romanRe = Regex("\\b(?!LLC)(?=[MDCLXVI]+\\b)M{0,4}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})\\b")
        return romanRe.replace(text) { match ->
            val num = romanToInt(match.value)
            if (num in 1..39) " ${n2w(num.toString())} " else match.value
        }
    }

    private fun romanToInt(s: String): Int {
        var result = 0
        for (i in s.indices) {
            val val1 = ROMAN_VALS[s[i]] ?: 0
            val val2 = if (i + 1 < s.length) ROMAN_VALS[s[i + 1]] ?: 0 else 0
            result += if (val1 < val2) -val1 else val1
        }
        return result
    }

    private fun expandDate(text: String): String {
        // Full date: DD/MM/YYYY
        var result = text.replace(Regex("(ngày)?([^0-9a-zA-Z_ỹỷỵ])?(\\d{1,2})(/|-|\\.)(\\d{1,2})(/|-|\\.)(\\d{4})")) { m ->
            val day = m.groupValues[3].trimStart('0').ifEmpty { "0" }
            val month = m.groupValues[5].trimStart('0').ifEmpty { "0" }
            val year = m.groupValues[7].trimStart('0').ifEmpty { "0" }
            if (day.toIntOrNull() in 1..31 && month.toIntOrNull() in 1..12)
                " ngày ${n2w(day)} tháng ${n2w(month)} năm ${n2w(year)} "
            else m.value
        }
        // Month/year: MM/YYYY
        result = result.replace(Regex("(tháng)?([^0-9a-zA-Z_ỹỷỵ])?(\\d{1,2})(/|-|\\.)(\\d{4})")) { m ->
            val month = m.groupValues[3].trimStart('0').ifEmpty { "0" }
            val year = m.groupValues[5].trimStart('0').ifEmpty { "0" }
            if (month.toIntOrNull() in 1..12)
                " tháng ${n2w(month)} năm ${n2w(year)} "
            else m.value
        }
        return result
    }

    private fun expandTime(text: String): String {
        // HH:MM or HHgMMp
        return text.replace(Regex("(\\d{1,2})(g|:|h)(\\d{1,2})(p|m)?")) { m ->
            val hour = m.groupValues[1].trimStart('0').ifEmpty { "0" }
            val minute = m.groupValues[3].trimStart('0').ifEmpty { "0" }
            if (hour.toIntOrNull() in 0..23 && minute.toIntOrNull() in 0..59)
                " ${n2w(hour)} giờ ${n2w(minute)} phút "
            else m.value
        }
    }

    private fun expandMeasurements(text: String): String {
        var result = text
        for ((unit, expanded) in MEASUREMENT_KEY.sortedByDescending { it.key.length }) {
            val re = Regex("(\\d)\\s*${Regex.escape(unit)}\\b")
            result = re.replace(result) { m ->
                "${m.groupValues[1]} $expanded "
            }
        }
        return result
    }

    private fun expandCurrency(text: String): String {
        var result = text
        for ((symbol, expanded) in CURRENCY_KEY) {
            result = result.replace(symbol, " $expanded ")
        }
        return result
    }

    private fun expandNumbers(text: String): String {
        // Phone numbers
        var result = text.replace(Regex("((?:\\+84|84|0|0084)(?:3|5|7|8|9)\\d{8})")) { m ->
            " ${n2wSingle(m.value)} "
        }
        // Regular numbers
        result = result.replace(Regex("(-)?(\\d+[.,]?\\d*)")) { m ->
            val negative = m.groupValues[1]
            val num = m.groupValues[2].replace(",", "").replace(".", "")
            if (num.all { it.isDigit() } && num.isNotEmpty()) {
                val prefix = if (negative == "-") "âm " else ""
                "$prefix${n2w(num)} "
            } else m.value
        }
        // Special ordinals: thứ 1 → thứ nhất, thứ 4 → thứ tư
        result = result.replace(Regex("(thứ|hạng)\\s+1"), "$1 nhất")
        result = result.replace(Regex("(thứ|hạng)\\s+4"), "$1 tư")
        return result
    }

    private fun expandLetters(text: String): String {
        return text.replace(Regex("(chữ|chữ cái|kí tự|ký tự)?\\s+[\"']?([a-zàáạảãâầấậẫẩăằắặẵẳẻẽẹèéêềếễểỏõọòóôồốỗổưứừựữửìíịỉỉỳýỵỷỹđ])\\b", RegexOption.IGNORE_CASE)) { m ->
            val leading = m.groupValues[1]
            val char = m.groupValues[2].lowercase(Locale.ROOT)
            val expanded = LETTER_KEY_VI[char] ?: char
            "${leading ?: ""} $expanded"
        }
    }
}