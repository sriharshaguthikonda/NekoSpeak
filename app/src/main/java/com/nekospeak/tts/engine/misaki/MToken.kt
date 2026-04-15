package com.nekospeak.tts.engine.misaki

/**
 * Faithful port of upstream Misaki's MToken.
 * Reference: https://github.com/hexgrad/misaki/blob/main/misaki/token.py
 *
 * Represents a token with text, POS tag, whitespace, phonemes, and
 * underscore attributes (stress, currency, num_flags, etc.)
 */
data class MToken(
    val text: String,
    val tag: String,
    var whitespace: String,
    var phonemes: String? = null,
    val startTs: Float? = null,
    val endTs: Float? = null,
    val attributes: Underscore = Underscore()
) {
    /**
     * Equivalent of Python's `tk._` accessor via dataclass replace.
     * Named `attributes` in Kotlin but aliased as `_.` conceptually.
     */
    data class Underscore(
        var isHead: Boolean = true,
        var numFlags: String = "",
        var prespace: Boolean = false,
        var stress: Double? = null,
        var currency: String? = null,
        var alias: String? = null,
        var rating: Int? = null,
        // JA-specific
        var pron: String? = null,
        var acc: Int? = null,
        var moraSize: Int? = null,
        var chainFlag: Boolean = false,
        var moras: List<String>? = null,
        var accents: List<Int>? = null,
        var pitch: String? = null
    )

    /**
     * Create a copy of this token with specified fields overridden.
     * Equivalent to Python's `dataclasses.replace(tk, ...)`.
     */
    fun copy(
        text: String = this.text,
        tag: String = this.tag,
        whitespace: String = this.whitespace,
        phonemes: String? = this.phonemes,
        attributes: Underscore = this.attributes
    ): MToken {
        return MToken(
            text = text,
            tag = tag,
            whitespace = whitespace,
            phonemes = phonemes,
            startTs = this.startTs,
            endTs = this.endTs,
            attributes = attributes
        )
    }
}

/**
 * Equivalent of Python's `replace(tk, _=tk._.copy(...))` for MToken.
 * Creates a new MToken with the underscore attributes replaced.
 */
fun replaceMToken(
    token: MToken,
    text: String = token.text,
    tag: String = token.tag,
    whitespace: String = token.whitespace,
    phonemes: String? = token.phonemes,
    isHead: Boolean? = null,
    numFlags: String? = null,
    prespace: Boolean? = null,
    stress: Double? = null,
    currency: String? = null,
    alias: String? = null,
    rating: Int? = null
): MToken {
    val newAttrs = token.attributes.copy(
        isHead = isHead ?: token.attributes.isHead,
        numFlags = numFlags ?: token.attributes.numFlags,
        prespace = prespace ?: token.attributes.prespace,
        stress = stress ?: token.attributes.stress,
        currency = currency ?: token.attributes.currency,
        alias = alias ?: token.attributes.alias,
        rating = rating ?: token.attributes.rating
    )
    return MToken(text, tag, whitespace, phonemes, token.startTs, token.endTs, newAttrs)
}