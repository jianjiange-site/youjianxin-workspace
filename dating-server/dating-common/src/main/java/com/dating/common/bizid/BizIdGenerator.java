package com.dating.common.bizid;

public interface BizIdGenerator {

    long next(String tableName);
}
