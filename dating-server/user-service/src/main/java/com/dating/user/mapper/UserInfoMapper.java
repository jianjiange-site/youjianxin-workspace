package com.dating.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.user.entity.UserInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserInfoMapper extends BaseMapper<UserInfo> {

    // PG `custom_avatar` 是 jsonb 列;MyBatis 默认对 String 走 setString(VARCHAR),
    // PG 不允许 text→jsonb 隐式转换,所以 UpdateWrapper.set("custom_avatar", json) 会
    // 报 "column custom_avatar is of type jsonb but expression is of type character varying"。
    // 这里显式 ::jsonb cast 解决。updated_at 走 PG NOW() 避免 OffsetDateTime 绕一圈。
    // userId 是业务主键 user_info.user_id(BizIdGenerator 生成),不是物理 id。
    @Update("UPDATE user_info SET custom_avatar = #{json}::jsonb, updated_at = NOW() "
            + "WHERE user_id = #{userId} AND deleted = false")
    int updateCustomAvatarJson(@Param("userId") Long userId, @Param("json") String json);
}
