package com.project.backend.mcp;

import static com.project.backend.mcp.tool.ToolDataCatalog.map;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.project.backend.user.AppUser;

/**
 * Audit probe, not a regression test. Replays the refrigerator self-check over HTTP the way each version's screen does
 * and records every request, rate-limit response and scripted-model call. It reuses the McpIntegrationTests
 * environment (isolated H2, scripted OpenAI endpoint, real FastMCP) and is copied into an exported tree by run_audit.py.
 */
class SelfCheckRequestProbe extends McpIntegrationTests {
    record Reply(int status,Map<String,Object> body){ }
    record Flow(String email,long conversation,Map<String,Object> shown){ }
    final HttpClient http=HttpClient.newHttpClient();
    final List<Map<String,Object>> requests=new ArrayList<>();
    String scenario="",phase="";
    long waitedSeconds;

    /** Default limits (burst 3, 5 per minute). A 429 is retried after Retry-After, the earliest a customer could resend. */
    @Test void probeRateLimit() throws Exception {
        var runs=new ArrayList<Map<String,Object>>();
        runs.add(scenario("NOT_RESOLVED",0,true));
        runs.add(scenario("SOLVED",3,true));
        write("self_check_rate_limit",map("runs",runs));
    }

    Map<String,Object> scenario(String outcome,int solvedAt,boolean honorRateLimit) throws Exception {
        scenario=outcome+(solvedAt>0?"@"+solvedAt:"");waitedSeconds=0;
        var flow=start(honorRateLimit);
        int from=requests.size();long started=System.nanoTime();
        var last=walk(flow,outcome,solvedAt,honorRateLimit);
        long wallMicros=(System.nanoTime()-started)/1000;
        var steps=new ArrayList<>(requests.subList(from,requests.size()));
        phase="verify";
        var saved=send("GET","/api/conversations/"+flow.conversation(),flow.email(),null,false);
        var state=consultations.snapshot(flow.email(),flow.conversation()).state();
        var counts=new TreeMap<String,Integer>();
        int limited=0,throttled=0,interpreter=0,answer=0,risk=0,agent=0;long serverMicros=0;
        for(var request:steps){
            counts.merge(request.get("method")+" "+request.get("path"),1,Integer::sum);
            if("/api/chat".equals(request.get("path")))limited++;
            if(Integer.valueOf(429).equals(request.get("status")))throttled++;
            interpreter+=(Integer)request.get("interpreter_calls");answer+=(Integer)request.get("answer_calls");
            risk+=(Integer)request.get("risk_calls");agent+=(Integer)request.get("agent_calls");
            if("POST".equals(request.get("method")))serverMicros+=(Long)request.get("elapsed_us");
        }
        var card=child(child(child(last.body(),"tools"),"card"),"data");
        String reply=Objects.toString(last.body().get("reply"),"");
        return map("scenario",scenario,"protocol",selfCheckCard(flow.shown())==null?"PER_STEP_CHAT":"OUTCOME_ONLY",
                "steps_shown",stepCount(flow.shown()),"step_requests",counts,"step_requests_total",steps.size(),
                "rate_limited_path_requests",limited,"http_429",throttled,"forced_wait_s",waitedSeconds,
                "model_calls",map("interpreter",interpreter,"grounded_answer",answer,"risk",risk,"agent",agent),
                "step_phase_wall_us",wallMicros,"step_phase_post_us",serverMicros,
                "final_status",last.status(),"final_reply_head",reply.length()>40?reply.substring(0,40):reply,
                "final_card",card==null?null:card.get("type"),"closed",state.closed(),
                "check_done",state.dialogue().answers().get("CHECK_DONE"),
                "saved_messages",saved.body().get("messages") instanceof List<?> list?list.size():-1);
    }

    /** Registers a refrigerator and answers the cooling question, as the existing integration test does. */
    Flow start(boolean honorRateLimit) throws Exception {
        phase="setup";
        String email="probe-"+UUID.randomUUID()+"@example.com";
        users.save(new AppUser(email,"unused","USER"));
        var created=send("POST","/api/products",email,map("category","REFRIGERATOR","name","검증용 냉장고","model","S836MEE022",
                "purchaseDate","2026-05-05","address","제주특별자치도 제주시"),false);
        long product=((Number)created.body().get("id")).longValue();
        interpretations.add(decision("SYMPTOM",null,List.of(map("code","COOLING","confidence",.98)),List.of(),null));
        var first=chat(email,map("message","냉장고가 시원하지 않아요.","category","REFRIGERATOR","productId",product),honorRateLimit);
        long conversation=((Number)first.body().get("conversationId")).longValue();
        var shown=chat(email,map("message","모두 전혀 차갑지 않아요","conversationId",conversation),honorRateLimit);
        return new Flow(email,conversation,shown.body());
    }

