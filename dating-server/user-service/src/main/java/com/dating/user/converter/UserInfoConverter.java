package com.dating.user.converter;

import com.dating.user.entity.UserInfo;
import com.dating.user.vo.UserProfileVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

// entity (DB 命名) ↔ VO (UI 命名 occupation/location);proto 转换在 ProtoMapper。
// avatar / interests 由 service 层组装(custom_avatar JSONB 单独解析 → AvatarVO key),转换层 ignore。
// userId 同名默认映射:entity.userId (BizIdGenerator 业务主键) → VO.userId
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface UserInfoConverter {

    @Mapping(source = "profession", target = "occupation")
    @Mapping(source = "preferredLocation", target = "location")
    @Mapping(target = "avatar", ignore = true)
    @Mapping(target = "interests", ignore = true)
    UserProfileVO toVO(UserInfo entity);

    List<UserProfileVO> toVOList(List<UserInfo> entities);
}
