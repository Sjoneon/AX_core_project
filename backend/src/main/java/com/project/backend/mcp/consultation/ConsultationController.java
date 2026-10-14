package com.project.backend.mcp.consultation;
import java.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import com.project.backend.mcp.tool.*;
@RestController
public class ConsultationController {
    private final ToolBackend tools;private final ConsultationService consultations;
    private final com.project.backend.mcp.agent.AgentChatService agent;
    public ConsultationController(ToolBackend tools,ConsultationService consultations,com.project.backend.mcp.agent.AgentChatService agent){this.tools=tools;this.consultations=consultations;this.agent=agent;}
    @GetMapping("/api/conversations/{id}/context") public Map<String,Object> context(@PathVariable Long id,Principal principal){
        var snapshot=consultations.snapshot(principal.getName(),id);
        return ToolDataCatalog.map("snapshot",snapshot,"risk_question","SUSPECT".equals(snapshot.state().riskStatus())?RiskRules.questions(snapshot.state().riskCase()).get(snapshot.state().riskQuestionNo()):null);
    }
    @PostMapping("/api/conversations/{id}/step-answer") public ToolEnvelope step(@PathVariable Long id,@RequestBody Map<String,Object> body,Principal principal){
        return tools.answerStep(principal.getName(),id,body);
    }
    @PostMapping("/api/conversations/{id}/product") public ConsultationService.Snapshot confirm(@PathVariable Long id,@RequestBody Map<String,Object> body,Principal principal){return consultations.confirmProduct(principal.getName(),id,ToolSupport.number(body,"product_id"));}
    @PostMapping("/api/conversations/{id}/risk-answer") public com.project.backend.chat.dto.ChatResponse risk(@PathVariable Long id,@RequestBody Map<String,Object> body,Principal principal){
        return agent.answerRisk(id,principal.getName(),body.get("answer") instanceof String answer?answer:null,ToolSupport.number(body,"context_version"));
    }
}
