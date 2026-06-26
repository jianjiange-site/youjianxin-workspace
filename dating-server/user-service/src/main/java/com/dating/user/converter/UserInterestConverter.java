package com.dating.user.converter;

import com.dating.user.entity.UserInterest;
import com.dating.user.vo.UserInterestVO;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.List;

// entity ↔ VO (proto 不在此映射,由 ProtoMapper 手写 builder)
// pic_key 直接透传到 VO,App 侧自拼 ${cdnBaseUrl}/${bucket}/${key}(头像走 CDN public,服务端不签 URL)。
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface UserInterestConverter {

    UserInterestVO toVO(UserInterest entity);

    List<UserInterestVO> toVOList(List<UserInterest> entities);
}
