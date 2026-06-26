package com.dating.mobilegateway.service;

import com.dating.mobilegateway.vo.HomeCardVO;

// BFF 聚合:把"看用户卡片"这件事拆成多个下游 gRPC 调用,并发拿,合成一个 HomeCardVO。
//   - 当前下游:UserProfileService.GetProfile
//   - 留扩展点:relation / im / push 等;Slice G6 只接 profile
public interface HomeService {

    HomeCardVO getCard(Long selfUserId, Long targetUserId);
}
