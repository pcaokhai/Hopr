/*
 * Copyright 2025 the original author.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pcaokhai.urlshortenerservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link ApiKeyFilter} onto {@code /shorten} and the {@code /links} management endpoints,
 * and nothing else.
 *
 * <p>A {@link FilterRegistrationBean} with explicit URL patterns -- rather than a bare
 * {@code @Component} filter, which the servlet container would map to {@code /*} -- is what keeps
 * actuator health, info, and the Prometheus scrape endpoint unauthenticated for the platform.
 */
@Configuration
public class ApiKeySecurityConfig {

    @Bean
    public FilterRegistrationBean<ApiKeyFilter> apiKeyFilterRegistration(
            ApiKeyProperties properties, ObjectMapper objectMapper) {
        FilterRegistrationBean<ApiKeyFilter> registration = new FilterRegistrationBean<>(
                new ApiKeyFilter(properties.getHashes(), objectMapper));
        registration.addUrlPatterns("/shorten", "/links", "/links/*");
        return registration;
    }
}
