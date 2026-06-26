package com.dating.im.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits an AI reply into sentence-sized chunks so a long answer is delivered as several
 * short messages (more human-like) instead of one wall of text.
 *
 * <p>规则:
 * <ul>
 *   <li>句末结束符:{@code . ? !} 与全角 {@code 。 ？ ！ …};连续结束符(如 {@code ?!}、{@code ...}、
 *       {@code ……})归到同一句末。</li>
 *   <li>小数/缩写守卫:ASCII {@code .} 当前后都是数字(如 {@code 3.14})不视作句末。</li>
 *   <li>无标点的尾句照常切出;整段无任何结束符时返回单元素列表(整段)。</li>
 *   <li>上限收敛:句数超过 {@code maxMessages} 时,保留前 {@code maxMessages-1} 句,其余拼成最后一条
 *       (内容不丢、不刷屏)。</li>
 * </ul>
 * 每条 trim 后入列,空白片段被丢弃。
 */
public final class SentenceSplitter {

    /** 句末结束符(中英文)。 */
    private static final String TERMINATORS = ".?!。？！…";

    private SentenceSplitter() {
    }

    /**
     * @param text        待切分文本(可为 null / 空)
     * @param maxMessages 最多切成几条(&lt;=0 视作不限)
     * @return 非空句子列表;text 为空时返回空列表
     */
    public static List<String> split(String text, int maxMessages) {
        List<String> sentences = new ArrayList<>();
        if (text == null) {
            return sentences;
        }

        int len = text.length();
        StringBuilder buf = new StringBuilder();
        int i = 0;
        while (i < len) {
            char c = text.charAt(i);
            buf.append(c);

            if (isTerminator(c) && !isDecimalDot(text, i)) {
                // 吃掉连续的结束符(?!、...、…… 等)
                int j = i + 1;
                while (j < len && isTerminator(text.charAt(j))) {
                    buf.append(text.charAt(j));
                    j++;
                }
                // 吃掉其后空白,不带入下一句
                while (j < len && Character.isWhitespace(text.charAt(j))) {
                    j++;
                }
                flush(buf, sentences);
                i = j;
            } else {
                i++;
            }
        }
        flush(buf, sentences);

        return capToMax(sentences, maxMessages);
    }

    private static boolean isTerminator(char c) {
        return TERMINATORS.indexOf(c) >= 0;
    }

    /** ASCII '.' 且前后皆数字(如 3.14)→ 不是句末。 */
    private static boolean isDecimalDot(String text, int idx) {
        if (text.charAt(idx) != '.') {
            return false;
        }
        if (idx == 0 || idx + 1 >= text.length()) {
            return false;
        }
        return Character.isDigit(text.charAt(idx - 1)) && Character.isDigit(text.charAt(idx + 1));
    }

    private static void flush(StringBuilder buf, List<String> out) {
        String s = buf.toString().trim();
        if (!s.isEmpty()) {
            out.add(s);
        }
        buf.setLength(0);
    }

    /** 句数超过上限时,把多余句子并进最后一条;maxMessages<=0 表示不限。 */
    private static List<String> capToMax(List<String> sentences, int maxMessages) {
        if (maxMessages <= 0 || sentences.size() <= maxMessages) {
            return sentences;
        }
        List<String> capped = new ArrayList<>(sentences.subList(0, maxMessages - 1));
        capped.add(String.join(" ", sentences.subList(maxMessages - 1, sentences.size())));
        return capped;
    }
}
