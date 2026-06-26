package com.dating.im.client;

import com.dating.youjianxin.proto.vision.AnalyzeProfilePhotoRequest;
import com.dating.youjianxin.proto.vision.AnalyzeProfilePhotoResponse;
import com.dating.youjianxin.proto.vision.ScoreFaceRequest;
import com.dating.youjianxin.proto.vision.ScoreFaceResponse;
import com.dating.youjianxin.proto.vision.UnderstandRequest;
import com.dating.youjianxin.proto.vision.UnderstandResponse;
import com.dating.youjianxin.proto.vision.VisionAgentGrpc;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * gRPC client for the VisionAgent (图像理解 / 颜值打分 / 头像分析).
 *
 * <p>VisionAgent 与 ChatAgent 由 chat-agent 注册在<b>同一进程、同一 Nacos 服务 {@code ai-chat}</b>
 * 上，因此这里复用与 {@link AiChatGrpcClient} 相同的 {@code @GrpcClient("ai-chat")} 通道，无需在
 * application.yml 另配 client。两个 client 各包各自的 stub，职责分离。
 *
 * <p>注意:chat-agent 的 VisionAgent 注册是<b>可降级</b>的(缺 GROQ/GOOGLE key 时不注册),此时调用
 * 会得到 {@code UNIMPLEMENTED};所有方法统一 catch {@link StatusRuntimeException} → 打日志并返回
 * {@code null},由调用方决定回退。vision LLM 较慢,每次调用带 {@code DEADLINE_SECONDS} 截止时间兜底。
 */
@Component
public class VisionAgentGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(VisionAgentGrpcClient.class);

    /** vision LLM 较慢,单次调用截止时间。 */
    private static final long DEADLINE_SECONDS = 30;

    @GrpcClient("ai-chat")
    private VisionAgentGrpc.VisionAgentBlockingStub visionAgentStub;

    /**
     * 图像理解:按 prompt 对一张/多张图片做自然语言理解。
     *
     * @param imageUrls 可公网访问的图片 URL(vision LLM 服务端 fetch),不可为空
     * @param prompt    理解指令,可空(空则 vision agent 用默认"描述图片")
     * @return 自然语言理解结果,失败返回 {@code null}
     */
    public String understand(List<String> imageUrls, String prompt) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return null;
        }
        UnderstandRequest.Builder req = UnderstandRequest.newBuilder().addAllImageUrls(imageUrls);
        if (prompt != null) {
            req.setPrompt(prompt);
        }
        try {
            UnderstandResponse resp = stub().understand(req.build());
            String content = resp.getContent();
            log.info("vision.understand: images={} length={}", imageUrls.size(),
                    content != null ? content.length() : 0);
            return content;
        } catch (StatusRuntimeException e) {
            log.error("vision.understand failed: images={} status={}", imageUrls.size(), e.getStatus(), e);
            return null;
        }
    }

    /**
     * 颜值打分:对一张/多张图片打分(多图取平均),返回结构化分数。
     *
     * @param imageUrls 可公网访问的图片 URL,不可为空
     * @return 结构化分数,失败返回 {@code null}
     */
    public VisionScore scoreFace(List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return null;
        }
        ScoreFaceRequest req = ScoreFaceRequest.newBuilder().addAllImageUrls(imageUrls).build();
        try {
            ScoreFaceResponse resp = stub().scoreFace(req);
            return new VisionScore(resp.getStatus(), resp.getAppearance(), resp.getSexualAttractivenessScore());
        } catch (StatusRuntimeException e) {
            log.error("vision.scoreFace failed: images={} status={}", imageUrls.size(), e.getStatus(), e);
            return null;
        }
    }

    /**
     * 头像/资料照分析:返回 "描述 | 风格标签" / not_human / anime_human。
     *
     * @param imageUrl 单张资料照 URL,不可为空
     * @return 分析结果,失败返回 {@code null}
     */
    public String analyzeProfilePhoto(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            return null;
        }
        AnalyzeProfilePhotoRequest req = AnalyzeProfilePhotoRequest.newBuilder().setImageUrl(imageUrl).build();
        try {
            AnalyzeProfilePhotoResponse resp = stub().analyzeProfilePhoto(req);
            return resp.getAnalysis();
        } catch (StatusRuntimeException e) {
            log.error("vision.analyzeProfilePhoto failed: status={}", e.getStatus(), e);
            return null;
        }
    }

    private VisionAgentGrpc.VisionAgentBlockingStub stub() {
        return visionAgentStub.withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS);
    }

    /** 颜值打分结果(0-100)。 */
    public record VisionScore(int status, int appearance, int sexualAttractiveness) {
    }
}
