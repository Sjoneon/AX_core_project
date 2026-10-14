package com.project.backend.mcp.agent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.ai.model.tool.*;
@Configuration
@EnableConfigurationProperties(AgentLimits.class)
public class ToolCallingConfiguration {
    // On the limit the loop ends with an error response instead of an exception, so the reply uses the results so far.
    @Bean public ToolCallingManager boundedToolCallingManager(AgentLimits limits){
        return ToolCallingManager.builder().maxCallsPerTool(limits.maxCallsPerTool()).maxTotalToolCalls(limits.maxToolCalls())
                .onLimitExceeded(ToolCallLimitBehavior.RETURN_ERROR_RESPONSE).build();
    }
}
