package com.devcollab.knowledgecore.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class DelegatedRestAccessConfig implements WebMvcConfigurer {

    private final DelegatedRestAccessInterceptor interceptor;

    public DelegatedRestAccessConfig(DelegatedRestAccessInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .addPathPatterns("/api/v1/**");
    }
}
