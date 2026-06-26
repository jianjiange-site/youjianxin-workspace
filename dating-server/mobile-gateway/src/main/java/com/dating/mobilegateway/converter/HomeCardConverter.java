package com.dating.mobilegateway.converter;

import com.dating.mobilegateway.vo.HomeCardVO;
import com.dating.mobilegateway.vo.UserProfileVO;

// HomeCard 装配 —— 留作扩展点:后续 relation / im 加入时,在此把多源 VO 合并。
// 当前只有 selfUserId + target,直接 new 即可。
public final class HomeCardConverter {

    private HomeCardConverter() {}

    public static HomeCardVO assemble(Long selfUserId, UserProfileVO target) {
        return new HomeCardVO(selfUserId, target);
    }
}
