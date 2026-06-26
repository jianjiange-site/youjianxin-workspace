package com.dating.common.bizid;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BizIdSeqMapper {

    @Select("""
            INSERT INTO biz_id_seq(table_name, date_part, seq) VALUES (#{tableName}, #{datePart}, 1)
            ON CONFLICT (table_name, date_part) DO UPDATE
            SET seq = biz_id_seq.seq + 1
            RETURNING seq
            """)
    Long upsertAndIncrement(@Param("tableName") String tableName,
                            @Param("datePart") int datePart);
}
