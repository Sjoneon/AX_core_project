package com.project.backend.chat;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class ChatRateLimiterTests {
    static final class TestClock extends Clock {
        Instant now=Instant.parse("2026-10-13T03:00:00Z");
        public ZoneId getZone(){return ZoneId.of("Asia/Seoul");}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    final TestClock clock=new TestClock();

    @Test void threeInARowThenOneEveryTwelveSeconds(){
        var limiter=new ChatRateLimiter(5,3,100,clock);
        for(int i=0;i<3;i++)assertThat(limiter.acquire("a@example.com")).isZero();
        assertThat(limiter.acquire("a@example.com")).isEqualTo(12);
        assertThat(limiter.acquire("b@example.com")).as("users are limited separately").isZero();
        clock.now=clock.now.plusSeconds(12);
        assertThat(limiter.acquire("a@example.com")).isZero();
    }
    @Test void dailyCapResetsAtMidnightInSeoul(){
        var limiter=new ChatRateLimiter(600,10,4,clock);
        for(int i=0;i<4;i++)assertThat(limiter.acquire("a@example.com")).isZero();
        // 03:00Z is 12:00 in Seoul, so the next day starts 12 hours later.
        assertThat(limiter.acquire("a@example.com")).isEqualTo(Duration.ofHours(12).toSeconds()+1);
        clock.now=clock.now.plus(Duration.ofHours(12));
        assertThat(limiter.acquire("a@example.com")).isZero();
    }
    @Test void onlyAuthenticatedChatPostsAreLimitedAndRejectionsUse429() throws Exception {
        var limiter=new ChatRateLimiter(5,1,100,clock);
        var request=new MockHttpServletRequest("POST","/api/chat");request.setUserPrincipal(()->"a@example.com");
        assertThat(limiter.preHandle(request,new MockHttpServletResponse(),new Object())).isTrue();
        var rejected=new MockHttpServletResponse();
        assertThat(limiter.preHandle(request,rejected,new Object())).isFalse();
        assertThat(rejected.getStatus()).isEqualTo(429);assertThat(rejected.getHeader("Retry-After")).isEqualTo("12");
        assertThat(rejected.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("잠시 후 다시 시도");
        var read=new MockHttpServletRequest("GET","/api/chat");read.setUserPrincipal(()->"a@example.com");
        assertThat(limiter.preHandle(read,new MockHttpServletResponse(),new Object())).isTrue();
        assertThat(limiter.preHandle(new MockHttpServletRequest("POST","/api/chat"),new MockHttpServletResponse(),new Object())).isTrue();
    }
}
