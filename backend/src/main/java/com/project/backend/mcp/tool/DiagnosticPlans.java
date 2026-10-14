package com.project.backend.mcp.tool;

import static com.project.backend.mcp.tool.ToolDataCatalog.*;
import java.util.*;
import com.project.backend.mcp.consultation.*;

/** Shared approval and condition checks for tools, dialogue and grounded answer validation. */
public final class DiagnosticPlans {
    private DiagnosticPlans(){ }
    public static Optional<Map<String,Object>> select(ToolDataCatalog catalog,ConsultationService.Snapshot s,String model){
        if(s.productId()==null||model==null||List.of("SUSPECT","CONFIRMED").contains(s.state().riskStatus()))return Optional.empty();
        var matches=catalog.approvedPlans.stream().filter(p->Boolean.TRUE.equals(p.get("actionApproved")))
                .filter(p->s.category().name().equals(p.get("category"))&&strings(p,"modelCodes").contains(ToolSupport.model(model)))
                .filter(p->s.state().errorCode()!=null?Objects.equals(p.get("errorCode"),s.state().errorCode()):
                    s.state().symptomCode()!=null&&Objects.equals(p.get("symptomCode"),s.state().symptomCode())).toList();
        return matches.size()==1?Optional.of(matches.getFirst()):Optional.empty();
    }
    @SuppressWarnings("unchecked")
    public static List<Map<String,Object>> conditions(Map<String,Object> plan){return (List<Map<String,Object>>)plan.getOrDefault("preconditions",List.of());}
    @SuppressWarnings("unchecked")
    public static List<Map<String,Object>> steps(Map<String,Object> plan){return (List<Map<String,Object>>)plan.getOrDefault("steps",List.of());}
    public static String scope(ToolDataCatalog catalog,ConsultationService.Snapshot s,String model,Map<String,Object> plan){
        return catalog.version+":"+text(plan,"id")+":"+text(plan,"revision")+":"+s.productId()+":"+ToolSupport.model(model);
    }
    public static Map<String,String> answers(ToolDataCatalog catalog,ConsultationService.Snapshot s,String model,Map<String,Object> plan){
        return Objects.equals(scope(catalog,s,model,plan),s.state().selfCare().scope())?s.state().selfCare().conditions():Map.of();
    }
    public static List<Map<String,Object>> pendingSteps(ToolDataCatalog catalog,ConsultationService.Snapshot s,String model){
        if(s.state().closed())return List.of();
        var selected=select(catalog,s,model);if(selected.isEmpty())return List.of();var plan=selected.get();
        var answers=answers(catalog,s,model,plan);
        if(conditions(plan).stream().anyMatch(q->!"YES".equals(answers.get(text(q,"id")))))return List.of();
        return steps(plan).stream().filter(step->!s.state().doneSteps().contains(text(step,"step_code"))).limit(1).toList();
    }
    public static String outcome(ToolDataCatalog catalog,ConsultationService.Snapshot s,String model){
        if(List.of("SUSPECT","CONFIRMED").contains(s.state().riskStatus()))return "RISK";
        if(s.productId()==null)return "PRODUCT_NEEDED";if(s.state().closed())return "RESOLVED";
        var selected=select(catalog,s,model);if(selected.isEmpty())return "NOT_VERIFIED";
        var plan=selected.get();var answers=answers(catalog,s,model,plan);
        for(var q:conditions(plan)){
            String answer=answers.get(text(q,"id"));if("YES".equals(answer))continue;
            return answer==null?"PRECONDITIONS_PENDING":"NO".equals(answer)?"PRECONDITION_NOT_MET":"PRECONDITION_UNCONFIRMED";
        }
        if(steps(plan).isEmpty())return "NO_SELF_CARE";
        return pendingSteps(catalog,s,model).isEmpty()?"ALL_DONE":"IN_PROGRESS";
    }
}
