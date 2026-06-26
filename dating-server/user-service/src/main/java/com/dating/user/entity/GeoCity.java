package com.dating.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

// 美国城市字典(SimpleMaps US Cities Basic ~28k 行);user_info.city_id 引用此表
// reference table:无软删、无 updated_at;字典刷新通过 source_id 做 upsert
@Data
@TableName("geo_city")
public class GeoCity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String city;
    private String stateCode;
    private String stateName;
    private String countryCode;
    private Double lat;
    private Double lng;
    private Long population;
    private String sourceId;

    private OffsetDateTime createdAt;
}
