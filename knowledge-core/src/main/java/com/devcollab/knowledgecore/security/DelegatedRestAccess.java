package com.devcollab.knowledgecore.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DelegatedRestAccess {
    String[] tools();
    String workspace() default "workspaceId";
    String repository() default "";
}
