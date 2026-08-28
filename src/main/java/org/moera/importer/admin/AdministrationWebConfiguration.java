package org.moera.importer.admin;

import jakarta.inject.Inject;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class AdministrationWebConfiguration implements WebMvcConfigurer {

    @Inject
    private LocalAdministrationInterceptor localAdministrationInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localAdministrationInterceptor).addPathPatterns("/api/**");
    }

}
