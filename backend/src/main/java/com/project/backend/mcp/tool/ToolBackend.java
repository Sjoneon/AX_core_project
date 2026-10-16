package com.project.backend.mcp.tool;

import static java.util.Objects.requireNonNull;

import static com.project.backend.mcp.tool.ToolSupport.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.project.backend.mcp.consultation.ConsultationService;

/** Validates ownership, context version and arguments, then dispatches to one tool handler. */
@Service
public class ToolBackend {
    public static final List<String> NAMES=List.of("lookup_model","search_knowledge","get_product_context","get_diagnostic_plan","check_warranty","get_repair_candidates","estimate_repair_cost","find_service_centers","prepare_service_handoff");
    private final ConsultationService consultations;
    private final Map<String,HomeCareTool> tools=new HashMap<>();
    public ToolBackend(ConsultationService consultations,List<HomeCareTool> tools){
        this.consultations=consultations;
        for(var tool:tools)if(this.tools.put(tool.name(),tool)!=null)throw new IllegalStateException("중복된 도구입니다: "+tool.name());
        if(!this.tools.keySet().equals(Set.copyOf(NAMES)))throw new IllegalStateException("도구 구성이 계약과 다릅니다: "+this.tools.keySet());
    }
    public ToolEnvelope execute(String name,String email,Map<String,Object> args,Long expectedVersion){
        var tool=tools.get(name);
        if(tool==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"도구를 찾을 수 없습니다.");
        Long id=number(args,"conversation_id");var s=consultations.snapshot(email,id);
        if(expectedVersion!=null&&expectedVersion!=s.version())throw new ResponseStatusException(HttpStatus.CONFLICT,"CONTEXT_STALE");
        if(!tool.arguments().containsAll(args.keySet()))bad("허용되지 않은 도구 입력입니다.");
        return tool.execute(email,s,args);
    }
    public ToolEnvelope answerStep(String email,Long id,Map<String,Object> args){
        var diagnostics=tools.get("get_diagnostic_plan");
        var s=consultations.snapshot(email,id);var plan=diagnostics.execute(email,s,Map.of());
        String answer=ToolDataCatalog.text(args,"answer");if(!List.of("YES","NO").contains(answer))bad("해결 여부를 확인해 주세요.");
        var steps=plan.data()==null?List.of():(List<?>)plan.data().getOrDefault("steps",List.of());
        if(!plan.status().equals("OK")||steps.isEmpty()||!Objects.equals(((Map<?,?>)requireNonNull(steps.getFirst())).get("step_code"),args.get("step_code")))bad("현재 승인된 조치가 아닙니다.");
        long version=number(args,"context_version");if(version!=s.version())throw new ResponseStatusException(HttpStatus.CONFLICT,"CONTEXT_STALE");
        var next=consultations.stepAnswer(email,id,version,ToolDataCatalog.text(args,"step_code"),answer.equals("YES"));return diagnostics.execute(email,next,Map.of());
    }
    public ToolEnvelope answerCondition(String email,Long id,Map<String,Object> args){
        var s=consultations.snapshot(email,id);
        long version=number(args,"context_version");if(version!=s.version())throw new ResponseStatusException(HttpStatus.CONFLICT,"CONTEXT_STALE");
        String answer=ToolDataCatalog.text(args,"answer");if(!List.of("YES","NO","UNSURE").contains(answer))bad("조건 응답을 확인해 주세요.");
        var diagnostics=tools.get("get_diagnostic_plan");var result=diagnostics.execute(email,s,Map.of());
        if(!"NEEDS_INPUT".equals(result.status())||!"CONFIRM_PRECONDITION".equals(result.data().get("interaction"))||
                !(result.data().get("question") instanceof Map<?,?> q)||!Objects.equals(q.get("id"),args.get("question_id"))||
                !Objects.equals(result.data().get("plan_scope"),args.get("plan_scope")))bad("현재 선행 조건 질문이 아닙니다.");
        var next=consultations.conditionAnswer(email,id,version,ToolDataCatalog.text(result.data(),"plan_scope"),ToolDataCatalog.text(args,"question_id"),answer);
        return diagnostics.execute(email,next,Map.of());
    }
}
