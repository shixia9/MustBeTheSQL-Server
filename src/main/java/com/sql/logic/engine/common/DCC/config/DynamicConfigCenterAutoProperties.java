package com.sql.logic.engine.common.DCC.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.sql.logic.engine.common.DCC.types.common.Constants;

@ConfigurationProperties(prefix = "engine.common.config", ignoreInvalidFields = true)
public class DynamicConfigCenterAutoProperties {

    private String system;

    public String getKey(String attributeName) {
        return this.system + Constants.LINE + attributeName;
    }

    public String getSystem() {
        return system;
    }

    public void setSystem(String system) {
        this.system = system;
    }
}
