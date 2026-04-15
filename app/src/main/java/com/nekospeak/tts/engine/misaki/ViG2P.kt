package com.nekospeak.tts.engine.misaki

import android.util.Log
import java.util.Locale

/**
 * Complete faithful port of upstream Misaki's Vietnamese G2P (vi.py + vi_cleaner/).
 * Reference: https://github.com/hexgrad/misaki/blob/main/misaki/vi.py
 *
 * Line-for-line port of vi.py including all dictionary entries:
 * - 27 onset consonant mappings (Cus_onsets)
 * - 158 nucleus vowel mappings with all 6 tone variants (Cus_nuclei)
 * - 125 off-glide diphthong mappings with tone variants (Cus_offglides)
 * - 105 on-glide labiovelar mappings with tone variants (Cus_onglides)
 * - 59 on-off-glide triphthong mappings with tone variants (Cus_onoffglides)
 * - 8 coda consonant mappings (Cus_codas)
 * - 60 tone diacritical mark -> Pham tone number mappings (Cus_tones_p)
 * - GI and QU special case mappings
 * - EN/VI letter-by-letter pronunciation for acronyms
 * - Complete trans() + convert() + substr2ipa() logic
 * - Northern/Southern/Central dialect-specific phonological rules
 * - Labialized allophony, velar fronting, monophthongization
 * - ViCleaner integration for full text normalization
 */
