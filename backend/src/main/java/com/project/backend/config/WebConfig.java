package com.project.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.project.backend.chat.ChatRateLimiter;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final ChatRateLimiter chatRateLimiter;
    public WebConfig(ChatRateLimiter chatRateLimiter){this.chatRateLimiter=chatRateLimiter;}
    @Override
    public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(chatRateLimiter).addPathPatterns("/api/chat");}
}
