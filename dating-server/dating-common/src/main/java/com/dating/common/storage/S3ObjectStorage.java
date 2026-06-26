package com.dating.common.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.InputStream;
import java.time.Duration;

// ObjectStorage 唯一实现;S3Client / S3Presigner 由 ObjectStorageAutoConfiguration 注入,本类无状态可重入。
public class S3ObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final ObjectStorageProperties props;

    public S3ObjectStorage(S3Client s3, S3Presigner presigner, ObjectStorageProperties props) {
        this.s3 = s3;
        this.presigner = presigner;
        this.props = props;
    }

    // ---- presigned ----

    @Override
    public String presignedPutUrl(String objectKey) {
        return presignedPutUrl(objectKey, Duration.ofSeconds(props.getPresignedPutTtlSeconds()));
    }

    @Override
    public String presignedPutUrl(String objectKey, Duration ttl) {
        requireKey(objectKey);
        try {
            PutObjectRequest put = PutObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(objectKey)
                    .build();
            PutObjectPresignRequest req = PutObjectPresignRequest.builder()
                    .signatureDuration(ttl)
                    .putObjectRequest(put)
                    .build();
            return presigner.presignPutObject(req).url().toString();
        } catch (Exception e) {
            throw new ObjectStorageException("presigned PUT failed: " + objectKey, e);
        }
    }

    @Override
    public String presignedGetUrl(String objectKey) {
        return presignedGetUrl(objectKey, Duration.ofSeconds(props.getPresignedGetTtlSeconds()));
    }

    @Override
    public String presignedGetUrl(String objectKey, Duration ttl) {
        if (isBlank(objectKey)) {
            return null;
        }
        try {
            GetObjectRequest get = GetObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(objectKey)
                    .build();
            GetObjectPresignRequest req = GetObjectPresignRequest.builder()
                    .signatureDuration(ttl)
                    .getObjectRequest(get)
                    .build();
            return presigner.presignGetObject(req).url().toString();
        } catch (Exception e) {
            throw new ObjectStorageException("presigned GET failed: " + objectKey, e);
        }
    }

    // ---- 服务端直接读写 ----

    @Override
    public void putObject(String objectKey, byte[] content, String contentType) {
        requireKey(objectKey);
        try {
            s3.putObject(buildPut(objectKey, contentType), RequestBody.fromBytes(content));
        } catch (Exception e) {
            throw new ObjectStorageException("putObject failed: " + objectKey, e);
        }
    }

    @Override
    public void putObject(String objectKey, InputStream content, long contentLength, String contentType) {
        requireKey(objectKey);
        try {
            s3.putObject(buildPut(objectKey, contentType), RequestBody.fromInputStream(content, contentLength));
        } catch (Exception e) {
            throw new ObjectStorageException("putObject(stream) failed: " + objectKey, e);
        }
    }

    @Override
    public byte[] getObject(String objectKey) {
        requireKey(objectKey);
        try {
            return s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(objectKey)
                    .build()).asByteArray();
        } catch (NoSuchKeyException nf) {
            throw new ObjectNotFoundException(objectKey, nf);
        } catch (AwsServiceException e) {
            if (isNotFound(e)) {
                throw new ObjectNotFoundException(objectKey, e);
            }
            throw new ObjectStorageException("getObject failed: " + objectKey, e);
        } catch (Exception e) {
            throw new ObjectStorageException("getObject failed: " + objectKey, e);
        }
    }

    @Override
    public void deleteObject(String objectKey) {
        requireKey(objectKey);
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(objectKey)
                    .build());
        } catch (NoSuchKeyException nf) {
            // 幂等:已不存在视作删除成功
            log.debug("deleteObject no-op, key absent: {}", objectKey);
        } catch (AwsServiceException e) {
            if (isNotFound(e)) {
                log.debug("deleteObject no-op, key absent (404): {}", objectKey);
                return;
            }
            throw new ObjectStorageException("deleteObject failed: " + objectKey, e);
        } catch (Exception e) {
            throw new ObjectStorageException("deleteObject failed: " + objectKey, e);
        }
    }

    @Override
    public boolean doesObjectExist(String objectKey) {
        if (isBlank(objectKey)) {
            return false;
        }
        try {
            s3.headObject(HeadObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(objectKey)
                    .build());
            return true;
        } catch (NoSuchKeyException nf) {
            return false;
        } catch (AwsServiceException e) {
            if (isNotFound(e)) {
                return false;
            }
            throw new ObjectStorageException("headObject failed: " + objectKey, e);
        } catch (Exception e) {
            throw new ObjectStorageException("headObject failed: " + objectKey, e);
        }
    }

    // ---- 元数据 / URL ----

    @Override
    public long headObjectSize(String objectKey) {
        requireKey(objectKey);
        try {
            HeadObjectResponse resp = s3.headObject(HeadObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(objectKey)
                    .build());
            return resp.contentLength();
        } catch (NoSuchKeyException nf) {
            throw new ObjectNotFoundException(objectKey, nf);
        } catch (AwsServiceException e) {
            // 部分 S3 兼容实现把对象不存在翻译成 404 而非 NoSuchKey,兜底
            if (isNotFound(e)) {
                throw new ObjectNotFoundException(objectKey, e);
            }
            throw new ObjectStorageException("headObject failed: " + objectKey, e);
        } catch (Exception e) {
            throw new ObjectStorageException("headObject failed: " + objectKey, e);
        }
    }

    @Override
    public String publicUrl(String objectKey) {
        if (isBlank(objectKey)) {
            return null;
        }
        String base = isBlank(props.getCdnBaseUrl()) ? props.getEndpoint() : props.getCdnBaseUrl();
        return base + "/" + props.getBucket() + "/" + objectKey;
    }

    // ---- helpers ----

    private PutObjectRequest buildPut(String objectKey, String contentType) {
        PutObjectRequest.Builder b = PutObjectRequest.builder()
                .bucket(props.getBucket())
                .key(objectKey);
        if (!isBlank(contentType)) {
            b.contentType(contentType);
        }
        return b.build();
    }

    private static boolean isNotFound(AwsServiceException e) {
        return e.statusCode() == 404;
    }

    private static void requireKey(String objectKey) {
        if (isBlank(objectKey)) {
            throw new IllegalArgumentException("objectKey required");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
