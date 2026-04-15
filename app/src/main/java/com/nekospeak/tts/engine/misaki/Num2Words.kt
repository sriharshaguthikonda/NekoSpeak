package com.nekospeak.tts.engine.misaki

/**
 * Faithful port of upstream Misaki's Num2Words (partial).
 * Reference: https://github.com/hexgrad/misaki/blob/main/misaki/en.py
 *
 * Converts numbers to English words for G2P processing.
 * The Python version uses the `num2words` library; this is a self-contained
 * Kotlin implementation covering cardinal, ordinal, and year forms.
 */
object Num2Words {

    private val ONES = arrayOf(
        "", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen"
    )

    private val TENS = arrayOf(
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
    )

    private val THOUSANDS = arrayOf(
        "", "thousand", "million", "billion", "trillion", "quadrillion", "quintillion"
    )

    private val ORDINALS = mapOf(
        "one" to "first", "two" to "second", "three" to "third",
        "four" to "fourth", "five" to "fifth", "six" to "sixth",
        "seven" to "seventh", "eight" to "eighth", "nine" to "ninth",
        "ten" to "tenth", "eleven" to "eleventh", "twelve" to "twelfth",
        "thirteen" to "thirteenth", "fourteen" to "fourteenth",
        "fifteen" to "fifteenth", "sixteen" to "sixteenth",
        "seventeen" to "seventeenth", "eighteen" to "eighteenth",
        "nineteen" to "nineteenth", "twenty" to "twentieth",
        "thirty" to "thirtieth", "forty" to "fortieth",
        "fifty" to "fiftieth", "sixty" to "sixtieth",
        "seventy" to "seventieth", "eighty" to "eightieth",
        "ninety" to "ninetieth", "hundred" to "hundredth",
        "thousand" to "thousandth", "million" to "millionth",
        "billion" to "billionth"
    )

    fun convert(number: Double): String = convert(number.toLong())

    fun convert(number: Int): String = convert(number.toLong())

    fun convert(number: Long): String {
        if (number == 0L) return "zero"
        var i = 0
        var words = ""
        var num = number
        if (number < 0) {
            num = -number
            words += "minus "
        }
        while (num > 0) {
            if (num % 1000 != 0L) {
                words = helper(num % 1000) + THOUSANDS[i] + " " + words
            }
            num /= 1000
            i++
        }
        return words.trim()
    }

    /**
     * Convert a number to its ordinal form (1st, 2nd, 3rd, ...)
     */
    fun toOrdinal(number: Long): String {
        val cardinal = convert(number)
        return toOrdinal(cardinal)
    }

    fun toOrdinal(cardinal: String): String {
        // Try direct mapping first
        ORDINALS[cardinal]?.let { return it }
        // Handle compound forms: "twenty-three" → "twenty-third"
        if (cardinal.contains("-")) {
            val parts = cardinal.split("-")
            val last = parts.last()
            val ordLast = ORDINALS[last] ?: (last + "th")
            return parts.dropLast(1).joinToString("-") + "-" + ordLast
        }
        // Handle forms ending in "y": "twenty" → "twentieth"
        if (cardinal.endsWith("y")) {
            return cardinal.dropLast(1) + "ieth"
        }
        // Default: append "th"
        return cardinal + "th"
    }

    /**
     * Convert a 4-digit number to year form: 2026 → "twenty twenty-six"
     */
    fun toYear(number: Long): String {
        if (number < 1000 || number > 9999) return convert(number)
        val thousands = (number / 100).toInt()
        val hundreds = (number % 100).toInt()
        return if (hundreds == 0) {
            convert(thousands.toLong()) + " hundred"
        } else {
            convert(thousands.toLong()) + " " + convert(hundreds.toLong())
        }
    }

    /**
     * Convert a floating point number to words: 3.14 → "three point one four"
     */
    fun toFloat(number: Double): String {
        val parts = number.toString().split(".")
        if (parts.size != 2) return convert(number.toLong())
        val intPart = convert(parts[0].toLong())
        val decDigits = parts[1].map { convert(it.digitToInt().toLong()) }.joinToString(" ")
        return "$intPart point $decDigits"
    }

    private fun helper(num: Long): String {
        return when {
            num == 0L -> ""
            num < 20 -> ONES[num.toInt()] + " "
            num < 100 -> TENS[num.toInt() / 10] + (if (num % 10 != 0L) "-" else " ") + helper(num % 10)
            else -> ONES[num.toInt() / 100] + " hundred " + helper(num % 100)
        }
    }
}