    /** Before: each step answer is a chat message. After: the card advances in the browser and posts one outcome. */
    Reply walk(Flow flow,String outcome,int solvedAt,boolean honorRateLimit) throws Exception {
        phase="steps";
        var card=selfCheckCard(flow.shown());
        if(card!=null){
            var data=child(card,"data");int steps=((List<?>)data.get("steps")).size();
            long version=((Number)child(card,"meta").get("context_version")).longValue();
            var reply=send("POST","/api/conversations/"+flow.conversation()+"/self-check",flow.email(),
                    outcomeBody(data,outcome,"SOLVED".equals(outcome)?solvedAt:steps,version),honorRateLimit);
            if(reply.status()==200)history(flow.email());
            return reply;
        }
        var current=new Reply(200,flow.shown());int step=1;
        while(step<=20&&question(current.body())!=null&&"SELF_CHECK".equals(question(current.body()).get("id"))){
            @SuppressWarnings("unchecked") var options=(List<String>)question(current.body()).get("options");
            String answer="SOLVED".equals(outcome)&&step==solvedAt?"해결됐어요"
                    :options.contains("아니요, 다음 단계")?"아니요, 다음 단계":"아니요, 해결되지 않았어요";
            current=chat(flow.email(),map("message",answer,"conversationId",flow.conversation()),honorRateLimit);step++;
        }
        return current;
    }

    /** One chat message as Chat.jsx sends it: the POST, then the history reload it performs on success or failure. */
    Reply chat(String email,Map<String,Object> body,boolean honorRateLimit) throws Exception {
        var reply=send("POST","/api/chat",email,body,honorRateLimit);
        history(email);
        return reply;
    }

    void history(String email) throws Exception {send("GET","/api/conversations",email,null,false);}

    Reply send(String method,String path,String email,Object body,boolean honorRateLimit) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+backendPort+path)).header("Authorization",token(email));
        if(body==null)builder.GET();
        else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        var request=builder.build();
        while(true){
            int[] before=counters();long start=System.nanoTime();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long micros=(System.nanoTime()-start)/1000;int[] after=counters();
            String retry=response.headers().firstValue("Retry-After").orElse("");
            requests.add(map("scenario",scenario,"phase",phase,"method",method,"path",path.replaceAll("/\\d+","/{id}"),
                    "status",response.statusCode(),"elapsed_us",micros,"retry_after",retry,
                    "interpreter_calls",after[0]-before[0],"answer_calls",after[1]-before[1],"risk_calls",after[2]-before[2],"agent_calls",after[3]-before[3]));
            if(response.statusCode()==429&&honorRateLimit&&!retry.isEmpty()){
                if("/api/chat".equals(path))history(email);
                long wait=Long.parseLong(retry);waitedSeconds+=wait;Thread.sleep(wait*1000);continue;
            }
            Map<String,Object> parsed=Map.of();
            if(response.body()!=null&&response.body().startsWith("{")){
                @SuppressWarnings("unchecked") var map=(Map<String,Object>)JSON.readValue(response.body(),Map.class);parsed=map;
            }
            return new Reply(response.statusCode(),parsed);
        }
    }

    /** Scripted-model requests so far: interpreter, grounded answer, risk check, anything else (tool-calling agent). */
    static int[] counters(){
        int risk=0,agent=0;
        synchronized(modelRequests){for(String input:modelRequests){if(input.contains("risk_detected"))risk++;else agent++;}}
        return new int[]{interpretationRequests.size(),answerRequests.size(),risk,agent};
    }

    static Map<String,Object> outcomeBody(Map<String,Object> data,String outcome,int step,long version){
        var body=map("result",outcome,"step",step,"context_version",version);
        // Observation questions (later versions) are answered with their first option when the steps did not help.
        if(!"SOLVED".equals(outcome)&&data.get("questions") instanceof List<?> questions&&!questions.isEmpty()){
            var answers=new LinkedHashMap<String,Object>();
            for(Object question:questions){
                var q=(Map<?,?>)question;var first=(Map<?,?>)((List<?>)q.get("options")).get(0);
                answers.put(q.get("id").toString(),first.get("value"));
            }
            body.put("observations",answers);
        }
        return body;
    }

    static Map<String,Object> selfCheckCard(Map<String,Object> body){
        var card=child(child(body,"tools"),"card");
        return card!=null&&"SELF_CHECK".equals(Objects.toString(child(card,"data")==null?null:child(card,"data").get("type")))?card:null;
    }

    static int stepCount(Map<String,Object> body){
        var card=selfCheckCard(body);
        if(card!=null)return ((List<?>)child(card,"data").get("steps")).size();
        var match=java.util.regex.Pattern.compile("1/(\\d+)단계").matcher(Objects.toString(body.get("reply"),""));
        return match.find()?Integer.parseInt(match.group(1)):-1;
    }

    static Map<String,Object> question(Map<String,Object> body){return child(child(child(child(body,"tools"),"consultation"),"data"),"question");}

    @SuppressWarnings("unchecked")
    static Map<String,Object> child(Map<String,Object> node,String key){
        return node!=null&&node.get(key) instanceof Map<?,?> value?(Map<String,Object>)value:null;
    }

    void write(String name,Map<String,Object> report) throws Exception {
        report.put("label",Objects.toString(System.getenv("AUDIT_LABEL"),"tree"));
        report.put("environment",map("java",System.getProperty("java.version"),"os",System.getProperty("os.name")+" "+System.getProperty("os.version"),
                "processors",Runtime.getRuntime().availableProcessors(),"database","H2 in-memory (MySQL mode)","model","scripted local OpenAI endpoint",
                "mcp","real FastMCP over local HTTP"));
        report.put("requests",requests);
        var directory=Path.of("build","optimization-audit");Files.createDirectories(directory);
        Files.writeString(directory.resolve(name+".json"),JSON.writeValueAsString(report)+"\n");
    }
}
