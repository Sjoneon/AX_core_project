package com.project.backend.mcp.tool.handler;

import static com.project.backend.mcp.tool.ToolDataCatalog.*;
import static com.project.backend.mcp.tool.ToolSupport.*;
import java.util.*;
import org.springframework.stereotype.Component;
import com.project.backend.mcp.consultation.*;
import com.project.backend.mcp.tool.*;

@Component
public class GetDiagnosticPlanTool implements HomeCareTool {
    private final ToolSupport support;
    private final ToolDataCatalog catalog;
    private final SearchKnowledgeTool knowledge;
    public GetDiagnosticPlanTool(ToolSupport support,ToolDataCatalog catalog,SearchKnowledgeTool knowledge){this.support=support;this.catalog=catalog;this.knowledge=knowledge;}
    public String name(){return "get_diagnostic_plan";}
    private static final String[] CAVEATS={"승인된 자가검진 단계만 한 번에 하나씩 안내합니다. 원문 후보는 실행 승인이 아닙니다.","안전 경고가 있으면 자가검진보다 사용 중지와 서비스 연결이 먼저입니다."};
    public ToolEnvelope execute(String email,ConsultationService.Snapshot s,Map<String,Object> args){
        var files=List.of(PLANS,KNOWLEDGE);
        if(List.of("SUSPECT","CONFIRMED").contains(s.state().riskStatus()))return support.result(s,"BLOCKED_BY_SAFETY",map("steps",List.of(),"skip_reason","RISK"),List.of(),Basis.of("UNDETERMINED",files,CAVEATS),RiskRules.warning());
        if(s.productId()==null)return support.needsProduct(s);
        if(s.state().closed())return support.result(s,"OK",map("steps",List.of(),"has_self_care",false,"skip_reason","RESOLVED"),List.of(),Basis.of("HIGH",files,CAVEATS));
        var p=support.product(email,s);String model=model(p.warranty().modelName());
        var plans=catalog.approvedPlans.stream().filter(x->s.category().name().equals(text(x,"category")))
                .filter(x->strings(x,"modelCodes").contains(model)).filter(x->Boolean.TRUE.equals(x.get("actionApproved")))
                .filter(x->s.state().errorCode()!=null&&Objects.equals(x.get("errorCode"),s.state().errorCode())
                        ||s.state().symptomCode()!=null&&Objects.equals(x.get("symptomCode"),s.state().symptomCode())).toList();
        var data=map("error_code",s.state().errorCode(),"symptom_code",s.state().symptomCode(),"steps",List.of(),"skipped_step_codes",s.state().doneSteps(),"has_self_care",false,"skip_reason","NO_SELF_CARE","current_step",s.state().currentStep());
        if(plans.isEmpty()){
            var found=knowledge.execute(email,s,map("query",s.state().searchQuery(s.input()),"topic","MANUAL","top_k",3));
            data.put("safety_notices",found.data().get("safety_notices"));data.put("references",found.data().get("documents"));data.put("skip_reason","NOT_VERIFIED");
            return support.result(s,"NOT_VERIFIED",data,found.evidence(),Basis.of("LOW",files,CAVEATS),"모델별 실행 승인과 선행 조건이 확인되지 않아 원문 후보만 제공합니다. 임의 조치 방법을 만들지 않습니다.");
        }
        var plan=plans.getFirst();var all=(List<Map<String,Object>>)plan.get("steps");
        var pending=all.stream().filter(step->!s.state().doneSteps().contains(text(step,"step_code"))).toList();
        data.put("steps",pending.stream().limit(1).toList());data.put("remaining_steps",pending.size());data.put("has_self_care",!pending.isEmpty());data.put("skip_reason",pending.isEmpty()?"ALL_DONE":null);
        return support.result(s,"OK",data,List.of(evidence(text(plan,"id"),"OFFICIAL_MANUAL",text(plan,"source"),text(plan,"baseDate"))),Basis.of("MEDIUM",files,CAVEATS));
    }
}
