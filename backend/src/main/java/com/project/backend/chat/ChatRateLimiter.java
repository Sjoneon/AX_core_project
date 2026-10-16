package com.project.backend.chat;

import java.io.IOException;
import java.time.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Per-user limit for consultation requests (POST /api/chat and legacy /stream): a token bucket of {@code burst} requests refilled at
 * {@code perMinute}, plus a daily cap in Asia/Seoul. One backend container keeps the windows in memory.
 */
@Component
public class ChatRateLimiter implements HandlerInterceptor {
    private record Window(double tokens,long refilledAt,LocalDate day,int dayCount){ }
    private final Map<String,Window> windows=new ConcurrentHashMap<>();
    private final int perMinute,burst,perDay;
    private final Clock clock;
    @Autowired
    public ChatRateLimiter(@Value("${homecare.rate-limit.chat.per-minute:5}") int perMinute,@Value("${homecare.rate-limit.chat.burst:3}") int burst,
            @Value("${homecare.rate-limit.chat.per-day:100}") int perDay){
        this(perMinute,burst,perDay,Clock.system(ZoneId.of("Asia/Seoul")));
    }
    public ChatRateLimiter(int perMinute,int burst,int perDay,Clock clock){this.perMinute=perMinute;this.burst=burst;this.perDay=perDay;this.clock=clock;}
    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,@NonNull HttpServletResponse response,@NonNull Object handler) throws IOException {
        if(request.getDispatcherType()==jakarta.servlet.DispatcherType.ASYNC||!"POST".equals(request.getMethod())||request.getUserPrincipal()==null)return true;
        long retryAfter=acquire(request.getUserPrincipal().getName());
        if(retryAfter==0)return true;
        response.setStatus(429);response.setHeader("Retry-After",Long.toString(retryAfter));
        response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"message\":\"상담 요청이 많아 잠시 후 다시 시도해 주세요.\"}");
        return false;
    }
    /** 0 when the request may pass, otherwise the seconds until it may be retried. */
    long acquire(String user){
        var retry=new long[1];
        windows.compute(user,(key,old)->{
            long now=clock.millis();LocalDate today=LocalDate.now(clock);
            double tokens=old==null?burst:Math.min(burst,old.tokens()+(now-old.refilledAt())*perMinute/60000.0);
            int count=old==null||!old.day().equals(today)?0:old.dayCount();
            if(count>=perDay){retry[0]=Duration.between(LocalDateTime.now(clock),today.plusDays(1).atStartOfDay()).toSeconds()+1;return new Window(tokens,now,today,count);}
            if(tokens<1){retry[0]=(long)Math.ceil((1-tokens)*60/perMinute);return new Window(tokens,now,today,count);}
            return new Window(tokens-1,now,today,count+1);
        });
        return retry[0];
    }
    // Windows of earlier days no longer limit anything.
    @Scheduled(cron="0 5 * * * *",zone="Asia/Seoul")
    void evict(){LocalDate today=LocalDate.now(clock);windows.values().removeIf(window->!window.day().equals(today));}
}
