package com.project.backend.mcp.consultation;
import java.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import com.project.backend.mcp.tool.*;
@RestController
public class ConsultationController {
    private final ConsultationService consultations;
    private final com.project.backend.mcp.agent.AgentChatService agent;
    private final com.project.backend.mcp.agent.SelfCareChatService selfCare;
    public ConsultationController(ConsultationService consultations,com.project.backend.mcp.agent.AgentChatService agent,com.project.backend.mcp.agent.SelfCareChatService selfCare){this.consultations=consultations;this.agent=agent;this.selfCare=selfCare;}
    @GetMapping("/api/conversations/{id}/context") public Map<String,Object> context(@PathVariable Long id,Principal principal){
        var snapshot=consultations.snapshot(principal.getName(),id);
        return ToolDataCatalog.map("snapshot",snapshot,"risk_question","SUSPECT".equals(snapshot.state().riskStatus())?RiskRules.questions(snapshot.state().riskCase()).get(snapshot.state().riskQuestionNo()):null);
    }
    @PostMapping("/api/conversations/{id}/step-answer") public com.project.backend.chat.dto.ChatResponse step(@PathVariable Long id,@RequestBody Map<String,Object> body,Principal principal){
        return selfCare.answer(id,principal.getName(),body,false);
    }
    @PostMapping("/api/conversations/{id}/precondition-answer") public com.project.backend.chat.dto.ChatResponse condition(@PathVariable Long id,@RequestBody Map<String,Object> body,Principal principal){
        return selfCare.answer(id,principal.getName(),body,true);
    }
    @PostMapping("/api/conversations/{id}/product") public ConsultationService.Snapshot confirm(@PathVariable Long id,@RequestBody Map<String,Object> body,Principal principal){return consultations.confirmProduct(principal.getName(),id,ToolSupport.number(body,"product_id"));}
    @PostMapping("/api/conversations/{id}/risk-answer") public com.project.backend.chat.dto.ChatResponse risk(@PathVariable Long id,@RequestBody Map<String,Object> body,Principal principal){
        return agent.answerRisk(id,principal.getName(),body.get("answer") instanceof String answer?answer:null,ToolSupport.number(body,"context_version"));
    }
    @PostMapping("/api/conversations/{id}/self-check") public com.project.backend.chat.dto.ChatResponse selfCheck(@PathVariable Long id,@RequestBody Map<String,Object> body,Principal principal){
        return agent.answerSelfCheck(id,principal.getName(),body.get("result") instanceof String result?result:null,body.get("step"),body.get("context_version"));
    }
}
