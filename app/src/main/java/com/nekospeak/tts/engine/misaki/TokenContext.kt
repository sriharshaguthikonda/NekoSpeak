package com.nekospeak.tts.engine.misaki

/**
 * Faithful port of upstream Misaki's TokenContext.
 * Reference: https://github.com/hexgrad/misaki/blob/main/misaki/en.py
 *
 * Carries forward-looking context for G2P decisions:
 * - futureVowel: Is the next significant token a vowel? Affects "the" → ði vs ðə
 * - futureTo: Is the next token "to"? Affects "used" pronunciation
 */
data class TokenContext(
    val futureVowel: Boolean? = null,
    val futureTo: Boolean = false
)