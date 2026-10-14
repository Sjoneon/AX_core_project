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

/** Atomic self-care state/result/history writes; no external model call inside the transaction. */
@Service
public class SelfCareChatService {
    private final ToolBackend tools;private final ConsultationService consultations;private final ConversationService history;
    public SelfCareChatService(ToolBackend tools,ConsultationService consultations,ConversationService history){this.tools=tools;this.consultations=consultations;this.history=history;}
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
        var outputs=mapOf(result);String reply=DiagnosticGuidance.format(result);
        var calls=List.of(ToolCallRecord.success("get_diagnostic_plan",Map.of(),"POLICY_FALLBACK",result,0));
        history.saveExchange(s.id(),email,input,reply,s.category(),List.of(),null,outputs,calls);
        return new ChatResponse(reply,s.id(),List.of(),null,outputs);
    }
    private static Map<String,ToolEnvelope> mapOf(ToolEnvelope result){return Map.of("get_diagnostic_plan",result);}
}
