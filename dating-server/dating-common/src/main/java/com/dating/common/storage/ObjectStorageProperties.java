package com.dating.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

// dating.storage.* 配置;真实 accessKey/secretKey 走 Nacos / .env.deploy,绝不入仓。
// 5 个必填字段(endpoint/region/accessKey/secretKey/bucket)缺一启动期 fail-fast。
@ConfigurationProperties("dating.storage")
public class ObjectStorageProperties {

    private String endpoint;
    private String region;
    private String accessKey;
    private String secretKey;
    private String bucket;

    private int presignedPutTtlSeconds = 300;
    private int presignedGetTtlSeconds = 1800;

    // 公开类下行的 CDN 基址;留空则 publicUrl 回退到 endpoint(直接拼 iDrive e2 公网域名)
    private String cdnBaseUrl = "";

    // S3 兼容 provider(iDrive e2)一律 path-style,AWS 原生 S3 才需关
    private boolean pathStyleAccess = true;

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }

    public int getPresignedPutTtlSeconds() { return presignedPutTtlSeconds; }
    public void setPresignedPutTtlSeconds(int presignedPutTtlSeconds) { this.presignedPutTtlSeconds = presignedPutTtlSeconds; }

    public int getPresignedGetTtlSeconds() { return presignedGetTtlSeconds; }
    public void setPresignedGetTtlSeconds(int presignedGetTtlSeconds) { this.presignedGetTtlSeconds = presignedGetTtlSeconds; }

    public String getCdnBaseUrl() { return cdnBaseUrl; }
    public void setCdnBaseUrl(String cdnBaseUrl) { this.cdnBaseUrl = cdnBaseUrl; }

    public boolean isPathStyleAccess() { return pathStyleAccess; }
    public void setPathStyleAccess(boolean pathStyleAccess) { this.pathStyleAccess = pathStyleAccess; }
}
