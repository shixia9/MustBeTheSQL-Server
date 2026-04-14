package com.sql.logic.engine.common.DCC.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;

import com.sql.logic.engine.common.DCC.domain.service.IDynamicConfigCenterService;


@Configuration
public class DynamicConfigCenterAutoConfig implements BeanPostProcessor {

    private final IDynamicConfigCenterService dynamicConfigCenterService;

    public DynamicConfigCenterAutoConfig(IDynamicConfigCenterService dynamicConfigCenterService) {
        this.dynamicConfigCenterService = dynamicConfigCenterService;
    }

    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        return dynamicConfigCenterService.proxyObject(bean);
    }
    
}
