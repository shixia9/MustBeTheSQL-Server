package com.sql.logic.engine.common.DCC.listener;

import org.redisson.api.listener.MessageListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sql.logic.engine.common.DCC.domain.model.valobj.AttributeVO;
import com.sql.logic.engine.common.DCC.domain.service.IDynamicConfigCenterService;


/**
 * dcc attribute adjust listener
 */
public class DynamicConfigCenterAdjustListener implements MessageListener<AttributeVO>{

    private final Logger logger = LoggerFactory.getLogger(DynamicConfigCenterAdjustListener.class);

    private final IDynamicConfigCenterService dynamicConfigCenterService;

    public DynamicConfigCenterAdjustListener(IDynamicConfigCenterService dynamicConfigCenterService) {
        this.dynamicConfigCenterService = dynamicConfigCenterService;
    }


    @Override
    public void onMessage(CharSequence charSequence, AttributeVO attributeVO) {
        try {
            logger.info("[DCC] config attribute:{} value:{}", attributeVO.getAttribute(), attributeVO.getValue());
            dynamicConfigCenterService.adjustAttributeValue(attributeVO);
        } catch (Exception e) {
            logger.error("[DCC] config attribute:{} value:{}", attributeVO.getAttribute(), attributeVO.getValue(), e);
        }
    }
    
}
