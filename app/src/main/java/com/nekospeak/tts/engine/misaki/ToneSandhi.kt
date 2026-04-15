package com.nekospeak.tts.engine.misaki

/**
 * Faithful port of upstream Misaki's Chinese Tone Sandhi (tone_sandhi.py).
 * Reference: https://github.com/hexgrad/misaki/blob/main/misaki/tone_sandhi.py
 * Original: https://github.com/PaddlePaddle/PaddleSpeech (Apache 2.0)
 *
 * Implements Mandarin Chinese tone modification rules:
 * 1. 不 (bù) sandhi: 不 before 4th tone → bú (2nd tone)
 * 2. 一 (yī) sandhi:
 *    - 一 before 4th/5th tone → yí (2nd tone)
 *    - 一 before non-4th tone → yì (4th tone)
 *    - 一 in reduplication (看一看) → yi (5th tone / neutral)
 *    - 第一 → dì yī (1st tone preserved)
 * 3. Neural tone sandhi: final syllable neutralization rules
 * 4. Three-tone sandhi: consecutive 3rd tones → first becomes 2nd tone
 *
 * This port operates on pinyin strings with tone numbers (e.g. "bu4", "yi1").
 */
class ToneSandhi {
    companion object {
        private const val BU = '不'
        private const val YI = '一'
        private val PUNC = setOf('、', '：', '，', '；', '。', '？', '！', '"', '"', '\'', '\'', '\'', ':', ',', '.', '?', '!')

        // Words where the last syllable must be neutral tone (5)
        private val MUST_NEURAL = setOf(
            "麻烦", "骨头", "骆驼", "首饰", "馒头", "风筝", "队伍", "闺女",
            "门道", "铺盖", "铃铛", "钥匙", "部分", "那么", "这么", "这个",
            "过去", "踏实", "跟头", "豆腐", "讲究", "记性", "认识", "规矩",
            "见识", "裁缝", "衣裳", "衣服", "街坊", "行李", "蛤蟆", "蘑菇",
            "葫芦", "葡萄", "萝卜", "苗条", "苍蝇", "芝麻", "舒服", "舌头",
            "自在", "脾气", "脑袋", "脊梁", "胳膊", "胭脂", "胡琴", "胡同",
            "聪明", "耽误", "耳朵", "老爷", "老实", "老婆", "将军", "罗嗦",
            "罐头", "编辑", "结实", "红火", "累赘", "糊涂", "精神", "粮食",
            "篱笆", "算计", "算盘", "答应", "笑话", "窟窿", "窝囊", "窗户",
            "稳当", "稀罕", "称呼", "秧歌", "秀气", "秀才", "福气", "祖宗",
            "码头", "石榴", "石头", "知识", "眼睛", "眉毛", "相声", "盘算",
            "白净", "痛快", "疙瘩", "疏忽", "生意", "甘蔗", "琵琶", "琢磨",
            "琉璃", "玻璃", "玫瑰", "狐狸", "状元", "特务", "牲口", "牌楼",
            "爽快", "爱人", "热闹", "烧饼", "烟筒", "点心", "灯笼", "火候",
            "漂亮", "溜达", "温和", "清楚", "消息", "浪头", "活泼", "比方",
            "正经", "欺负", "模糊", "棺材", "棉花", "核桃", "栅栏", "柴火",
            "架势", "枕头", "枇杷", "机灵", "本事", "木头", "朋友", "月饼",
            "月亮", "暖和", "明白", "时候", "新鲜", "故事", "收拾", "收成",
            "提防", "挖苦", "挑剔", "指甲", "拳头", "招牌", "招呼", "抬举",
            "护士", "折腾", "打量", "打算", "打扮", "打听", "打发", "扎实",
            "扁担", "戒指", "懒得", "意识", "意思", "悟性", "怪物", "思量",
            "怎么", "念头", "别人", "快活", "忙活", "志气", "心思", "得罪",
            "张罗", "弟兄", "开通", "应酬", "庄稼", "干事", "帮手", "帐篷",
            "师父", "师傅", "巴结", "巴掌", "差事", "工夫", "岁数", "屁股",
            "尾巴", "少爷", "小气", "小伙", "将就", "对头", "对付", "寡妇",
            "家伙", "客气", "实在", "官司", "学问", "字号", "嫁妆", "媳妇",
            "媒人", "婆家", "娘家", "委屈", "姑娘", "姐夫", "妥当", "妖精",
            "奴才", "女婿", "头发", "太阳", "大爷", "大方", "大意", "大夫",
            "多少", "多么", "外甥", "壮实", "地道", "地方", "在乎", "困难",
            "嘴巴", "嘱咐", "嘀咕", "喜欢", "喇嘛", "喇叭", "商量", "唾沫",
            "哑巴", "哈欠", "哆嗦", "咳嗽", "和尚", "告诉", "告示", "含糊",
            "吓唬", "后头", "名字", "名堂", "合同", "吆喝", "叫唤", "口袋",
            "厚道", "厉害", "包袱", "包涵", "勤快", "动静", "动弹", "功夫",
            "力气", "前头", "刺猬", "别扭", "利落", "利索", "利害", "分析",
            "出息", "凑合", "凉快", "冤枉", "冒失", "养活", "关系", "先生",
            "兄弟", "便宜", "使唤", "佩服", "作坊", "体面", "位置", "似的",
            "伙计", "休息", "什么", "人家", "亲戚", "亲家", "交情", "云彩",
            "事情", "买卖", "主意", "丫头", "丧气", "东西", "东家", "世故",
            "下水", "下巴", "上头", "上司", "丈夫", "丈人", "一辈", "那个",
            "菩萨", "父亲", "母亲", "咕噜", "邋遢", "费用", "冤家", "甜头",
            "介绍", "荒唐", "大人", "泥鳅", "幸福", "熟悉", "计划", "扑腾",
            "蜡烛", "姥爷", "照顾", "喉咙", "吉他", "弄堂", "蚂蚱", "凤凰",
            "拖沓", "寒碜", "糟蹋", "倒腾", "报复", "逻辑", "盘缠", "牢骚",
            "咖喱", "扫把", "惦记"
        )

        // Words where the last syllable must NOT be neutral tone
        private val MUST_NOT_NEURAL = setOf(
            "男子", "女子", "分子", "原子", "量子", "莲子", "石子", "瓜子",
            "电子", "人人", "幺幺", "干嘛", "学子", "哈哈", "数数", "袅袅",
            "局地", "以下", "想想", "整整", "莘莘", "落地", "算子"
        )
    }

