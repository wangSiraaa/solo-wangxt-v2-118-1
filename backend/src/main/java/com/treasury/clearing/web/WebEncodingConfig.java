package com.treasury.clearing.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.CharacterEncodingFilter;

/**
 * Deferral reasons and batch notes are entered in Chinese; make request/response
 * bodies UTF-8 unconditionally instead of relying on each request's Content-Type.
 * Registered as a bean so it also applies in MockMvc full-stack tests.
 */
@Configuration
public class WebEncodingConfig {

    @Bean
    public FilterRegistrationBean<CharacterEncodingFilter> utf8EncodingFilterRegistration() {
        CharacterEncodingFilter filter = new CharacterEncodingFilter("UTF-8");
        filter.setForceEncoding(true);
        FilterRegistrationBean<CharacterEncodingFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(0);
        return registration;
    }
}
