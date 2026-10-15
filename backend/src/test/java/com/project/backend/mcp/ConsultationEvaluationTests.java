package com.project.backend.mcp;

import static com.project.backend.mcp.tool.ToolDataCatalog.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import io.github.cdimascio.dotenv.Dotenv;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.project.backend.chat.dto.ChatResponse;
import com.project.backend.conversation.*;
import com.project.backend.mcp.consultation.*;
import com.project.backend.mcp.retrieval.KnowledgeVectorIndex;
import com.project.backend.mcp.tool.*;
import com.project.backend.product.*;
import com.project.backend.security.JwtTokenProvider;
import com.project.backend.user.*;
import com.project.backend.warranty.WarrantyModels;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in paid evaluation. Normal test/mysqlTest exclude this tag and never load a real key. */
@Tag("real-agent")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "homecare.agent.enabled=true", "homecare.knowledge.vector.enabled=true",
    "spring.datasource.url=jdbc:h2:mem:real_agent_evaluation;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
    "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop", "debug=false", "logging.level.root=WARN",
    "jwt.secret=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsultationEvaluationTests {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Path ROOT=Path.of("..").toAbsolutePath().normalize();
    private static String modelName;
    private static int mcpPort;
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @LocalServerPort int backendPort;
    @Autowired AppUserRepository users;
    @Autowired UserProductService products;
    @Autowired JwtTokenProvider jwt;
    @Autowired ConsultationService consultations;
    @Autowired ConversationService history;
    @Autowired ToolDataCatalog catalog;
    @Autowired KnowledgeVectorIndex vector;
    @Autowired ChatClient.Builder chat;
    Process mcp;
    Path mcpLog;
    final Map<String,String> runtimeHashes=new TreeMap<>();

    @DynamicPropertySource static void realModel(DynamicPropertyRegistry properties)throws Exception {
        var env=Dotenv.configure().ignoreIfMissing().load();
        String key=env.get("OPENAI_API_KEY");modelName=env.get("OPENAI_MODEL");
        if(key==null||key.isBlank()||key.startsWith("your-")||modelName==null||modelName.isBlank())
            throw new IllegalStateException("Real evaluation requires the existing local OpenAI configuration");
        properties.add("spring.ai.openai.api-key",()->key);
        properties.add("spring.ai.openai.chat.model",()->modelName);
        try(var socket=new ServerSocket(0)){mcpPort=socket.getLocalPort();}
        properties.add("homecare.mcp.url",()->"http://127.0.0.1:"+mcpPort);
    }
    @BeforeAll void start()throws Exception {
        // Use the application's SDK default URL, including its /v1 path. Abort
        // before running cases when provider connectivity is not verified.
        String preflight=chat.build().prompt().user("Reply with exactly OK.").call().content();
        if(preflight==null||preflight.isBlank())throw new IllegalStateException("Real model preflight returned no content");
        String executable=System.getenv("HOMECARE_TEST_PYTHON");
        if(executable==null||executable.isBlank())throw new IllegalStateException("Set HOMECARE_TEST_PYTHON to the MCP virtualenv executable");
        mcpLog=Files.createTempFile("homecare-real-evaluation-",".log");
        var builder=new ProcessBuilder(executable,"-m","homecare_mcp").directory(ROOT.resolve("mcp_server").toFile());
        builder.environment().putAll(Map.of("HOMECARE_MCP_TRANSPORT","http","HOMECARE_MCP_PORT",Integer.toString(mcpPort),
                "HOMECARE_BACKEND_URL","http://127.0.0.1:"+backendPort,"PYTHONUTF8","1"));
        mcp=builder.redirectErrorStream(true).redirectOutput(mcpLog.toFile()).start();
        boolean ready=false;
        for(int attempt=0;attempt<150;attempt++){
            if(!mcp.isAlive())throw new IllegalStateException("Isolated evaluation MCP failed to start; inspect local temporary log");
            try(var socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",mcpPort),150);ready=true;break;}
            catch(Exception unavailable){Thread.sleep(200);}
        }
        if(!ready)throw new IllegalStateException("Isolated evaluation MCP startup timeout");
        // Reuses the normal versioned cache. Query failures still take the normal bounded lexical fallback.
        vector.prepare();
    }
    @AfterAll void stop(){if(mcp!=null)mcp.destroyForcibly();}

    @Test void frozenConsultations()throws Exception {
        for(Class<?> type:List.of(ConsultationEvaluationTests.class,com.project.backend.mcp.agent.AgentChatService.class,
                MessageInterpreter.class,DialogueRouter.class,RiskRules.class,com.project.backend.mcp.retrieval.KnowledgeRetriever.class)){
            try(var input=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")){
                runtimeHashes.put(type.getName(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())));
            }
        }
        Path specification=ROOT.resolve(Objects.toString(System.getenv("HOMECARE_EVAL_SPEC"),"docs/mcp/consultation_evaluation.json")).normalize();
        if(!specification.startsWith(ROOT.resolve("docs/mcp")))throw new IllegalArgumentException("Evaluation specification must be under docs/mcp");
        byte[] bytes=Files.readAllBytes(specification);
        Map<String,Object> spec=JSON.readValue(bytes,new TypeReference<>(){});
        String run=Objects.toString(System.getenv("HOMECARE_EVAL_RUN_ID"),"initial");
        if(!run.matches("[a-zA-Z0-9_-]{1,40}"))throw new IllegalArgumentException("Invalid evaluation run ID");
        String filter=Objects.toString(System.getenv("HOMECARE_EVAL_CASES"),"");
        Set<String> selected=filter.isBlank()?Set.of():Set.of(filter.split(","));
        var rows=new ArrayList<Map<String,Object>>();
        String commit=git("rev-parse","HEAD"),worktree=git("status","--porcelain");
        Path report=ROOT.resolve("docs/benchmarks/consultation_agent_20261015_"+run+".json");
        if(Files.exists(report))throw new IllegalStateException("Use a new HOMECARE_EVAL_RUN_ID; evaluation reports are append-only");
        for(var raw:(List<?>)spec.get("cases")){
            var test=(Map<String,Object>)raw;String id=text(test,"id");
            if(!selected.isEmpty()&&!selected.contains(id))continue;
            var turns=new ArrayList<Map<String,Object>>();var failures=new ArrayList<String>();
            long started=System.nanoTime();
            String email="evaluation-"+UUID.randomUUID()+"@example.com";
            users.save(new AppUser(email,"unused","USER"));
            var category=ProductCategory.valueOf(text(test,"category"));
            var configuredModels=strings(test,"models");
            for(String code:configuredModels){
                var warranty=new WarrantyModels.Request(category,code,LocalDate.parse(Objects.toString(test.get("purchase_date"),"2025-01-01")),null,null,null,"GENERAL",true,true);
                products.create(email,new UserProductService.Create("평가 제품 "+code,warranty,"서울","강남구"));
            }
            String authorization="Bearer "+jwt.createToken(email);ChatResponse response=null;
            try{
                for(var operation:(List<?>)test.get("turns")){
                    var step=(Map<String,Object>)operation;
                    int repeat="confirm_conditions".equals(step.get("type"))?((Number)step.get("count")).intValue():1;
                    for(int index=0;index<repeat;index++){
                        var before=response;long turnStart=System.nanoTime();
                        String type=text(step,"type");Long conversation=before==null?null:before.conversationId();
                        var body=new LinkedHashMap<String,Object>();String path;
                        if(type.equals("chat")){
                            path="/api/chat";body.putAll(map("message",step.get("input"),"conversationId",conversation,"category",category.name()));
                        }else if(type.equals("risk")){
                            path="/api/conversations/"+conversation+"/risk-answer";
                            body.putAll(map("answer",step.get("answer"),"context_version",consultations.snapshot(email,conversation).version()));
                        }else{
                            if(before==null)throw new IllegalStateException("No displayed self-care result");
                            var diagnostic=before.tools().get("get_diagnostic_plan");
                            if(diagnostic==null)throw new IllegalStateException("No displayed self-care plan");
                            boolean condition=!type.equals("step");
                            path="/api/conversations/"+conversation+(condition?"/precondition-answer":"/step-answer");
                            body.putAll(map("answer",step.get("answer"),"context_version",diagnostic.meta().get("context_version")));
                            if(condition){var question=(Map<?,?>)diagnostic.data().get("question");
                                if(question==null)throw new IllegalStateException("Expected condition is absent");
                                body.put("question_id",question.get("id"));body.put("plan_scope",diagnostic.data().get("plan_scope"));
                            }else{var steps=(List<?>)diagnostic.data().get("steps");
                                if(steps==null||steps.isEmpty())throw new IllegalStateException("Expected approved step is absent");
                                body.put("step_code",((Map<?,?>)steps.getFirst()).get("step_code"));}
                        }
                        response=post(path,body,authorization);
                        var snapshot=consultations.snapshot(email,response.conversationId());
                        var messages=history.detail(response.conversationId(),email).messages();
                        var latest=messages.getLast();
                        var calls=history.toolCalls(response.conversationId(),email).stream().filter(call->Objects.equals(call.messageId(),latest.id())).toList();
                        var checks=new ArrayList<Map<String,Object>>();
                        if(index==repeat-1)check((Map<String,Object>)step.getOrDefault("expect",Map.of()),response,snapshot,calls,email,checks);
                        var saved=latest.tools();
                        add(checks,"history_matches_reply",Objects.equals(response.reply(),latest.content()));
                        add(checks,"history_matches_tool_names",response.tools().keySet().equals(saved.keySet()));
                        add(checks,"tool_call_budget",calls.size()<=5&&calls.stream().collect(java.util.stream.Collectors.groupingBy(ConversationService.ToolCallView::toolName,java.util.stream.Collectors.counting())).values().stream().allMatch(n->n<=2));
                        for(var check:checks)if(!Boolean.TRUE.equals(check.get("passed")))failures.add("turn "+(turns.size()+1)+": "+check.get("name"));
                        var toolStates=new LinkedHashMap<String,Object>();
                        response.tools().forEach((name,result)->toolStates.put(name,map("status",result.status(),"data",result.data(),"evidence",result.evidence(),"warnings",result.warnings(),"meta",result.meta())));
                        boolean agentPath=response.tools().containsKey("answer_generation")||response.tools().containsKey("agent_fallback");
                        boolean modelSelection=agentPath&&!Set.of("COST","SERVICE","UNCLASSIFIED").contains(snapshot.state().dialogue().intent());
                        turns.add(map("operation",type,"input",step.get("input"),"answer",step.get("answer"),"elapsed_ms",(System.nanoTime()-turnStart)/1_000_000,
                                "reply",response.reply(),"state",snapshot.state(),"selected_model",consultations.selectedModel(email,snapshot),"tools",toolStates,
                                "model_tool_selection_applicable",modelSelection,
                                "calls",calls.stream().map(call->map("tool",call.toolName(),"selection_source",call.selectionSource(),"status",call.status(),"error_code",call.errorCode(),"elapsed_ms",call.elapsedMs())).toList(),"checks",checks));
                    }
                }
            }catch(Exception failed){failures.add("RUNTIME: "+failed.getClass().getSimpleName());}
            var row=map("id",id,"expected_outcome",test.get("outcome"),"outcome",failures.isEmpty()?test.get("outcome"):"FAILED",
                    "passed",failures.isEmpty(),"failures",failures,"elapsed_ms",(System.nanoTime()-started)/1_000_000,"turns",turns,"semantic_review_required",true);
            rows.add(row);
            write(report,bytes,commit,worktree,run,selected,rows);
            System.out.println("Consultation "+rows.size()+": "+id+" "+row.get("outcome")+" failures="+failures.size());
        }
        if(rows.isEmpty())throw new IllegalArgumentException("No matching evaluation cases");
        Assertions.assertTrue(rows.stream().allMatch(row->Boolean.TRUE.equals(row.get("passed"))),"Review frozen consultation evaluation failures; report is preserved");
    }
    private ChatResponse post(String path,Map<String,Object> body,String token)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+backendPort+path)).timeout(Duration.ofSeconds(120))
                .header("Authorization",token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        var response=HTTP.send(request,HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)throw new IllegalStateException("Evaluation HTTP status "+response.statusCode());
        return JSON.readValue(response.body(),ChatResponse.class);
    }
    private void check(Map<String,Object> expect,ChatResponse response,ConsultationService.Snapshot snapshot,List<ConversationService.ToolCallView> calls,String email,List<Map<String,Object>> checks){
        var state=snapshot.state();var diagnostic=response.tools().get("get_diagnostic_plan");
        var plan=diagnostic==null?Map.<String,Object>of():diagnostic.data();
        var evidence=new LinkedHashSet<String>();
        for(var result:response.tools().values())if(result.data()!=null)for(String name:List.of("documents","safety_notices","references"))
            if(result.data().get(name) instanceof List<?> docs)for(var doc:docs)if(doc instanceof Map<?,?> value)evidence.add(Objects.toString(value.get("document_id")));
        for(var entry:expect.entrySet()){
            String key=entry.getKey();Object wanted=entry.getValue();boolean passed;
            var cost=response.tools().get("estimate_repair_cost");
            var alternatives=cost==null||!(cost.data().get("alternatives") instanceof List<?> list)?List.of():list;
            passed=switch(key){
                case "symptom"->Objects.equals(wanted,state.symptomCode());
                case "intent"->Objects.equals(wanted,state.dialogue().intent());
                case "risk"->Objects.equals(wanted,state.riskStatus());
                case "timing"->Objects.equals(wanted,state.dialogue().answers().get("VIBRATION_WHEN"));
                case "closed"->Objects.equals(wanted,state.closed());
                case "selected_model"->Objects.equals(wanted,consultations.selectedModel(email,snapshot));
                case "question"->{String question=state.dialogue().pendingQuestion()==null?null:state.dialogue().pendingQuestion().id();
                    if(plan.get("question") instanceof Map<?,?> q)question=Objects.toString(q.get("id"),null);yield Objects.equals(wanted,question);}
                case "diagnostic"->diagnostic!=null&&Objects.equals(wanted,diagnostic.status());
                case "skip_reason"->Objects.equals(wanted,plan.get("skip_reason"));
                case "steps"->Objects.equals(wanted,plan.get("steps") instanceof List<?> list?list.stream().map(item->((Map<?,?>)item).get("step_code")).toList():List.of());
                case "done_steps"->Objects.equals(wanted,state.doneSteps());
                case "no_steps"->!(plan.get("steps") instanceof List<?> list)||list.isEmpty();
                case "no_cost"->alternatives.isEmpty();
                case "no_calls"->calls.isEmpty();
                case "tools"->response.tools().keySet().containsAll((List<?>)wanted);
                case "evidence"->evidence.containsAll((List<?>)wanted);
                case "exclude_evidence"->Collections.disjoint(evidence,(List<?>)wanted);
                case "reply_contains"->((List<?>)wanted).stream().allMatch(value->response.reply().contains(value.toString()));
                case "reply_excludes"->((List<?>)wanted).stream().noneMatch(value->response.reply().contains(value.toString()));
                case "delivery"->{var handoff=response.tools().get("prepare_service_handoff");yield handoff!=null&&Objects.equals(wanted,handoff.data().get("delivery_status"));}
                case "warranty"->response.warranty()!=null&&Objects.equals(wanted,response.warranty().productWarranty().periodStatus());
                case "lookup"->{var lookup=response.tools().get("lookup_model");yield lookup!=null&&Objects.equals(wanted,lookup.data().get("match_status"));}
                case "cost_min","cost_median","cost_max"->{String field=key.substring(5);
                    yield !alternatives.isEmpty()&&((Map<?,?>)((Map<?,?>)alternatives.getFirst()).get("expected_range")).get(field) instanceof Number number&&number.longValue()==((Number)wanted).longValue();}
                default->throw new IllegalArgumentException("Unknown frozen expectation: "+key);
            };
            add(checks,key,passed);
        }
    }
    private static void add(List<Map<String,Object>> checks,String name,boolean passed){checks.add(map("name",name,"passed",passed));}
    private void write(Path report,byte[] spec,String commit,String worktree,String run,Set<String> selected,List<Map<String,Object>> rows)throws Exception {
        long passed=rows.stream().filter(row->Boolean.TRUE.equals(row.get("passed"))).count();
        var turns=rows.stream().flatMap(row->((List<Map<String,Object>>)row.get("turns")).stream()).toList();
        var timings=turns.stream().map(turn->((Number)turn.get("elapsed_ms")).longValue()).sorted().toList();
        var calls=turns.stream().flatMap(turn->((List<Map<String,Object>>)turn.get("calls")).stream()).toList();
        var selection=calls.stream().collect(java.util.stream.Collectors.groupingBy(call->Objects.toString(call.get("selection_source")),TreeMap::new,java.util.stream.Collectors.counting()));
        var summary=map("evaluated_date","2026-10-15","run_id",run,"real_model",true,"model",modelName,"data_version",catalog.version,
                "git_commit",commit,"worktree_dirty",!worktree.isBlank(),"runtime_class_sha256",runtimeHashes,"model_preflight_verified",true,"spec_sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(spec)),
                "filtered_cases",selected,"isolated_h2",true,"http_jwt_mcp",true,"physical_operations",false,"index_metrics",vector.metrics(),
                "total",rows.size(),"passed",passed,"failed",rows.size()-passed,"turn_count",turns.size(),"selection_sources",selection,
                "latency_ms",map("p50",percentile(timings,.5),"p95",percentile(timings,.95),"max",timings.isEmpty()?0:timings.getLast()),"cases",rows);
        Files.createDirectories(report.getParent());Files.writeString(report,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary)+"\n");
    }
    private static long percentile(List<Long> sorted,double percentile){return sorted.isEmpty()?0:sorted.get(Math.max(0,(int)Math.ceil(sorted.size()*percentile)-1));}
    private static String git(String... args)throws Exception{
        var command=new ArrayList<String>();command.add("git");command.addAll(List.of(args));
        var process=new ProcessBuilder(command).directory(ROOT.toFile()).redirectErrorStream(true).start();
        String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).strip();process.waitFor();return output;
    }
}
