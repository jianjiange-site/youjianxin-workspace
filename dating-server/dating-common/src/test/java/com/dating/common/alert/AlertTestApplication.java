package com.dating.common.alert;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;

// 只装配 AlertAutoConfiguration,避开 DataSource/MyBatis/Storage 等其他 AutoConfig,
// 让 IT 不依赖外部 PG / S3。
@SpringBootConfiguration
@ImportAutoConfiguration(classes = AlertAutoConfiguration.class)
public class AlertTestApplication {
}
