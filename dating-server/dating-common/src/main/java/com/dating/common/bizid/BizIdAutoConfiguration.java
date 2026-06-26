package com.dating.common.bizid;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(BizIdProperties.class)
@ConditionalOnClass(BaseMapper.class)
@MapperScan(basePackages = "com.dating.common.bizid", annotationClass = Mapper.class)
public class BizIdAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public BizIdGenerator bizIdGenerator(BizIdSeqMapper mapper, BizIdProperties properties) {
        int env = properties.getEnvPrefix();
        if (env != 1 && env != 2) {
            throw new IllegalStateException(
                    "dating.bizid.env-prefix must be 1 (test) or 2 (prod), actual=" + env);
        }
        return new BizIdGeneratorImpl(mapper, properties);
    }
}
