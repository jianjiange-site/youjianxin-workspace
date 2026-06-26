package com.dating.im.util;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 反导流检测:识别消息文本里的站外联系方式 / 社交账号(美国手机号、Instagram、Facebook、WhatsApp、
 * Telegram)。命中即认为用户试图把对话引导到站外。
 *
 * <p>{@link #detect(String)} 返回命中的类别名(供日志 / 监控打标)或 {@code null}(未命中)。
 *
 * <p>基础版按「号码格式 + 平台 URL + 关键词」匹配,大小写不敏感。{@code ig}/{@code ins}/{@code fb}/
 * {@code tg} 这类极短缩写易误伤普通英文词,只在后面紧跟 {@code :} / 全角 {@code :} / {@code @} 时才算命中。
 *
 * <p>TODO(硬化):unicode 同形字、数字间插空格 / emoji、把 "at"/"dot" 写成单词等绕过手法,后续按需补。
 */
@Component
public class ContactInfoDetector {

    /**
     * 美国手机号(NANP):可选 +1 / 1 国家码,区号与交换码首位 2-9,允许 空格 / {@code -} / {@code .} /
     * 括号 作分隔,也匹配 10 位连号。前后用 {@code (?<!\d)} / {@code (?!\d)} 防止切到更长数字串中间。
     */
    private static final Pattern US_PHONE = Pattern.compile(
            "(?<!\\d)(\\+?1[\\s.-]?)?\\(?[2-9]\\d{2}\\)?[\\s.-]?[2-9]\\d{2}[\\s.-]?\\d{4}(?!\\d)");

    private static final Pattern INSTAGRAM = Pattern.compile(
            "instagram\\.com/[\\w./-]+"
                    + "|instagr\\.am/[\\w./-]+"
                    + "|\\binstagram\\b"
                    + "|\\binsta\\b"
                    + "|\\big\\b\\s*[:：@]\\s*@?\\w"
                    + "|\\bins\\b\\s*[:：@]\\s*@?\\w",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FACEBOOK = Pattern.compile(
            "facebook\\.com/[\\w./-]+"
                    + "|fb\\.com/[\\w./-]+"
                    + "|m\\.me/[\\w./-]+"
                    + "|\\bfacebook\\b"
                    + "|\\bfb\\b\\s*[:：@]\\s*@?\\w",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern WHATSAPP = Pattern.compile(
            "wa\\.me/[\\w./+-]+"
                    + "|\\bwhatsapp\\b"
                    + "|\\bwhats\\s?app\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TELEGRAM = Pattern.compile(
            "t\\.me/[\\w./+-]+"
                    + "|\\btelegram\\b"
                    + "|\\btg\\b\\s*[:：@]\\s*@?\\w",
            Pattern.CASE_INSENSITIVE);

    /**
     * 有序:命中后返回第一个匹配的类别名。平台(URL/关键词)更具体,排在最泛的手机号前面 —— 否则
     * {@code wa.me/12345678901} 里的号码会被先判成 {@code us_phone}。
     */
    private static final Map<String, Pattern> RULES = new LinkedHashMap<>();

    static {
        RULES.put("instagram", INSTAGRAM);
        RULES.put("facebook", FACEBOOK);
        RULES.put("whatsapp", WHATSAPP);
        RULES.put("telegram", TELEGRAM);
        RULES.put("us_phone", US_PHONE);
    }

    /**
     * @param content 消息文本(仅 TEXT 消息有意义)
     * @return 命中的类别名(us_phone / instagram / facebook / whatsapp / telegram),未命中返回 {@code null}
     */
    public String detect(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, Pattern> rule : RULES.entrySet()) {
            if (rule.getValue().matcher(content).find()) {
                return rule.getKey();
            }
        }
        return null;
    }
}
