package com.project.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.project.backend.chat.ChatRateLimiter;
import org.jspecify.annotations.NonNull;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final @NonNull ChatRateLimiter chatRateLimiter;
    private final boolean agentEnabled;
    public WebConfig(@NonNull ChatRateLimiter chatRateLimiter,@Value("${homecare.agent.enabled:true}") boolean agentEnabled){this.chatRateLimiter=chatRateLimiter;this.agentEnabled=agentEnabled;}
    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry){
        var registration=registry.addInterceptor(chatRateLimiter).addPathPatterns("/api/chat");
        if(!agentEnabled)registration.addPathPatterns("/api/chat/stream");
    }
}
