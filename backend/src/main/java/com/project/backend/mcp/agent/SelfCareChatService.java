package com.project.backend.mcp.agent;

import static com.project.backend.mcp.tool.ToolDataCatalog.map;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.project.backend.chat.dto.ChatResponse;
import com.project.backend.conversation.ConversationService;
import com.project.backend.mcp.audit.ToolCallRecord;
import com.project.backend.mcp.consultation.*;
import com.project.backend.mcp.tool.*;
import com.project.backend.warranty.WarrantyModels;
import com.project.backend.warranty.WarrantyService;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Atomic self-care state/result/history writes; no external model call inside the transaction. */
@Service
public class SelfCareChatService {
    private final ToolBackend tools;private final ConsultationService consultations;private final ConversationService history;
    private final AgentLimits limits;private final ToolDataCatalog catalog;private final WarrantyService warranty;
    private static final JsonMapper JSON=JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    public SelfCareChatService(ToolBackend tools,ConsultationService consultations,ConversationService history,AgentLimits limits,ToolDataCatalog catalog,WarrantyService warranty){this.tools=tools;this.consultations=consultations;this.history=history;this.limits=limits;this.catalog=catalog;this.warranty=warranty;}
    @Transactional
    public ChatResponse answer(Long id,String email,Map<String,Object> args,boolean condition){
        var before=tools.execute("get_diagnostic_plan",email,map("conversation_id",id),ToolSupport.number(args,"context_version"));
        var result=condition?tools.answerCondition(email,id,args):tools.answerStep(email,id,args);
        String label=Map.of("YES","예","NO","아니요","UNSURE","모르겠어요").get(Objects.toString(args.get("answer"),""));
        String question=condition?Objects.toString(((Map<?,?>)before.data().get("question")).get("text"),""):
            Objects.toString(((Map<?,?>)((List<?>)before.data().get("steps")).getFirst()).get("text"),"")+" — 해결됐나요?";
        return save(consultations.snapshot(email,id),email,question+"\n"+label,result);
    }
    @Transactional
    public ChatResponse dialogue(ConsultationService.Snapshot expected,String email,String input,ConsultationState next){
        var s=consultations.applyDialogue(email,expected.id(),expected.version(),next);
        var result=tools.execute("get_diagnostic_plan",email,map("conversation_id",s.id()),s.version());
        return save(s,email,input,result);
    }
    private ChatResponse save(ConsultationService.Snapshot s,String email,String input,ToolEnvelope result){
        result.meta().put("selection_source","POLICY_FALLBACK");result.meta().put("execution_source","SELF_CARE_POLICY");
        var outputs=new LinkedHashMap<String,ToolEnvelope>();outputs.put("get_diagnostic_plan",result);
        var calls=new ArrayList<ToolCallRecord>();calls.add(ToolCallRecord.success("get_diagnostic_plan",Map.of(),"POLICY_FALLBACK",result,0));
        long started=System.nanoTime();
        RepairFollowupPolicy.complete(s,outputs,(name,args)->{
            if(calls.size()>=limits.maxToolCalls()||System.nanoTime()-started>limits.totalTimeout().toNanos())return null;
            long invoked=System.nanoTime();var bounded=new LinkedHashMap<>(args);bounded.put("conversation_id",s.id());
            var next=tools.execute(name,email,bounded,s.version());next.meta().put("selection_source","POLICY_FALLBACK");next.meta().put("execution_source","SELF_CARE_FOLLOWUP_POLICY");
            calls.add(ToolCallRecord.success(name,args,"POLICY_FALLBACK",next,java.time.Duration.ofNanos(System.nanoTime()-invoked).toMillis()));return next;
        },catalog.route);
        var checked=outputs.get("check_warranty");var coverage=checked==null?null:JSON.convertValue(checked.data(),WarrantyModels.Result.class);
        var lines=new ArrayList<String>();lines.add(DiagnosticGuidance.format(result));
        if(coverage!=null)lines.add(coverage.headline()+" 실제 무상 여부는 고장 부품·증빙·모델 조건 확인이 필요하며, 상세 내용은 보증 결과에서 확인해 주세요.");
        var centers=outputs.get("find_service_centers");if(centers!=null)lines.add("등록 제품 배송지역의 센터와 공식 접수 경로를 준비했어요. 운영·접수 가능 여부는 공식 페이지에서 확인해 주세요.");
        var handoff=outputs.get("prepare_service_handoff");if(handoff!=null)lines.add("상담 요약을 준비했으며 외부 전송·예약은 하지 않았습니다. 아래 요약을 복사해 [LG 공식 출장 접수]("+catalog.route.get("apply_url")+") 또는 고객센터 "+catalog.route.get("phone")+"로 문의해 주세요.");
        if(outputs.containsKey("followup"))lines.add("일부 후속 조회를 마치지 못했어요. 공식 상담 경로를 이용하거나 다음 대화에서 다시 문의해 주세요.");
        String reply=String.join("\n\n",lines);
        history.saveExchange(s.id(),email,input,reply,s.category(),List.of(),coverage,outputs,calls);
        return new ChatResponse(reply,s.id(),List.of(),coverage,outputs);
    }
}
