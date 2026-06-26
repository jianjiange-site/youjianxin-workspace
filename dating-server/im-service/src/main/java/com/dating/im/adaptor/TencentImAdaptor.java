package com.dating.im.adaptor;

import com.dating.im.model.ImMessage;
import com.dating.im.model.event.ImEvent;
import com.dating.im.model.event.MessageBeforeSendEvent;
import com.dating.im.model.event.MessageSentEvent;
import com.dating.im.model.event.UnknownEvent;
import com.dating.youjianxin.proto.im.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Parses Tencent IM webhook callbacks into {@link ImEvent}. Dispatches on {@code CallbackCommand}:
 * {@code *.CallbackAfterSendMsg} → {@link MessageSentEvent}, {@code *.CallbackBeforeSendMsg} →
 * {@link MessageBeforeSendEvent}; anything else → {@link UnknownEvent}.
 *
 * <p>The expected JSON structure (simplified):
 * <pre>
 * {
 *   "CallbackCommand": "C2C.CallbackAfterSendMsg",
 *   "From_Account": "user123",
 *   "To_Account": "user456",
 *   "MsgSeq": 100,
 *   "MsgTime": 1717171200,
 *   "MsgBody": [{"MsgType": "TIMTextElem", "MsgContent": {"Text": "hello"}}]
 * }
 * </pre>
 */
@Component
public class TencentImAdaptor implements ImProviderAdaptor {

    private static final Logger log = LoggerFactory.getLogger(TencentImAdaptor.class);
    private static final String PROVIDER = "tencent_im";

    // JSON parsing is done manually to avoid heavy dependencies; in production consider Jackson
    @Override
    public boolean supports(String provider) {
        return PROVIDER.equals(provider);
    }

    @Override
    public ImEvent parse(byte[] rawPayload) {
        String json = new String(rawPayload, StandardCharsets.UTF_8);
        log.debug("Parsing Tencent IM callback: {}", json);

        String cmd = extractString(json, "CallbackCommand");
        if (cmd == null) {
            cmd = "";
        }
        if (cmd.contains("AfterSendMsg")) {
            return new MessageSentEvent(toMessage(json, cmd), cmd);
        }
        if (cmd.contains("BeforeSendMsg")) {
            return new MessageBeforeSendEvent(toMessage(json, cmd), cmd);
        }
        return new UnknownEvent(PROVIDER, cmd, json);
    }

    private ImMessage toMessage(String json, String cmd) {
        String fromAccount = extractString(json, "From_Account");
        String toAccount = extractString(json, "To_Account");
        long msgTime = extractLong(json, "MsgTime");
        long msgSeq = extractLong(json, "MsgSeq");

        MessageType type = extractMsgType(json);
        String content = extractContent(json, type);

        return ImMessage.builder()
                .messageId(PROVIDER + "_" + msgSeq)
                .fromUserId(fromAccount)
                .toUserId(toAccount)
                .content(content)
                .type(type)
                .conversationType("C2C")
                .provider(PROVIDER)
                .timestamp(msgTime)
                .putMetadata("msg_seq", String.valueOf(msgSeq))
                .putMetadata("callback_command", cmd)
                .build();
    }

    /** Determines the message type from the first MsgBody entry. */
    private MessageType extractMsgType(String json) {
        String bodyType = extractFirstBodyField(json, "MsgType");
        if (bodyType == null) return MessageType.MESSAGE_TYPE_UNKNOWN;
        if ("TIMTextElem".equals(bodyType)) return MessageType.TEXT;
        if ("TIMImageElem".equals(bodyType)) return MessageType.IMAGE;
        if ("TIMSoundElem".equals(bodyType)) return MessageType.AUDIO;
        if ("TIMVideoFileElem".equals(bodyType)) return MessageType.VIDEO;
        if ("TIMCustomElem".equals(bodyType)) return MessageType.CUSTOM;
        return MessageType.MESSAGE_TYPE_UNKNOWN;
    }

    /** Extracts text content from the first MsgBody entry. */
    private String extractContent(String json, MessageType type) {
        if (type == MessageType.TEXT) {
            return extractFirstBodyField(json, "Text");
        }
        // For non-text types, content is a placeholder; actual media URLs go in metadata
        String desc = extractFirstBodyField(json, "MsgType");
        return desc != null ? "[" + desc + "]" : "";
    }

    // ---- minimal JSON extraction helpers (no external dependency) ----

    private String extractString(String json, String key) {
        return extractValue(json, "\"" + key + "\"", "\"", "\"");
    }

    private long extractLong(String json, String key) {
        String v = extractValue(json, "\"" + key + "\"", ":", ",}]");
        if (v == null) return 0;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String extractFirstBodyField(String json, String fieldName) {
        // Find MsgBody array and extract field from the first object
        int bodyIdx = json.indexOf("\"MsgBody\"");
        if (bodyIdx < 0) return null;
        // Find first occurrence of the field within the MsgBody
        int searchStart = json.indexOf("{", bodyIdx);
        if (searchStart < 0) return null;
        int firstBraceEnd = findClosingBrace(json, searchStart);
        if (firstBraceEnd < 0) return null;
        String firstBodyObj = json.substring(searchStart, firstBraceEnd + 1);

        // Now find the field within this object
        int fieldIdx = firstBodyObj.indexOf("\"" + fieldName + "\"");
        if (fieldIdx < 0) {
            // Try MsgContent sub-object
            int mcIdx = firstBodyObj.indexOf("\"MsgContent\"");
            if (mcIdx < 0) return null;
            int mcStart = firstBodyObj.indexOf("{", mcIdx);
            if (mcStart < 0) return null;
            int mcEnd = findClosingBrace(firstBodyObj, mcStart);
            if (mcEnd < 0) return null;
            String msgContent = firstBodyObj.substring(mcStart, mcEnd + 1);
            fieldIdx = msgContent.indexOf("\"" + fieldName + "\"");
            if (fieldIdx < 0) return null;
            return extractValue(msgContent, "\"" + fieldName + "\"", "\"", "\"");
        }
        return extractValue(firstBodyObj, "\"" + fieldName + "\"", "\"", "\"");
    }

    /**
     * Extracts a value after a key pattern.
     * @param keyPattern the key to search for, e.g. {@code "From_Account"}
     * @param afterPrefix what separates key from value, e.g. {@code ":"}
     * @param endChars value delimiter characters
     */
    private String extractValue(String json, String keyPattern, String afterPrefix, String endChars) {
        int keyIdx = json.indexOf(keyPattern);
        if (keyIdx < 0) return null;
        int valStart = json.indexOf(afterPrefix, keyIdx + keyPattern.length());
        if (valStart < 0) return null;
        // For "key":"value" pattern, skip past the opening quote after colon
        if (afterPrefix.contains("\"") && json.charAt(valStart) == '"') {
            valStart++;
        }
        // For "key": 123 pattern, skip whitespace after colon
        if (afterPrefix.equals(":")) {
            valStart = valStart + 1; // skip the colon
            while (valStart < json.length() && Character.isWhitespace(json.charAt(valStart))) {
                valStart++;
            }
        }
        int valEnd = valStart;
        while (valEnd < json.length() && endChars.indexOf(json.charAt(valEnd)) < 0) {
            valEnd++;
        }
        if (valEnd == valStart) return "";
        return json.substring(valStart, valEnd);
    }

    private int findClosingBrace(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}
