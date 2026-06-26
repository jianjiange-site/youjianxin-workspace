package com.dating.common.bizid;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("dating.bizid")
public class BizIdProperties {

    private int envPrefix;

    public int getEnvPrefix() {
        return envPrefix;
    }

    public void setEnvPrefix(int envPrefix) {
        this.envPrefix = envPrefix;
    }
}
