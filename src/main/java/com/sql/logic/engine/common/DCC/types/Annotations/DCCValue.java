package com.sql.logic.engine.common.DCC.types.Annotations;

import java.lang.annotation.*;

/**
 * DCC Annotation
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
@Documented
public @interface DCCValue {

    String value() default "";
    
}
