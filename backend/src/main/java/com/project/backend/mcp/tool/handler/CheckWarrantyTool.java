package com.project.backend.mcp.tool.handler;

import static com.project.backend.mcp.tool.ToolDataCatalog.map;
import static com.project.backend.mcp.tool.ToolSupport.*;
import java.util.*;
import org.springframework.stereotype.Component;
import com.project.backend.mcp.consultation.ConsultationService;
import com.project.backend.mcp.tool.*;
import com.project.backend.warranty.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
public class CheckWarrantyTool implements HomeCareTool {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final ToolSupport support;
    private final ConsultationService consultations;
    private final WarrantyService warranty;
    public CheckWarrantyTool(ToolSupport support,ConsultationService consultations,WarrantyService warranty){this.support=support;this.consultations=consultations;this.warranty=warranty;}
    public String name(){return "check_warranty";}
    public ToolEnvelope execute(String email,ConsultationService.Snapshot s,Map<String,Object> args){
        var selected=support.product(email,s);
        var manual=consultations.manualWarranty(email,s.id());
        var request=selected!=null?selected.warranty():manual!=null?manual:new WarrantyModels.Request(s.category(),null,null,null,null,null,null,false,false);
        var value=warranty.check(request);
        Map<String,Object> data=JSON.convertValue(value,new TypeReference<>(){});
        var product=value.productWarranty();
        // Quality warranty starts at the registered purchase date; retention is a separate manufacture-date period.
        data.put("coverage_periods",map(
                "quality_warranty",map("label","품질보증기간","basis_label","구입일 기준","basis_date",value.basisDate(),
                        "period_months",product.periodMonths(),"expires_on",product.expiresOn(),"status",product.periodStatus()),
                "parts_retention",map("label","부품보유기간","basis_label","제조일 기준","status","UNAVAILABLE","fact_ids",List.of(),
                        "reason","B30 원본 자료를 확인하기 전에는 부품보유기간 값을 제공하지 않습니다.")));
        var basis=Basis.of("UNKNOWN".equals(product.periodStatus())?"UNDETERMINED":"MEDIUM",List.of(POLICIES),
                "품질보증기간은 구입일 기준, 부품보유기간은 제조일 기준으로 서로 다른 기간입니다.",
                "기간 계산은 개인별 무상 수리 여부나 출장비·기술료 면제를 판정하지 않습니다.");
        boolean dated=value.basisDate()!=null;
        return support.result(s,dated?"OK":"NEEDS_INPUT",data,List.of(evidence("LG_WARRANTY","OFFICIAL_POLICY",value.sourceUrl(),value.policyVerifiedOn().toString())),
                dated?basis:basis.missing("purchase_date"));
    }
}
