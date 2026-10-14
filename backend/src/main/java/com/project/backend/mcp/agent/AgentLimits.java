package com.project.backend.mcp.agent;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The single source of tool-call limits for one customer question. Spring AI's tool calling manager and the MCP
 * gateway (which also counts server policy calls) both read these values.
 */
@ConfigurationProperties("homecare.agent")
public record AgentLimits(@DefaultValue("5") int maxToolCalls,@DefaultValue("2") int maxCallsPerTool,
        @DefaultValue("5s") Duration toolTimeout,@DefaultValue("20s") Duration totalTimeout) {
}
