package com.dating.match.constant;

/**
 * match_outbox.action 取值。
 *
 * <p>3 个 action 对应 docs §5.3 的副作用列表:
 * <ul>
 *   <li>ENSURE_CONVERSATION:调 im-service 建/确认 C2C 会话</li>
 *   <li>SYSTEM_MSG:给双方各发"你们配对了"系统消息</li>
 *   <li>DH_OPENING:DH 端触发 ai-chat 生成开场白</li>
 * </ul>
 *
 * <p>im.proto 不新增 RPC(docs §6.7),三类副作用都用 ImService.SendMessage(type=CUSTOM, metadata.action=...) 表达。
 */
public final class OutboxAction {

    private OutboxAction() {}

    public static final String ENSURE_CONVERSATION = "ENSURE_CONVERSATION";
    public static final String SYSTEM_MSG = "SYSTEM_MSG";
    public static final String DH_OPENING = "DH_OPENING";
}
