package com.dating.mobilegateway.converter;

import com.dating.mobilegateway.vo.AvatarVO;
import com.dating.mobilegateway.vo.UserInterestVO;
import com.dating.mobilegateway.vo.UserProfileVO;
import com.dating.youjianxin.proto.user.Avatar;
import com.dating.youjianxin.proto.user.UserInterest;
import com.dating.youjianxin.proto.user.UserProfile;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

// Proto → VO 映射(读 proto getter)。
//   - proto enum 字段统一取 *Value (int) 入 VO Integer
//   - proto repeated 字段 MapStruct 按 getter 名 (getInterestsList) 当 property 名 (interestsList) 处理
//   - Avatar / UserInterest 字段桥接:user-proto 0.2.x stub 字段名仍是 *_url,但承载的值是 object_key,
//     这里显式 source→target 把 *_url 字段读出来塞到 *Key 字段;App 侧自拼 ${cdnBaseUrl}/${bucket}/${key}。
//
// 反向(VO → proto)由 ProtoReqBuilder 手写,因为 MapStruct 不能 target protobuf Builder。
@Mapper(componentModel = "spring")
public interface UserProfileConverter {

    @Mapping(target = "gender", source = "genderValue")
    @Mapping(target = "regulationStatus", source = "regulationStatusValue")
    @Mapping(target = "interests", source = "interestsList")
    UserProfileVO toVO(UserProfile p);

    List<UserProfileVO> toVOs(List<UserProfile> ps);

    @Mapping(target = "originalKey", source = "originalUrl")
    @Mapping(target = "minKey", source = "minUrl")
    @Mapping(target = "midKey", source = "midUrl")
    AvatarVO toAvatarVO(Avatar a);

    @Mapping(target = "picKey", source = "picUrl")
    UserInterestVO toInterestVO(UserInterest i);
}
