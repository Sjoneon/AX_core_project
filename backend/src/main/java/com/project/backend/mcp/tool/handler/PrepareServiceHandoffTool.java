package com.project.backend.mcp.tool.handler;

import static com.project.backend.mcp.tool.ToolDataCatalog.map;
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
        if(!List.of("RISK","NOT_RESOLVED","UNCLASSIFIED","OUT_OF_SCOPE","INSUFFICIENT_EVIDENCE").contains(reason))bad("연결 사유를 확인해 주세요.");
        if(reason.equals("RISK")&&!List.of("SUSPECT","CONFIRMED").contains(s.state().riskStatus()))bad("위험 상태와 연결 사유가 다릅니다.");
        var facts=new ArrayList<String>();facts.add("품목: "+s.category().getLabel());facts.add("위험 상태: "+s.state().riskStatus());
        if(s.state().errorCode()!=null)facts.add("오류코드: "+s.state().errorCode());facts.add("완료한 조치: "+String.join(", ",s.state().doneSteps()));
        var sources=new ArrayList<Map<String,Object>>();
        if(s.productId()!=null){var value=warranty.check(support.product(email,s).warranty());facts.add("제품 전체 보증: "+value.productWarranty().periodStatus());facts.add("주요 부품 보증은 해당 부품·모델 조건 별도 확인");sources.add(evidence("LG_WARRANTY","OFFICIAL_POLICY",value.sourceUrl(),value.policyVerifiedOn().toString()));}
        var basis=Basis.of("HIGH",List.of(ROUTE,POLICIES),"상담 요약 준비이며 외부로 전송하거나 예약하지 않습니다.","최종 진단·개인별 무상 승인·실제 청구 금액은 포함하지 않습니다.");
        return support.result(s,"OK",map("reason",reason,"delivery_status","PREPARED_ONLY","summary_text",String.join("\n",facts),"known_facts",facts,"unknown_facts",List.of("엔지니어 최종 진단","개인별 실제 무상 승인","실제 청구 금액"),"route",catalog.route),sources,basis,"상담 요약 준비이며 외부에 전송하거나 예약하지 않았습니다.");
    }
}
