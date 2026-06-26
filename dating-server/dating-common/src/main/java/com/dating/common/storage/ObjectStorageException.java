package com.dating.common.storage;

// 对象存储通用错误;调用方按业务语义包成各自 BizException。
public class ObjectStorageException extends RuntimeException {

    public ObjectStorageException(String message) {
        super(message);
    }

    public ObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
