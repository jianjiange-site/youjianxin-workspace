package com.dating.im.adaptor;

import com.dating.im.model.ImMessage;
import com.dating.im.model.event.ImEvent;
import com.dating.im.model.event.MessageBeforeSendEvent;
import com.dating.im.model.event.MessageSentEvent;
import com.dating.im.model.event.UnknownEvent;
import com.dating.im.model.event.UserOfflineEvent;
import com.dating.im.model.event.UserOnlineEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dating.youjianxin.proto.im.MessageType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link OpenImAdaptor} — PictureElem URL extraction for IMAGE messages, plus
 * {@code callbackCommand} dispatch into the right {@link ImEvent} variant.
 */
class OpenImAdaptorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenImAdaptor adaptor = new OpenImAdaptor(objectMapper);

    /** content 为 PictureElem JSON 字符串(OpenIM 常见形态)时,三图齐全优先取最小的缩略图。 */
    @Test
    void prefersSmallestSnapshotPicture() throws Exception {
        String elem = """
                {"sourcePicture":{"url":"https://cdn/src.jpg"},
                 "bigPicture":{"url":"https://cdn/big.jpg"},
                 "snapshotPicture":{"url":"https://cdn/snap.jpg"}}""";
        ImMessage msg = parseMessage(imageCallback(elem));

        assertEquals(MessageType.IMAGE, msg.type());
        assertEquals("https://cdn/snap.jpg", msg.metadata().get(OpenImAdaptor.METADATA_IMAGE_URL));
        assertEquals(elem, msg.content()); // content 原样保留
    }

    /** 缺缩略图时回退 bigPicture.url(仍小于原图)。 */
    @Test
    void fallsBackToBigPicture() throws Exception {
        String elem = "{\"sourcePicture\":{\"url\":\"https://cdn/src.jpg\"},\"bigPicture\":{\"url\":\"https://cdn/big.jpg\"}}";
        ImMessage msg = parseMessage(imageCallback(elem));
        assertEquals("https://cdn/big.jpg", msg.metadata().get(OpenImAdaptor.METADATA_IMAGE_URL));
    }

    /** 只有原图时回退 sourcePicture.url。 */
    @Test
    void fallsBackToSourcePicture() throws Exception {
        String elem = "{\"sourcePicture\":{\"url\":\"https://cdn/src.jpg\"}}";
        ImMessage msg = parseMessage(imageCallback(elem));
        assertEquals("https://cdn/src.jpg", msg.metadata().get(OpenImAdaptor.METADATA_IMAGE_URL));
    }

    /** content 是嵌套对象(非字符串)时也能解析。 */
    @Test
    void extractsFromNestedObjectContent() throws Exception {
        Map<String, Object> pic = new LinkedHashMap<>();
        pic.put("sourcePicture", Map.of("url", "https://cdn/obj.jpg"));
        Map<String, Object> root = baseRoot(102);
        root.put("content", pic); // 嵌套对象,不是字符串
        ImMessage msg = parseMessage(objectMapper.writeValueAsBytes(root));
        assertEquals("https://cdn/obj.jpg", msg.metadata().get(OpenImAdaptor.METADATA_IMAGE_URL));
    }

    /** PictureElem 无任何 url 字段 → 不写 image_url,不抛异常。 */
    @Test
    void noUrlYieldsNoMetadata() throws Exception {
        ImMessage msg = parseMessage(imageCallback("{\"sourcePath\":\"x\"}"));
        assertFalse(msg.metadata().containsKey(OpenImAdaptor.METADATA_IMAGE_URL));
    }

    /** content 不是合法 JSON → 不写 image_url,不抛异常。 */
    @Test
    void malformedContentYieldsNoMetadata() throws Exception {
        ImMessage msg = parseMessage(imageCallback("not-a-json"));
        assertFalse(msg.metadata().containsKey(OpenImAdaptor.METADATA_IMAGE_URL));
    }

    /** TEXT 消息不解析图片,content 为原文。 */
    @Test
    void textMessageHasNoImageUrl() throws Exception {
        Map<String, Object> root = baseRoot(101);
        root.put("content", "hello");
        ImMessage msg = parseMessage(objectMapper.writeValueAsBytes(root));
        assertEquals(MessageType.TEXT, msg.type());
        assertEquals("hello", msg.content());
        assertFalse(msg.metadata().containsKey(OpenImAdaptor.METADATA_IMAGE_URL));
    }

    @Test
    void supportsOpenimProvider() {
        assertTrue(adaptor.supports("openim"));
        assertFalse(adaptor.supports("tencent"));
    }

    // ---- callbackCommand dispatch ----

    /** before-send 回调 → MessageBeforeSendEvent(同样携带 message)。 */
    @Test
    void beforeSendParsedAsBeforeEvent() throws Exception {
        Map<String, Object> root = baseRoot(101);
        root.put("callbackCommand", "callbackBeforeSendSingleMsgCommand");
        root.put("content", "hi");
        ImEvent ev = adaptor.parse(objectMapper.writeValueAsBytes(root));
        assertInstanceOf(MessageBeforeSendEvent.class, ev);
        assertEquals("hi", ((MessageBeforeSendEvent) ev).message().content());
    }

    /** userOnline 回调 → UserOnlineEvent,不产生消息(修复"未知回调落垃圾消息行")。 */
    @Test
    void userOnlineParsedAsOnlineEvent() throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("callbackCommand", "callbackUserOnlineCommand");
        root.put("userID", "100");
        root.put("platformID", 1);
        ImEvent ev = adaptor.parse(objectMapper.writeValueAsBytes(root));
        assertInstanceOf(UserOnlineEvent.class, ev);
        assertEquals("100", ((UserOnlineEvent) ev).userId());
    }

    /** userOffline 回调 → UserOfflineEvent。 */
    @Test
    void userOfflineParsedAsOfflineEvent() throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("callbackCommand", "callbackUserOfflineCommand");
        root.put("userID", "200");
        root.put("platformID", 2);
        ImEvent ev = adaptor.parse(objectMapper.writeValueAsBytes(root));
        assertInstanceOf(UserOfflineEvent.class, ev);
        assertEquals("200", ((UserOfflineEvent) ev).userId());
    }

    /** 未建模的 callbackCommand → UnknownEvent(log+ack,不落库)。 */
    @Test
    void unknownCommandParsedAsUnknownEvent() throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("callbackCommand", "callbackAfterRevokeMsgCommand");
        ImEvent ev = adaptor.parse(objectMapper.writeValueAsBytes(root));
        assertInstanceOf(UnknownEvent.class, ev);
        assertEquals("callbackAfterRevokeMsgCommand", ev.callbackCommand());
    }

    // --- helpers ---

    /** Parse an after-send callback and return the carried message. */
    private ImMessage parseMessage(byte[] payload) {
        ImEvent ev = adaptor.parse(payload);
        assertInstanceOf(MessageSentEvent.class, ev);
        return ((MessageSentEvent) ev).message();
    }

    private Map<String, Object> baseRoot(int contentType) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("callbackCommand", "callbackAfterSendSingleMsgCommand");
        root.put("sendID", "100");
        root.put("recvID", "200");
        root.put("contentType", contentType);
        root.put("serverMsgID", "srv1");
        root.put("sendTime", 1717171200000L);
        return root;
    }

    /** 构造一条 contentType=102 的图片回调,content 为 PictureElem JSON 字符串。 */
    private byte[] imageCallback(String pictureElemJson) throws Exception {
        Map<String, Object> root = baseRoot(102);
        root.put("content", pictureElemJson);
        return objectMapper.writeValueAsBytes(root);
    }
}