    /**
     * Apply 不 sandhi to pinyin finals.
     * 不 before 4th tone → 2nd tone; 不 at position 1 in 3-char word → 5th tone.
     */
    fun buSandhi(word: String, finals: MutableList<String>): MutableList<String> {
        if (word.length == 3 && word[1] == BU) {
            finals[1] = replaceTone(finals[1], 5)
        } else {
            for (i in word.indices) {
                if (word[i] == BU && i + 1 < word.length && finals[i + 1].last() == '4') {
                    finals[i] = replaceTone(finals[i], 2)
                }
            }
        }
        return finals
    }

    /**
     * Apply 一 sandhi to pinyin finals.
     */
    fun yiSandhi(word: String, finals: MutableList<String>): MutableList<String> {
        // 一 in number sequences: preserve
        if (word.contains(YI) && word.filter { it != YI }.all { it.isDigit() }) return finals

        // Reduplication: 看一看 → yi5
        if (word.length == 3 && word[1] == YI && word[0] == word[2]) {
            finals[1] = replaceTone(finals[1], 5)
            return finals
        }

        // Ordinal: 第一 → yi1
        if (word.startsWith("第一")) {
            finals[1] = replaceTone(finals[1], 1)
            return finals
        }

        // General 一 sandhi
        for (i in word.indices) {
            if (word[i] == YI && i + 1 < word.length) {
                if (finals[i + 1].last() in setOf('4', '5')) {
                    finals[i] = replaceTone(finals[i], 2)
                } else if (word[i + 1] !in PUNC) {
                    finals[i] = replaceTone(finals[i], 4)
                }
            }
        }
        return finals
    }

