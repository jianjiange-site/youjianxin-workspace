package com.dating.common.storage;

// HEAD 对象 404 时抛;调用方一般映射为业务"对象不存在"语义(如头像 confirm 找不到上传的 key)。
public class ObjectNotFoundException extends ObjectStorageException {

    private final String objectKey;

    public ObjectNotFoundException(String objectKey, Throwable cause) {
        super("object not found: " + objectKey, cause);
        this.objectKey = objectKey;
    }

    public String getObjectKey() {
        return objectKey;
    }
}
