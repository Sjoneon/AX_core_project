package com.project.backend.mcp.client;

import static java.util.Objects.requireNonNull;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import com.project.backend.mcp.agent.AgentLimits;
import com.project.backend.mcp.audit.ToolCallRecord;
import com.project.backend.mcp.consultation.ConsultationService;
import com.project.backend.mcp.tool.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.type.TypeReference;

@Component
public class McpGateway {
    /** Errors that end the whole request. Other tool errors are returned to the model as data. */
    public static final Set<String> FATAL=Set.of("AUTH_REQUIRED","OWNERSHIP_NOT_FOUND","CONTEXT_STALE");
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(McpGateway.class);
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Pattern CODE=Pattern.compile("^([A-Z][A-Z_]{2,40}):");
    private final String url;
    private final AgentLimits limits;
    public McpGateway(@Value("${homecare.mcp.url:http://127.0.0.1:8000}") String url,AgentLimits limits){this.url=url;this.limits=limits;}
    public Session open(String bearer,ConsultationService.Snapshot snapshot){return new Session(url,bearer,snapshot,limits);}

    /** A tool error code such as OWNERSHIP_NOT_FOUND. It never carries backend payloads or addresses. */
    public static final class ToolCallException extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID=1L;
        private final String code;
        public ToolCallException(String code){super(code);this.code=code;}
        public String code(){return code;}
    }

    public static final class Session implements AutoCloseable {
        private final McpSyncClient client;
        private final ConsultationService.Snapshot snapshot;
        private final AgentLimits limits;
        private final long deadline;
        private final Map<String,McpSchema.Tool> definitions=new LinkedHashMap<>();
        private final Map<String,Integer> perTool=new HashMap<>();
        public final Map<String,ToolEnvelope> results=new LinkedHashMap<>();
        public final List<ToolCallRecord> calls=new ArrayList<>();
        Session(String url,String bearer,ConsultationService.Snapshot snapshot,AgentLimits limits){
            if(bearer==null||!bearer.startsWith("Bearer "))throw new IllegalArgumentException("로그인 인증이 필요합니다.");
            this.snapshot=snapshot;this.limits=limits;this.deadline=System.nanoTime()+limits.totalTimeout().toNanos();
            var transport=HttpClientStreamableHttpTransport.builder(url).endpoint("/mcp")
                    .requestBuilder(HttpRequest.newBuilder().header("Authorization",bearer)
                            .header("X-HomeCare-Context-Version",Long.toString(snapshot.version())))
                    .connectTimeout(Duration.ofSeconds(3)).openConnectionOnStartup(false).build();
            client=McpClient.sync(transport).requestTimeout(limits.toolTimeout()).initializationTimeout(Duration.ofSeconds(5)).build();
            try{client.initialize();for(var tool:client.listTools().tools())if(ToolBackend.NAMES.contains(tool.name()))definitions.put(tool.name(),tool);
                if(!definitions.keySet().containsAll(ToolBackend.NAMES))throw new IllegalStateException("MCP 도구 "+ToolBackend.NAMES.size()+"개가 준비되지 않았습니다.");
            }catch(RuntimeException error){client.close();throw error;}
        }
        public List<ToolCallback> callbacks(){return definitions.values().stream()
                .filter(t->!List.of("SUSPECT").contains(snapshot.state().riskStatus())||List.of("lookup_model","get_product_context","search_knowledge","prepare_service_handoff").contains(t.name()))
                .filter(t->!"CONFIRMED".equals(snapshot.state().riskStatus())||!t.name().equals("get_diagnostic_plan"))
                .map(this::callback).toList();}
        /** Final answer generation uses the remainder of the existing request's tool/agent budget. */
        public Duration remaining(){return Duration.ofNanos(Math.max(0,deadline-System.nanoTime()));}
        private ToolCallback callback(McpSchema.Tool tool){
            Map<String,Object> schema=JSON.convertValue(tool.inputSchema(),new TypeReference<>(){});
            var props=(Map<?,?>)schema.get("properties");
            Set<String> hidden=Set.of("conversation_id","request","region_override");
            hidden.forEach(props::remove);
            // All referenced definitions belong to the removed user-only inputs.
            if(!JSON.writeValueAsString(props).contains("\"$ref\""))schema.remove("$defs");
            if(schema.get("required") instanceof List<?> required)schema.put("required",required.stream().filter(x->!hidden.contains(requireNonNull(x).toString())).toList());
            var definition=ToolDefinition.builder().name(requireNonNull(tool.name())).description(requireNonNull(tool.description())).inputSchema(requireNonNull(JSON.writeValueAsString(schema))).build();
            return new ToolCallback(){
                public @NonNull ToolDefinition getToolDefinition(){return definition;}
                public @NonNull ToolMetadata getToolMetadata(){return ToolMetadata.builder().returnDirect(false).build();}
                public @NonNull String call(@NonNull String input){
                    var args=JSON.readValue(input,new TypeReference<Map<String,Object>>(){});
                    try{
                        if(!props.keySet().containsAll(args.keySet())){
                            calls.add(ToolCallRecord.failure(tool.name(),args,"MODEL","INVALID_ARGUMENT",0));
                            throw new ToolCallException("INVALID_ARGUMENT");
                        }
                        return requireNonNull(JSON.writeValueAsString(invoke(tool.name(),args,"MODEL")));
                    }catch(ToolCallException error){
                        if(FATAL.contains(error.code()))throw error;
                        // The model sees only the code; server-side arguments are validated again on every call.
                        return "{\"error\":\""+error.code()+"\"}";
                    }
                }
            };
        }
        /** A call chosen by server policy rather than the model; it counts toward the same limits. */
        public boolean canInvoke(String name){return definitions.containsKey(name)&&calls.size()<limits.maxToolCalls()&&perTool.getOrDefault(name,0)<limits.maxCallsPerTool()&&System.nanoTime()<deadline;}
        public ToolEnvelope invoke(String name,Map<String,Object> args){return invoke(name,args,"POLICY_FALLBACK");}
        public ToolEnvelope invoke(String name,Map<String,Object> args,String selection){
            if(!definitions.containsKey(name))throw new ToolCallException("TOOL_NOT_ALLOWED");
            if(calls.size()>=limits.maxToolCalls()||perTool.getOrDefault(name,0)>=limits.maxCallsPerTool()){
                calls.add(ToolCallRecord.failure(name,args,selection,"TOOL_LIMIT_REACHED",0));throw new ToolCallException("TOOL_LIMIT_REACHED");
            }
            if(System.nanoTime()>deadline){calls.add(ToolCallRecord.failure(name,args,selection,"TOOL_TIMEOUT",0));throw new ToolCallException("TOOL_TIMEOUT");}
            perTool.merge(name,1,(left,right)->requireNonNull(left)+requireNonNull(right));
            var bounded=new LinkedHashMap<>(args);bounded.put("conversation_id",snapshot.id());
            long started=System.nanoTime();
            try{
                var response=client.callTool(new McpSchema.CallToolRequest(name,bounded,null));
                if(Boolean.TRUE.equals(response.isError()))throw new ToolCallException(errorCode(response));
                Object structured=response.structuredContent();
                if(structured instanceof Map<?,?> map&&map.containsKey("result")&&map.size()==1)structured=map.get("result");
                @Nullable ToolEnvelope result=structured==null?null:JSON.convertValue(structured,ToolEnvelope.class);
                if(result==null||result.meta()==null||!(result.meta().get("context_version") instanceof Number version)||version.longValue()!=snapshot.version())throw new ToolCallException("CONTEXT_STALE");
                long elapsed=millis(started);
                calls.add(ToolCallRecord.success(name,args,selection,result,elapsed));
                LOG.info("homecare_tool_call trace_id={} tool={} status={} elapsed_ms={}",result.meta().get("trace_id"),name,result.status(),elapsed);
                results.put(name,result);return result;
            }catch(ToolCallException error){
                fail(name,args,selection,error.code(),started);throw error;
            }catch(RuntimeException error){
                // Transport errors and timeouts: only the code is kept, never the exception message or address.
                fail(name,args,selection,"TOOL_UNAVAILABLE",started);throw new ToolCallException("TOOL_UNAVAILABLE");
            }
        }
        private void fail(String name,Map<String,Object> args,String selection,String code,long started){
            long elapsed=millis(started);calls.add(ToolCallRecord.failure(name,args,selection,code,elapsed));
            LOG.info("homecare_tool_call trace_id=none tool={} status=ERROR error_code={} elapsed_ms={}",name,code,elapsed);
        }
        private static String errorCode(McpSchema.CallToolResult response){
            for(var content:response.content())if(content instanceof McpSchema.TextContent text){
                var matcher=CODE.matcher(text.text().strip());
                if(matcher.find())return matcher.group(1);
                if(text.text().toLowerCase(Locale.ROOT).contains("validation error"))return "INVALID_ARGUMENT";
            }
            return "TOOL_ERROR";
        }
        private static long millis(long started){return Duration.ofNanos(System.nanoTime()-started).toMillis();}
        public void close(){client.close();}
    }
}
