package com.sql.logic.engine.common.DCC.domain.service;

import com.sql.logic.engine.common.DCC.domain.model.valobj.AttributeVO;

/**
 * Dynamic Config Center service interface
 */
public interface IDynamicConfigCenterService {

    Object proxyObject(Object bean);

    void adjustAttributeValue(AttributeVO attributeVO);

}
