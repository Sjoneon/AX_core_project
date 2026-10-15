package com.project.backend.mcp.tool.handler;

import static com.project.backend.mcp.tool.ToolDataCatalog.*;
import static com.project.backend.mcp.tool.ToolSupport.*;
import java.util.*;
import org.springframework.stereotype.Component;
import com.project.backend.mcp.consultation.ConsultationService;
import com.project.backend.mcp.tool.*;
import com.project.backend.warranty.WarrantyService;

@Component
public class PrepareServiceHandoffTool implements HomeCareTool {
    private final ToolSupport support;
    private final ToolDataCatalog catalog;
    private final WarrantyService warranty;
    public PrepareServiceHandoffTool(ToolSupport support,ToolDataCatalog catalog,WarrantyService warranty){this.support=support;this.catalog=catalog;this.warranty=warranty;}
    public String name(){return "prepare_service_handoff";}
    public Set<String> arguments(){return Set.of("conversation_id","reason");}
    public ToolEnvelope execute(String email,ConsultationService.Snapshot s,Map<String,Object> args){
        String reason=Objects.toString(args.get("reason"),"INSUFFICIENT_EVIDENCE");
        if(!List.of("RISK","NOT_RESOLVED","UNCLASSIFIED","OUT_OF_SCOPE","INSUFFICIENT_EVIDENCE","NOT_VERIFIED","NO_SELF_CARE","PRECONDITION_UNCONFIRMED").contains(reason))bad("연결 사유를 확인해 주세요.");
        if(reason.equals("RISK")&&!List.of("SUSPECT","CONFIRMED").contains(s.state().riskStatus()))bad("위험 상태와 연결 사유가 다릅니다.");
        var p=support.product(email,s);String outcome=DiagnosticPlans.outcome(catalog,s,p==null?null:model(p.warranty().modelName()));
        if(reason.equals("NOT_RESOLVED")&&!"ALL_DONE".equals(outcome))bad("자가조치 미해결 상태가 아닙니다.");
        if(reason.equals("NOT_VERIFIED")&&!"NOT_VERIFIED".equals(outcome)||reason.equals("NO_SELF_CARE")&&!"NO_SELF_CARE".equals(outcome))bad("자가조치 상태와 연결 사유가 다릅니다.");
        if(reason.equals("PRECONDITION_UNCONFIRMED")&&!Set.of("PRECONDITION_NOT_MET","PRECONDITION_UNCONFIRMED").contains(outcome))bad("선행 조건 상태와 연결 사유가 다릅니다.");
        var facts=new ArrayList<String>();facts.add("품목: "+s.category().getLabel());facts.add("위험 확인: "+switch(s.state().riskStatus()){case "SUSPECT"->"확인 중";case "CONFIRMED"->"사용 중지·공식 점검 우선";case "DISMISSED"->"현재 확인 질문에서 위험 징후 없음";default->"현재 감지된 위험 징후 없음";});
        if(p!=null)facts.add("등록 모델: "+p.warranty().modelName());
        String symptom=com.project.backend.mcp.consultation.SymptomCatalog.label(s.category(),s.state().symptomCode());if(!symptom.isBlank())facts.add("확인한 증상: "+symptom);
        if(s.state().dialogue().symptomText()!=null)facts.add("고객 증상 설명: "+s.state().dialogue().symptomText().substring(0,Math.min(300,s.state().dialogue().symptomText().length())));
        String observed=com.project.backend.mcp.consultation.DialogueRouter.coolingLabel(s.state().dialogue().answers().get(com.project.backend.mcp.consultation.DialogueRouter.COOLING_STATE));
        if(observed!=null)facts.add("고객이 답한 상태: "+observed);
        var seen=com.project.backend.mcp.consultation.ObservationQuestions.lines(s.category(),s.state().symptomCode(),
                com.project.backend.mcp.consultation.ObservationQuestions.stored(s.state().dialogue().answers()));
        if(!seen.isEmpty())facts.add("고객이 확인한 관찰: "+String.join(", ",seen));
        facts.add("자가조치 상태: "+switch(outcome){case "NOT_VERIFIED"->"실행 승인 미확인";case "ALL_DONE"->"검토된 단계 모두 수행, 미해결";case "NO_SELF_CARE"->"검토 결과 조치 없음";case "PRECONDITION_NOT_MET"->"선행 조건 미충족";case "PRECONDITION_UNCONFIRMED","PRECONDITIONS_PENDING"->"선행 조건 미확인";case "IN_PROGRESS"->"단계 진행 중";case "RESOLVED"->"해결";case "RISK"->"위험 확인 우선";default->"제품 확인 필요";});
        if(s.state().errorCode()!=null)facts.add("오류코드: "+s.state().errorCode());
        var stepNames=new HashMap<String,String>();for(var plan:catalog.approvedPlans)for(var step:DiagnosticPlans.steps(plan))stepNames.put(text(step,"step_code"),text(step,"text"));
        for(String code:s.state().doneSteps())facts.add("수행 보고: "+stepNames.getOrDefault(code,"기존 상담에서 확인한 조치")+" ("+("RESOLVED".equals(s.state().selfCare().results().get(code))?"해결":"미해결 또는 결과 추가 확인")+")");
        var sources=new ArrayList<Map<String,Object>>();
        if(s.productId()!=null){var value=warranty.check(support.product(email,s).warranty());facts.add("제품 전체 보증: "+switch(value.productWarranty().periodStatus()){case "IN_WARRANTY"->"보증기간 안, 실제 무상 적용 조건 확인 필요";case "EXPIRED"->"기간 만료";default->"추가 확인 필요";});facts.add("주요 부품 보증은 해당 부품·모델 조건 별도 확인");sources.add(evidence("LG_WARRANTY","OFFICIAL_POLICY",value.sourceUrl(),value.policyVerifiedOn().toString()));}
        var basis=Basis.of("HIGH",List.of(ROUTE,POLICIES),"상담 요약 준비이며 외부로 전송하거나 예약하지 않습니다.","최종 진단·개인별 무상 승인·실제 청구 금액은 포함하지 않습니다.");
        return support.result(s,"OK",map("reason",reason,"self_care_outcome",outcome,"delivery_status","PREPARED_ONLY","summary_text",String.join("\n",facts),"known_facts",facts,"unknown_facts",List.of("엔지니어 최종 진단","개인별 실제 무상 승인","실제 청구 금액"),"route",catalog.route),sources,basis,"상담 요약 준비이며 외부에 전송하거나 예약하지 않았습니다.");
    }
}