    /**
     * Apply neural tone sandhi (last-syllable neutralization).
     */
    fun neuralSandhi(word: String, pos: String, finals: MutableList<String>): MutableList<String> {
        if (word in MUST_NOT_NEURAL) return finals

        // Reduplication words: 奶奶, 试试
        for (j in 1 until word.length) {
            if (word[j] == word[j - 1] && pos.first() in setOf('n', 'v', 'a')) {
                finals[j] = replaceTone(finals[j], 5)
            }
        }

        // Particles and suffixes → neutral tone (5)
        if (word.isNotEmpty()) {
            when (word.last()) {
                in "吧呢啊呐噻嘛吖嗨哦哒滴哩哟啰耶喔诶" -> finals[word.lastIndex] = replaceTone(finals[word.lastIndex], 5)
                in "的地得" -> finals[word.lastIndex] = replaceTone(finals[word.lastIndex], 5)
            }
        }

        // 了着过 as single-character particles
        if (word.length == 1 && word in setOf("了", "着", "过") && pos in setOf("ul", "uz", "ug")) {
            finals[0] = replaceTone(finals[0], 5)
        }

        // 们子 suffix → neutral
        if (word.length > 1 && word.last() in setOf('们', '子') && pos.first() in setOf('r', 'n')) {
            finals[word.lastIndex] = replaceTone(finals[word.lastIndex], 5)
        }

        // 上下 as locative → neutral
        if (word.length > 1 && word.last() in setOf('上', '下') && pos in setOf("s", "l", "f")) {
            finals[word.lastIndex] = replaceTone(finals[word.lastIndex], 5)
        }

        // Directional complements: 上来, 下去, etc.
        if (word.length > 1 && word.last() in setOf('来', '去') &&
            word[word.length - 2] in "上下进出回过起开") {
            finals[word.lastIndex] = replaceTone(finals[word.lastIndex], 5)
        }

        // 个 as classifier
        val geIdx = word.indexOf('个')
        if (geIdx >= 1 && (word[geIdx - 1].isDigit() || word[geIdx - 1] in "几有两半多各整每做是")) {
            finals[geIdx] = replaceTone(finals[geIdx], 5)
        }

        // Must-neural words
        if (word in MUST_NEURAL || word.takeLast(2) in MUST_NEURAL) {
            finals[word.lastIndex] = replaceTone(finals[word.lastIndex], 5)
        }

        return finals
    }

    /**
     * Apply three-tone sandhi: when two consecutive 3rd tones,
     * the first becomes 2nd tone.
     */
    fun threeSandhi(word: String, finals: MutableList<String>): MutableList<String> {
        val allThree = finals.all { it.last() == '3' }
        if (!allThree) {
            // Partial: check adjacent 3rd tones
            if (word.length == 4) {
                // Four-character idiom: split into pairs
                val first = finals.take(2).toMutableList()
                val second = finals.drop(2).toMutableList()
                if (first.all { it.last() == '3' }) first[0] = replaceTone(first[0], 2)
                if (second.all { it.last() == '3' }) second[0] = replaceTone(second[0], 2)
                finals.clear()
                finals.addAll(first + second)
            }
            return finals
        }

        when (word.length) {
            2 -> finals[0] = replaceTone(finals[0], 2)
            3 -> {
                // Disyllabic + monosyllabic (e.g. 蒙古包)
                finals[0] = replaceTone(finals[0], 2)
                finals[1] = replaceTone(finals[1], 2)
            }
            4 -> {
                // Split into pairs
                finals[0] = replaceTone(finals[0], 2)
                finals[2] = replaceTone(finals[2], 2)
            }
        }
        return finals
    }

    /**
     * Apply all tone sandhi modifications to pinyin finals.
     */
    fun modifiedTone(word: String, pos: String, finals: List<String>): List<String> {
        val result = finals.toMutableList()
        buSandhi(word, result)
        yiSandhi(word, result)
        neuralSandhi(word, pos, result)
        threeSandhi(word, result)
        return result
    }

    /** Replace tone number in a pinyin final (e.g. "bu4" → "bu2"). */
    private fun replaceTone(final: String, newTone: Int): String {
        if (final.isEmpty()) return final
        val last = final.last()
        return if (last.isDigit()) final.dropLast(1) + newTone else final + newTone
    }
}