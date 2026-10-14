package com.project.backend.mcp.agent;

import static com.project.backend.mcp.tool.ToolDataCatalog.map;
import java.util.*;
import java.util.function.BiFunction;
import com.project.backend.mcp.consultation.ConsultationService;
import com.project.backend.mcp.tool.*;

/** Ordered, bounded completion calls. They are policy calls, never credited to the model. */
public final class RepairFollowupPolicy {
    private RepairFollowupPolicy(){ }
    public static void complete(ConsultationService.Snapshot s,Map<String,ToolEnvelope> outputs,
            BiFunction<String,Map<String,Object>,ToolEnvelope> call,Map<String,Object> route){
        if(s.state().closed()||List.of("SUSPECT","CONFIRMED").contains(s.state().riskStatus()))return;
        var pending=new ArrayList<String>();String intent=s.state().dialogue().intent();
        boolean cost="COST".equals(intent),service="SERVICE".equals(intent);
        var diagnostic=outputs.get("get_diagnostic_plan");String reason=diagnostic==null||diagnostic.data()==null?"":Objects.toString(diagnostic.data().get("skip_reason"),"");
        if(cost){
            var candidates=ensure("get_repair_candidates",Map.of(),outputs,call,pending);
            if(candidates!=null&&candidates.data()!=null&&!"BLOCKED_BY_SAFETY".equals(candidates.status())){
                var args=RepairCandidatePolicy.costArguments(candidates);var answers=s.state().dialogue().answers();
                if(answers.containsKey("VISIT_TIME_SLOT"))args.put("time_slot",answers.get("VISIT_TIME_SLOT"));
                if(answers.containsKey("VISIT_MONTH"))args.put("month",Integer.valueOf(answers.get("VISIT_MONTH")));
                ensure("estimate_repair_cost",args,outputs,call,pending);
            }
            ensure("check_warranty",Map.of(),outputs,call,pending);
        }else if("WARRANTY".equals(intent))ensure("check_warranty",Map.of(),outputs,call,pending);
        var estimate=outputs.get("estimate_repair_cost");
        boolean unanswered=s.state().dialogue().pendingQuestion()!=null;
        boolean needsService=service||Set.of("ALL_DONE","NO_SELF_CARE","PRECONDITION_NOT_MET","PRECONDITION_UNCONFIRMED","NOT_VERIFIED").contains(reason)
                ||"UNCLASSIFIED".equals(intent)||cost&&!unanswered&&(estimate==null||"NO_DATA".equals(estimate.status()));
        if(needsService){
            ensure("check_warranty",Map.of(),outputs,call,pending);
            if(s.productId()!=null)ensure("find_service_centers",Map.of(),outputs,call,pending);
            String handoffReason=switch(reason){case "ALL_DONE"->"NOT_RESOLVED";case "NOT_VERIFIED"->"NOT_VERIFIED";case "NO_SELF_CARE"->"NO_SELF_CARE";case "PRECONDITION_NOT_MET","PRECONDITION_UNCONFIRMED"->"PRECONDITION_UNCONFIRMED";default->"UNCLASSIFIED".equals(intent)?"UNCLASSIFIED":"INSUFFICIENT_EVIDENCE";};
            ensure("prepare_service_handoff",map("reason",handoffReason),outputs,call,pending);
        }
        if(!pending.isEmpty())outputs.put("followup",ToolEnvelope.policy("PARTIAL",map("pending_tools",List.copyOf(pending),"route",route,"reason","TOOL_BUDGET_OR_UNAVAILABLE"),map("context_version",s.version()),List.of("일부 후속 조회를 마치지 못했습니다. 공식 상담 경로를 이용하거나 다음 대화에서 다시 문의해 주세요.")));
    }
    private static ToolEnvelope ensure(String name,Map<String,Object> args,Map<String,ToolEnvelope> outputs,BiFunction<String,Map<String,Object>,ToolEnvelope> call,List<String> pending){
        var old=outputs.get(name);if(old!=null)return old;
        var result=call.apply(name,args);if(result==null)pending.add(name);else outputs.put(name,result);return result;
    }
}
