package com.slack.lab.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/** {@code app.role}이 나열한 역할 중 하나일 때만 빈을 등록한다. 값이 없으면 {@link AppRole#ALL}로 본다. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
@Conditional(OnRoleCondition.class)
public @interface ConditionalOnRole {

    AppRole[] value();
}