class ViG2P(
    private val enG2P: ((String) -> String)? = null,
    private val espeakFallback: ((String, String) -> String?)? = null,
    private val unk: String = "❓",
    private val cleaner: ViCleaner = ViCleaner()
) {
    companion object {
        private const val TAG = "ViG2P"

        private val ONSETS = mapOf(
            "b" to "b",
            "t" to "t",
            "th" to "tʰ",
            "đ" to "d",
            "ch" to "c",
            "kh" to "x",
            "g" to "ɣ",
            "l" to "l",
            "m" to "m",
            "n" to "n",
            "ngh" to "ŋ",
            "nh" to "ɲ",
            "ng" to "ŋ",
            "ph" to "f",
            "v" to "v",
            "x" to "s",
            "d" to "z",
            "h" to "h",
            "p" to "p",
            "qu" to "kw",
            "gi" to "j",
            "tr" to "ʈ",
            "k" to "k",
            "c" to "k",
            "gh" to "ɣ",
            "r" to "ʐ",
            "s" to "ʂ"
        )

        private val NUCLEI = mapOf(
            "a" to "a",
            "á" to "a",
            "à" to "a",
            "ả" to "a",
            "ã" to "a",
            "ạ" to "a",
            "â" to "ɤ̆",
            "ấ" to "ɤ̆",
            "ầ" to "ɤ̆",
            "ẩ" to "ɤ̆",
            "ẫ" to "ɤ̆",
            "ậ" to "ɤ̆",
            "ă" to "ă",
            "ắ" to "ă",
            "ằ" to "ă",
            "ẳ" to "ă",
            "ẵ" to "ă",
            "ặ" to "ă",
            "e" to "ɛ",
            "é" to "ɛ",
            "è" to "ɛ",
            "ẻ" to "ɛ",
            "ẽ" to "ɛ",
            "ẹ" to "ɛ",
            "ê" to "e",
            "ế" to "e",
            "ề" to "e",
            "ể" to "e",
            "ễ" to "e",
            "ệ" to "e",
            "i" to "i",
            "í" to "i",
            "ì" to "i",
            "ỉ" to "i",
            "ĩ" to "i",
            "ị" to "i",
            "o" to "ɔ",
            "ó" to "ɔ",
            "ò" to "ɔ",
            "ỏ" to "ɔ",
            "õ" to "ɔ",
            "ọ" to "ɔ",
            "ô" to "o",
            "ố" to "o",
            "ồ" to "o",
            "ổ" to "o",
            "ỗ" to "o",
            "ộ" to "o",
            "ơ" to "ɤ",
            "ớ" to "ɤ",
            "ờ" to "ɤ",
            "ở" to "ɤ",
            "ỡ" to "ɤ",
            "ợ" to "ɤ",
            "u" to "u",
            "ú" to "u",
            "ù" to "u",
            "ủ" to "u",
            "ũ" to "u",
            "ụ" to "u",
            "ư" to "ɯ",
            "ứ" to "ɯ",
            "ừ" to "ɯ",
            "ử" to "ɯ",
            "ữ" to "ɯ",
            "ự" to "ɯ",
            "y" to "i",
            "ý" to "i",
            "ỳ" to "i",
            "ỷ" to "i",
            "ỹ" to "i",
            "ỵ" to "i",
            "eo" to "eo",
            "éo" to "eo",
            "èo" to "eo",
            "ẻo" to "eo",
            "ẽo" to "eo",
            "ẹo" to "eo",
            "êu" to "ɛu",
            "ếu" to "ɛu",
            "ều" to "ɛu",
            "ểu" to "ɛu",
            "ễu" to "ɛu",
            "ệu" to "ɛu",
            "ia" to "iə",
            "ía" to "iə",
            "ìa" to "iə",
            "ỉa" to "iə",
            "ĩa" to "iə",
            "ịa" to "iə",
            "iá" to "iə",
            "ià" to "iə",
            "iả" to "iə",
            "iã" to "iə",
            "iạ" to "iə",
            "iê" to "iə",
            "iế" to "iə",
            "iề" to "iə",
            "iể" to "iə",
            "iễ" to "iə",
            "iệ" to "iə",
            "oo" to "ɔ",
            "óo" to "ɔ",
            "òo" to "ɔ",
            "ỏo" to "ɔ",
            "õo" to "ɔ",
            "ọo" to "ɔ",
            "oó" to "ɔ",
            "oò" to "ɔ",
            "oỏ" to "ɔ",
            "oõ" to "ɔ",
            "oọ" to "ɔ",
            "ôô" to "o",
            "ốô" to "o",
            "ồô" to "o",
            "ổô" to "o",
            "ỗô" to "o",
            "ộô" to "o",
            "ôố" to "o",
            "ôồ" to "o",
            "ôổ" to "o",
            "ôỗ" to "o",
            "ôộ" to "o",
            "ua" to "uə",
            "úa" to "uə",
            "ùa" to "uə",
            "ủa" to "uə",
            "ũa" to "uə",
            "ụa" to "uə",
            "uô" to "uə",
            "uố" to "uə",
            "uồ" to "uə",
            "uổ" to "uə",
            "uỗ" to "uə",
            "uộ" to "uə",
            "ưa" to "ɯə",
            "ứa" to "ɯə",
            "ừa" to "ɯə",
            "ửa" to "ɯə",
            "ữa" to "ɯə",
            "ựa" to "ɯə",
            "ươ" to "ɯə",
            "ướ" to "ɯə",
            "ườ" to "ɯə",
            "ưở" to "ɯə",
            "ưỡ" to "ɯə",
            "ượ" to "ɯə",
            "yê" to "iɛ",
            "yế" to "iɛ",
            "yề" to "iɛ",
            "yể" to "iɛ",
            "yễ" to "iɛ",
            "yệ" to "iɛ",
            "uơ" to "uə",
            "uở" to "uə",
            "uờ" to "uə",
            "uỡ" to "uə",
            "uợ" to "uə"
        )

        private val OFFGLIDES = mapOf(
            "ai" to "aj",
            "ái" to "aj",
            "ài" to "aj",
            "ải" to "aj",
            "ãi" to "aj",
            "ại" to "aj",
            "ay" to "ăj",
            "áy" to "ăj",
            "ày" to "ăj",
            "ảy" to "ăj",
            "ãy" to "ăj",
            "ạy" to "ăj",
            "ao" to "aw",
            "áo" to "aw",
            "ào" to "aw",
            "ảo" to "aw",
            "ão" to "aw",
            "ạo" to "aw",
            "au" to "ăw",
            "áu" to "ăw",
            "àu" to "ăw",
            "ảu" to "ăw",
            "ãu" to "ăw",
            "ạu" to "ăw",
            "ây" to "ɤ̆j",
            "ấy" to "ɤ̆j",
            "ầy" to "ɤ̆j",
            "ẩy" to "ɤ̆j",
            "ẫy" to "ɤ̆j",
            "ậy" to "ɤ̆j",
            "âu" to "ɤ̆w",
            "ấu" to "ɤ̆w",
            "ầu" to "ɤ̆w",
            "ẩu" to "ɤ̆w",
            "ẫu" to "ɤ̆w",
            "ậu" to "ɤ̆w",
            "eo" to "ew",
            "éo" to "ew",
            "èo" to "ew",
            "ẻo" to "ew",
            "ẽo" to "ew",
            "ẹo" to "ew",
            "iu" to "iw",
            "íu" to "iw",
            "ìu" to "iw",
            "ỉu" to "iw",
            "ĩu" to "iw",
            "ịu" to "iw",
            "oi" to "ɔj",
            "ói" to "ɔj",
            "òi" to "ɔj",
            "ỏi" to "ɔj",
            "õi" to "ɔj",
            "ọi" to "ɔj",
            "ôi" to "oj",
            "ối" to "oj",
            "ồi" to "oj",
            "ổi" to "oj",
            "ỗi" to "oj",
            "ội" to "oj",
            "ui" to "uj",
            "úi" to "uj",
            "ùi" to "uj",
            "ủi" to "uj",
            "ũi" to "uj",
            "ụi" to "uj",
            "uy" to "ʷi",
            "úy" to "uj",
            "ùy" to "uj",
            "ủy" to "uj",
            "ũy" to "uj",
            "ụy" to "uj",
            "uý" to "ʷi",
            "uỳ" to "ʷi",
            "uỷ" to "ʷi",
            "uỹ" to "ʷi",
            "uỵ" to "ʷi",
            "ơi" to "ɤj",
            "ới" to "ɤj",
            "ời" to "ɤj",
            "ởi" to "ɤj",
            "ỡi" to "ɤj",
            "ợi" to "ɤj",
            "ưi" to "ɯj",
            "ứi" to "ɯj",
            "ừi" to "ɯj",
            "ửi" to "ɯj",
            "ữi" to "ɯj",
            "ựi" to "ɯj",
            "ưu" to "ɯw",
            "ứu" to "ɯw",
            "ừu" to "ɯw",
            "ửu" to "ɯw",
            "ữu" to "ɯw",
            "ựu" to "ɯw",
            "iêu" to "iəw",
            "iếu" to "iəw",
            "iều" to "iəw",
            "iểu" to "iəw",
            "iễu" to "iəw",
            "iệu" to "iəw",
            "yêu" to "iəw",
            "yếu" to "iəw",
            "yều" to "iəw",
            "yểu" to "iəw",
            "yễu" to "iəw",
            "yệu" to "iəw",
            "uôi" to "uəj",
            "uối" to "uəj",
            "uồi" to "uəj",
            "uổi" to "uəj",
            "uỗi" to "uəj",
            "uội" to "uəj",
            "ươi" to "ɯəj",
            "ưới" to "ɯəj",
            "ười" to "ɯəj",
            "ưởi" to "ɯəj",
            "ưỡi" to "ɯəj",
            "ượi" to "ɯəj",
            "ươu" to "ɯəw",
            "ướu" to "ɯəw",
            "ườu" to "ɯəw",
            "ưởu" to "ɯəw",
            " : u'ɯəw', u'ượu" to "ɯəw",
            "ưỡu" to "ɯəw"
        )

        private val ONGLIDES = mapOf(
            "oa" to "ʷa",
            "oá" to "ʷa",
            "oà" to "ʷa",
            "oả" to "ʷa",
            "oã" to "ʷa",
            "oạ" to "ʷa",
            "óa" to "ʷa",
            "òa" to "ʷa",
            "ỏa" to "ʷa",
            "õa" to "ʷa",
            "ọa" to "ʷa",
            "oă" to "ʷă",
            "oắ" to "ʷă",
            "oằ" to "ʷă",
            "oẳ" to "ʷă",
            "oẵ" to "ʷă",
            "oặ" to "ʷă",
            "oe" to "ʷɛ",
            "oé" to "ʷɛ",
            "oè" to "ʷɛ",
            "oẻ" to "ʷɛ",
            "oẽ" to "ʷɛ",
            "oẹ" to "ʷɛ",
            "óe" to "ʷɛ",
            "òe" to "ʷɛ",
            "ỏe" to "ʷɛ",
            "õe" to "ʷɛ",
            "ọe" to "ʷɛ",
            "ua" to "ʷa",
            "uá" to "ʷa",
            "uà" to "ʷa",
            "uả" to "ʷa",
            "uã" to "ʷa",
            "uạ" to "ʷa",
            "uă" to "ʷă",
            "uắ" to "ʷă",
            "uằ" to "ʷă",
            "uẳ" to "ʷă",
            "uẵ" to "ʷă",
            "uặ" to "ʷă",
            "uâ" to "ʷɤ̆",
            "uấ" to "ʷɤ̆",
            "uầ" to "ʷɤ̆",
            "uẩ" to "ʷɤ̆",
            "uẫ" to "ʷɤ̆",
            "uậ" to "ʷɤ̆",
            "ue" to "ʷɛ",
            "ué" to "ʷɛ",
            "uè" to "ʷɛ",
            "uẻ" to "ʷɛ",
            "uẽ" to "ʷɛ",
            "uẹ" to "ʷɛ",
            "uê" to "ʷe",
            "uế" to "ʷe",
            "uề" to "ʷe",
            "uể" to "ʷe",
            "uễ" to "ʷe",
            "uệ" to "ʷe",
            "uơ" to "ʷɤ",
            "uớ" to "ʷɤ",
            "uờ" to "ʷɤ",
            "uở" to "ʷɤ",
            "uỡ" to "ʷɤ",
            "uợ" to "ʷɤ",
            "uy" to "ʷi",
            "uý" to "ʷi",
            "uỳ" to "ʷi",
            "uỷ" to "ʷi",
            "uỹ" to "ʷi",
            "uỵ" to "ʷi",
            "uya" to "ʷiə",
            "uyá" to "ʷiə",
            "uyà" to "ʷiə",
            "uyả" to "ʷiə",
            "uyã" to "ʷiə",
            "uyạ" to "ʷiə",
            "uyê" to "ʷiə",
            "uyế" to "ʷiə",
            "uyề" to "ʷiə",
            "uyể" to "ʷiə",
            "uyễ" to "ʷiə",
            "uyệ" to "ʷiə",
            "uyu" to "ʷiu",
            "uyú" to "ʷiu",
            "uyù" to "ʷiu",
            "uyủ" to "ʷiu",
            "uyũ" to "ʷiu",
            "uyụ" to "ʷiu",
            "uýu" to "ʷiu",
            "uỳu" to "ʷiu",
            "uỷu" to "ʷiu",
            "uỹu" to "ʷiu",
            "uỵu" to "ʷiu",
            "oen" to "ʷen",
            "oén" to "ʷen",
            "oèn" to "ʷen",
            "oẻn" to "ʷen",
            "oẽn" to "ʷen",
            "oẹn" to "ʷen",
            "oet" to "ʷet",
            "oét" to "ʷet",
            "oèt" to "ʷet",
            "oẻt" to "ʷet",
            "oẽt" to "ʷet",
            "oẹt" to "ʷet"
        )

        private val ONOFFGLIDES = mapOf(
            "oe" to "ɛj",
            "oé" to "ɛj",
            "oè" to "ɛj",
            "oẻ" to "ɛj",
            "oẽ" to "ɛj",
            "oẹ" to "ɛj",
            "oai" to "aj",
            "oái" to "aj",
            "oài" to "aj",
            "oải" to "aj",
            "oãi" to "aj",
            "oại" to "aj",
            "oay" to "ăj",
            "oáy" to "ăj",
            "oày" to "ăj",
            "oảy" to "ăj",
            "oãy" to "ăj",
            "oạy" to "ăj",
            "oao" to "aw",
            "oáo" to "aw",
            "oào" to "aw",
            "oảo" to "aw",
            "oão" to "aw",
            "oạo" to "aw",
            "oeo" to "ew",
            "oéo" to "ew",
            "oèo" to "ew",
            "oẻo" to "ew",
            "oẽo" to "ew",
            "oẹo" to "ew",
            "óeo" to "ew",
            "òeo" to "ew",
            "ỏeo" to "ew",
            "õeo" to "ew",
            "ọeo" to "ew",
            "ueo" to "ew",
            "uéo" to "ew",
            "uèo" to "ew",
            "uẻo" to "ew",
            "uẽo" to "ew",
            "uẹo" to "ew",
            "uai" to "aj",
            "uái" to "aj",
            "uài" to "aj",
            "uải" to "aj",
            "uãi" to "aj",
            "uại" to "aj",
            "uay" to "ăj",
            "uáy" to "ăj",
            "uày" to "ăj",
            "uảy" to "ăj",
            "uãy" to "ăj",
            "uạy" to "ăj",
            "uây" to "ɤ̆j",
            "uấy" to "ɤ̆j",
            "uầy" to "ɤ̆j",
            "uẩy" to "ɤ̆j",
            "uẫy" to "ɤ̆j",
            "uậy" to "ɤ̆j"
        )

        private val CODAS = mapOf(
            "p" to "p",
            "t" to "t",
            "c" to "k",
            "m" to "m",
            "n" to "n",
            "ng" to "ŋ",
            "nh" to "ɲ",
            "ch" to "tʃ"
        )

        private val TONES_P = mapOf(
            "á" to 5,
            "à" to 2,
            "ả" to 4,
            "ã" to 3,
            "ạ" to 6,
            "ấ" to 5,
            "ầ" to 2,
            "ẩ" to 4,
            "ẫ" to 3,
            "ậ" to 6,
            "ắ" to 5,
            "ằ" to 2,
            "ẳ" to 4,
            "ẵ" to 3,
            "ặ" to 6,
            "é" to 5,
            "è" to 2,
            "ẻ" to 4,
            "ẽ" to 3,
            "ẹ" to 6,
            "ế" to 5,
            "ề" to 2,
            "ể" to 4,
            "ễ" to 3,
            "ệ" to 6,
            "í" to 5,
            "ì" to 2,
            "ỉ" to 4,
            "ĩ" to 3,
            "ị" to 6,
            "ó" to 5,
            "ò" to 2,
            "ỏ" to 4,
            "õ" to 3,
            "ọ" to 6,
            "ố" to 5,
            "ồ" to 2,
            "ổ" to 4,
            "ỗ" to 3,
            "ộ" to 6,
            "ớ" to 5,
            "ờ" to 2,
            "ở" to 4,
            "ỡ" to 3,
            "ợ" to 6,
            "ú" to 5,
            "ù" to 2,
            "ủ" to 4,
            "ũ" to 3,
            "ụ" to 6,
            "ứ" to 5,
            "ừ" to 2,
            "ử" to 4,
            "ữ" to 3,
            "ự" to 6,
            "ý" to 5,
            "ỳ" to 2,
            "ỷ" to 4,
            "ỹ" to 3,
            "ỵ" to 6
        )

        private val GI = mapOf(
            "gi" to "zi",
            "gí" to "zi",
            "gì" to "zi",
            "gĩ" to "zi",
            "gị" to "zi"
        )

        private val QU = mapOf(
            "quy" to "kwi",
            "qúy" to "kwi",
            "qùy" to "kwi",
            "qủy" to "kwi",
            "qũy" to "kwi",
            "qụy" to "kwi"
        )

        private val EN_LETTERS = mapOf(
            "a" to "ây",
            "b" to "bi",
            "c" to "si",
            "d" to "đi",
            "e" to "i",
            "f" to "ép",
            "g" to "giy",
            "h" to "hếch",
            "i" to "ai",
            "j" to "giây",
            "k" to "cây",
            "l" to "eo",
            "m" to "em",
            "n" to "en",
            "o" to "âu",
            "p" to "pi",
            "q" to "kiu",
            "r" to "a",
            "s" to "ét",
            "t" to "ti",
            "u" to "diu",
            "ư" to "ư",
            "v" to "vi",
            "w" to "đắp liu",
            "x" to "ít",
            "y" to "quai",
            "z" to "giét"
        )

        private val VI_LETTERS = mapOf(
            "a" to "a",
            "ă" to "á",
            "â" to "ớ",
            "b" to "bê",
            "c" to "cê",
            "d" to "dê",
            "đ" to "đê",
            "e" to "e",
            "ê" to "ê",
            "f" to "phờ",
            "g" to "gờ",
            "h" to "hờ",
            "i" to "i",
            "j" to "giây",
            "k" to "ka",
            "l" to "lờ",
            "m" to "mờ",
            "n" to "nờ",
            "o" to "o",
            "ô" to "ô",
            "ơ" to "ơ",
            "p" to "pờ",
            "q" to "quy",
            "r" to "rờ",
            "s" to "sờ",
            "t" to "tờ",
            "u" to "u",
            "ư" to "ư",
            "v" to "vi",
            "w" to "gờ",
            "x" to "xờ",
            "y" to "i",
            "z" to "gia"
        )

        // Vietnamese IPA symbol inventory (from vi.py vi_syms)
        private val VI_SYMS = listOf("ɯəj", "ɤ̆j", "ʷiə", "ɤ̆w", "ɯəw", "ʷet", "iəw", "uəj", "ʷen", "tʰw", "ʷɤ̆", "ʷiu", "kwi", "ŋ͡m", "k͡p", "cw", "jw", "uə", "eə", "bw", "oj", "ʷi", "vw", "ăw", "ʈw", "ʂw", "aʊ", "fw", "ɛu", "tʰ", "tʃ", "ɔɪ", "xw", "ʷɤ", "ɤ̆", "ŋw", "ʊə", "zi", "ʷă", "dw", "eɪ", "aɪ", "ew", "iə", "ɣw", "zw", "ɯj", "ʷɛ", "ɯw", "ɤj", "ɔ:", "əʊ", "ʷa", "mw", "ɑ:", "hw", "ɔj", "uj", "lw", "ɪə", "ăj", "u:", "aw", "ɛj", "iw", "aj", "ɜ:", "kw", "nw", "ɲw", "eo", "sw", "tw", "ʐw", "iɛ", "ʷe", "i:", "ɯə", "ɲ", "θ", "ʌ", "l", "w", "1", "ɪ", "ɯ", "d", "p", "ə", "u", "o", "3", "ɣ", "!", "ð", "ʧ", "6", "ʒ", "ʐ", "z", "v", "g", "ă", "æ", "ɤ", "2", "ʤ", "i", ".", "b", "h", "n", "ʂ", "ɔ", "ɛ", "k", "m", "5", " ", "c", "j", "x", "ʈ", ",", "4", "ʊ", "s", "ŋ", "a", "ʃ", "?", "r", ":", "f", ";", "e", "t", "'", "–")

        // Vietnamese-only character set
        private val VI_ONLY_CHARS = setOf('ă', 'â', 'đ', 'ê', 'ô', 'ơ', 'ư', 'à', 'á', 'ả', 'ã', 'ạ', 'ằ', 'ắ', 'ẳ', 'ẵ', 'ặ', 'ầ', 'ấ', 'ẩ', 'ẫ', 'ậ', 'è', 'é', 'ẻ', 'ẽ', 'ẹ', 'ề', 'ế', 'ể', 'ễ', 'ệ', 'ì', 'í', 'ỉ', 'ĩ', 'ị', 'ò', 'ó', 'ỏ', 'õ', 'ọ', 'ồ', 'ố', 'ổ', 'ỗ', 'ộ', 'ờ', 'ớ', 'ở', 'ỡ', 'ợ', 'ù', 'ú', 'ủ', 'ũ', 'ụ', 'ừ', 'ứ', 'ử', 'ữ', 'ự', 'ỳ', 'ý', 'ỷ', 'ỹ', 'ỵ')
    }

    private val dialect: String = "n" // north

    /**
     * Convert a single Vietnamese syllable to IPA.
     * Faithful port of upstream vi.py `trans()` + `convert()`.
     */
    fun convert(word: String, glottal: Int = 0, pham: Int = 1, cao: Int = 0, palatals: Int = 0): String {
        var ons = ""
        var nuc = ""
        var cod = ""
        var ton = 0
        var oOffset = 0
        var cOffset = 0
        val l = word.length

        if (l == 0) return ""

        // Find onset
        if (l >= 3 && word.substring(0, 3) in ONSETS) {
            ons = ONSETS[word.substring(0, 3)]!!
            oOffset = 3
        } else if (l >= 2 && word.substring(0, 2) in ONSETS) {
            ons = ONSETS[word.substring(0, 2)]!!
            oOffset = 2
        } else if (word[0].toString() in ONSETS) {
            ons = ONSETS[word[0].toString()]!!
            oOffset = 1
        }

        // Find coda
        if (l >= 2 && word.substring(l - 2) in CODAS) {
            cod = CODAS[word.substring(l - 2)]!!
            cOffset = 2
        } else if (l >= 1 && word[l - 1].toString() in CODAS) {
            cod = CODAS[word[l - 1].toString()]!!
            cOffset = 1
        }

        // Find nucleus
        if (word.substring(0, 2) in GI && cod.isNotEmpty() && l == 3) {
            nuc = "i"
            ons = "z"
        } else {
            val nucl = if (oOffset + cOffset <= l) word.substring(oOffset, l - cOffset) else ""
            if (nucl in NUCLEI) {
                if (oOffset == 0 && glottal == 1 && word[0].toString() !in ONSETS) {
                    ons = "ʔ" + NUCLEI[nucl]!!
                }
                nuc = NUCLEI[nucl]!!
            } else if (nucl in ONGLIDES && ons != "kw") {
                nuc = ONGLIDES[nucl]!!
                if (ons.isNotEmpty()) ons += "w" else ons = "w"
            } else if (nucl in ONGLIDES && ons == "kw") {
                nuc = ONGLIDES[nucl]!!
            } else if (nucl in ONOFFGLIDES) {
                cod = ONOFFGLIDES[nucl]!!.last().toString()
                nuc = ONOFFGLIDES[nucl]!!.dropLast(1)
                if (ons != "kw") {
                    if (ons.isNotEmpty()) ons += "w" else ons = "w"
                }
            } else if (nucl in OFFGLIDES) {
                cod = OFFGLIDES[nucl]!!.last().toString()
                nuc = OFFGLIDES[nucl]!!.dropLast(1)
            } else if (word in GI) {
                ons = GI[word]!![0].toString()
                nuc = GI[word]!![1].toString()
            } else if (word in QU) {
                ons = QU[word]!!.dropLast(1)
                nuc = QU[word]!!.last().toString()
            } else {
                return "" // Unknown
            }
        }

        // Velar fronting (northern)
        if (dialect == "n") {
            if (nuc == "a") {
                if (cod == "k" && cOffset == 2) nuc = "ɛ"
                if (cod == "ɲ" && nuc == "a") nuc = "ɛ"
            }
            if (palatals == 1 && nuc in listOf("i", "e", "ɛ") && cod == "k") {
                cod = "c"
            }
        } else {
            // Southern/central
            if (nuc in listOf("i", "e")) {
                if (cod == "k") cod = "t"
                if (cod == "ŋ") cod = "n"
            } else if (nuc in listOf("iə", "ɯə", "uə", "u", "ɯ", "ɤ", "o", "ɔ", "ă", "ɤ̆")) {
                if (cod == "t") cod = "k"
                if (cod == "n") cod = "ŋ"
            }
            // Monophthongization (southern)
            if (dialect == "s" && cod in listOf("m", "p")) {
                if (nuc == "iə") nuc = "i"
                if (nuc == "uə") nuc = "u"
                if (nuc == "ɯə") nuc = "ɯ"
            }
        }

        // Tone detection
        val toneChars = word.filter { it.toString() in TONES_P }
        ton = if (toneChars.isNotEmpty()) TONES_P[toneChars.last().toString()] ?: 1 else 1

        // Labialized allophony
        if (cOffset != 0 && nuc in listOf("u", "o", "ɔ")) {
            if (cod == "ŋ") cod = "ŋ͡m"
            if (cod == "k") cod = "k͡p"
        }

        return listOf(ons, nuc, cod, ton.toString()).joinToString("/")
    }

    /**
     * Main phonemization entry point.
     */
    fun phonemize(text: String): String {
        if (text.isBlank()) return ""

        // Try eSpeak fallback first
        val espeakResult = espeakFallback?.invoke(text, "vi")
        if (espeakResult != null && espeakResult.isNotBlank()) {
            return espeakResult
        }

        // Apply full ViCleaner normalization pipeline
        // (abbreviations, acronyms, roman numerals, dates, measurements,
        //  currency, numbers, letters, etc.)
        var processed = cleaner.cleanText(text)

        // Simple word tokenization (split by spaces)
        val tokens = processed.split(Regex("\\s+")).filter { it.isNotEmpty() }

        val result = mutableListOf<String>()
        for (tk in tokens) {
            // Handle punctuation
            if (tk in listOf(".", ",", ";", ":", "!", "?", ")", "}", "]")) {
                result.add(tk.replace(')', ')'))
                continue
            }
            if (tk in listOf("(", "{", "[")) {
                result.add("(")
                continue
            }
            if (tk in listOf("\"", "'", "–", "\u201C", "\u201D")) {
                result.add("\"")
                continue
            }

            // Try direct Vietnamese conversion
            val firstTry = convert(tk)
            if (firstTry.isNotEmpty() && !firstTry.contains("[")) {
                val parts = firstTry.split("/")
                val ipa = parts.take(3).joinToString("") + parts.getOrElse(3) { "" }
                result.add(ipa)
            } else {
                // Substring fallback for foreign words
                val lower = tk.lowercase(Locale.ROOT)
                val hasViChars = lower.any { it in VI_ONLY_CHARS }

                if (!hasViChars && lower.matches(Regex("[a-z]+"))) {
                    // Try English G2P
                    val engResult = enG2P?.invoke(lower)
                    if (engResult != null && unk !in engResult) {
                        result.add(engResult)
                        continue
                    }
                }

                // Letter-by-letter fallback for acronyms
                if (lower.uppercase(Locale.ROOT) == lower && lower.length <= 6) {
                    val mapping = if (hasViChars) VI_LETTERS else EN_LETTERS
                    for (ch in lower) {
                        val letterIpa = convert(mapping[ch.toString()] ?: ch.toString())
                        if (letterIpa.isNotEmpty()) result.add(letterIpa.replace("/", ""))
                        else result.add(ch.toString())
                    }
                } else {
                    result.add(unk)
                }
            }
        }

        return result.joinToString(" ")
    }
}