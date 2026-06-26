package com.dating.common.storage;

import java.io.InputStream;
import java.time.Duration;

// 对象存储统一能力接口 —— 面向 dating-server 全部业务服务(user / post / im / payment / digital-human ...),
// 不是任何单一服务的定制。实现走 AWS SDK v2(S3 兼容,全环境 iDrive e2,dev/test/prod 通过 bucket 隔离)。
//
// 设计约束:
//   - 调用方只持本接口,不持 S3Client / S3Presigner;换 provider 只改 dating.storage.* 配置。
//   - bucket 由部署侧 dating.storage.bucket 注入,一服务一桶(CLAUDE.md)。接口刻意不暴露 bucket 入参,
//     从签名层面堵死"直读别人家桶"红线 —— 跨服务对象一律调对方 gRPC,由 owner service 自己读 / 自己签。
//
// presigned URL 适用范围(重要):
//   - presigned URL 只服务**无凭据**的外部客户端(App / H5 / 第三方)。
//   - 上行:App 直传 OSS 必须 presigned PUT(否则 AK/SK 要下放到 App,违反密钥红线)。
//   - 下行:bucket 私有 + 资产敏感时,后端签 presigned GET URL 透给 App;公开资产用 publicUrl 走 CDN 即可。
//   - **内部 RPC 服务持有 AK/SK,读写一律直接调 getObject / putObject / doesObjectExist / headObjectSize,
//     不要为自己签 presigned URL 再自己消费**。多此一举,徒增错误面。
//
// null/blank objectKey 约定:
//   - presignedGetUrl / publicUrl 容忍空 key,返回 null(可选展示资产"没有就不给 URL")。
//   - 其余方法把空 key 当编程错误,抛 IllegalArgumentException(doesObjectExist 例外:空 key 直接 false)。
public interface ObjectStorage {

    // ---- 客户端直传 / 直读(presigned URL,仅给 App / H5 等无凭据外部客户端)----

    // 默认 TTL(dating.storage.presigned-put-ttl-seconds)的 PUT presigned URL;签名失败抛 ObjectStorageException。
    // 用于:App 拿到 URL 后直传 OSS,后端不读文件流。
    String presignedPutUrl(String objectKey);

    // 指定 TTL 的 PUT presigned URL。
    String presignedPutUrl(String objectKey, Duration ttl);

    // 默认 TTL(dating.storage.presigned-get-ttl-seconds)的 GET presigned URL;key 为空返回 null。
    // 用于:bucket 私有 + 资产敏感时,后端临时下发给 App。**内部 RPC 读对象用 getObject,不要走这个**。
    String presignedGetUrl(String objectKey);

    // 指定 TTL 的 GET presigned URL。
    String presignedGetUrl(String objectKey, Duration ttl);

    // ---- 服务端直接读写(内部 RPC 凭 AK/SK 操作,无需 presign)----

    // 上传字节数组;contentType 可空(空则不设)。
    void putObject(String objectKey, byte[] content, String contentType);

    // 流式上传;必须给准确的 contentLength(S3 签名需要),调用方负责关闭传入的 stream。
    void putObject(String objectKey, InputStream content, long contentLength, String contentType);

    // 读对象全量字节;对象不存在抛 ObjectNotFoundException。仅用于中小文件,大文件让 App 走 presignedGetUrl 直读。
    byte[] getObject(String objectKey);

    // 删除对象;幂等,对象不存在不报错。
    void deleteObject(String objectKey);

    // 对象是否存在(HEAD 探测);key 为空返回 false。
    boolean doesObjectExist(String objectKey);

    // ---- 元数据 / URL ----

    // HEAD 拿 Content-Length;对象不存在抛 ObjectNotFoundException,其它错误抛 ObjectStorageException。
    long headObjectSize(String objectKey);

    // 公开类对象的无签名 URL:<cdnBaseUrl|endpoint>/<bucket>/<objectKey>;key 为空返回 null。
    // 用于:bucket 允许匿名读 + 走 CDN 的资产(头像等),后端塞进 VO 直接下发 App。
    String publicUrl(String objectKey);
}
