package com.project.backend.mcp;

import static com.project.backend.mcp.tool.ToolDataCatalog.map;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import com.project.backend.user.AppUser;

/**
 * Same replay with the chat limit raised, so request counts and local timings are not mixed with 429 waits.
 * Also checks that the outcome request keeps ownership, context-version and step validation.
 */
@TestPropertySource(properties={"homecare.rate-limit.chat.burst=1000000","homecare.rate-limit.chat.per-minute=1000000",
        "homecare.rate-limit.chat.per-day=1000000"})
class SelfCheckRequestTimingProbe extends SelfCheckRequestProbe {
    static final int WARMUP=3,REPEAT=20;

    @Test void probeRequestsAndTiming() throws Exception {
        var runs=new ArrayList<Map<String,Object>>();
        // Scenarios alternate so drift during the run affects both alike.
        for(int i=0;i<WARMUP+REPEAT;i++){
            for(var run:List.of(scenario("NOT_RESOLVED",0,false),scenario("SOLVED",3,false))){
                run.put("iteration",i);run.put("warmup",i<WARMUP);runs.add(run);
            }
        }
        write("self_check_requests",map("warmup",WARMUP,"repeat",REPEAT,"runs",runs,"checks",checks()));
    }

    Map<String,Object> checks() throws Exception {
        scenario="checks";
        var flow=start(false);
        phase="checks";
        String other="probe-other-"+UUID.randomUUID()+"@example.com";
        users.save(new AppUser(other,"unused","USER"));
        var result=new LinkedHashMap<String,Object>();
        var card=selfCheckCard(flow.shown());
        if(card==null){
            result.put("protocol","PER_STEP_CHAT");
            result.put("other_user_chat_status",send("POST","/api/chat",other,map("message","아니요, 다음 단계","conversationId",flow.conversation()),false).status());
            return result;
        }
        result.put("protocol","OUTCOME_ONLY");
        var data=child(card,"data");int steps=((List<?>)data.get("steps")).size();
        long version=((Number)child(card,"meta").get("context_version")).longValue();
        String path="/api/conversations/"+flow.conversation()+"/self-check";
        result.put("other_user_status",send("POST",path,other,outcomeBody(data,"NOT_RESOLVED",steps,version),false).status());
        result.put("stale_version_status",send("POST",path,flow.email(),outcomeBody(data,"NOT_RESOLVED",steps,version-1),false).status());
        result.put("not_resolved_before_last_step_status",send("POST",path,flow.email(),outcomeBody(data,"NOT_RESOLVED",steps-1,version),false).status());
        result.put("unknown_result_status",send("POST",path,flow.email(),map("result","MAYBE","step",1,"context_version",version),false).status());
        int[] before=counters();
        result.put("valid_status",send("POST",path,flow.email(),outcomeBody(data,"NOT_RESOLVED",steps,version),false).status());
        int[] after=counters();
        result.put("valid_model_calls",Arrays.stream(after).sum()-Arrays.stream(before).sum());
        result.put("duplicate_status",send("POST",path,flow.email(),outcomeBody(data,"NOT_RESOLVED",steps,version),false).status());
        return result;
    }
}
