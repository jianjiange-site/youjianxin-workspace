package com.dating.user.service.dto;

// pickDhCitiesForCaller 单条结果:DH 对该 caller 的位置覆盖值。
// stateCode 等于 caller.state_code(展示用同州);city 是同州下随机选的另一个城市(!= caller.city)。
public record DhCityOverridePick(String stateCode, String city) {}
