package com.dating.common.bizid;

public class BizIdOverflowException extends RuntimeException {

    private final String tableName;
    private final int datePart;
    private final long seq;

    public BizIdOverflowException(String tableName, int datePart, long seq) {
        super("biz id overflow: table=%s, date=%d, seq=%d (exceeds BIGINT-safe range ~1e12/day)"
                .formatted(tableName, datePart, seq));
        this.tableName = tableName;
        this.datePart = datePart;
        this.seq = seq;
    }

    public String getTableName() {
        return tableName;
    }

    public int getDatePart() {
        return datePart;
    }

    public long getSeq() {
        return seq;
    }
}
