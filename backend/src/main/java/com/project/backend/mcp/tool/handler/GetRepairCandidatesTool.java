package com.project.backend.mcp.tool.handler;

import static com.project.backend.mcp.tool.ToolDataCatalog.*;
import static com.project.backend.mcp.tool.ToolSupport.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Component;
import com.project.backend.mcp.consultation.ConsultationService;
import com.project.backend.mcp.tool.*;
import com.project.backend.product.UserProduct;

/** Repair part codes and names of the consultation category. A registered product is optional. */
@Component
public class GetRepairCandidatesTool implements HomeCareTool {
    static final String[] CAVEATS={"부품 후보는 원인 확정이 아닙니다. 실제 고장 부품은 엔지니어 점검 후 정해집니다.","부품 그룹은 수리비 추정용이며 실물 부품 호환을 확정하지 않습니다."};
    private final ToolSupport support;
    private final ToolDataCatalog catalog;
    public GetRepairCandidatesTool(ToolSupport support,ToolDataCatalog catalog){this.support=support;this.catalog=catalog;}
    public String name(){return "get_repair_candidates";}
    public ToolEnvelope execute(String email,ConsultationService.Snapshot s,Map<String,Object> args){
        var basis=Basis.of("LOW",List.of(PARTS),CAVEATS);
        if("SUSPECT".equals(s.state().riskStatus()))return support.result(s,"BLOCKED_BY_SAFETY",null,List.of(),basis,"위험 확인을 먼저 마쳐 주세요.");
        var product=support.product(email,s);
        var permitted=allowedParts(s);String error=s.state().errorCode();
        var mapped=error==null?List.<Map<String,Object>>of():permitted.stream().filter(p->strings(p,"targetCodes").contains(error)).limit(2).toList();
        var cases=catalog.repairCases.stream().filter(x->s.category().name().equals(text(x,"category"))).filter(x->"REFERENCE".equals(text(x,"use_type")))
                .filter(x->Objects.equals(x.get("error_code"),s.state().errorCode())&&s.state().errorCode()!=null).limit(5).toList();
        var data=map("candidate_set_id",candidateSet(s,product),"selection_source",!mapped.isEmpty()?"MAPPING":permitted.isEmpty()?cases.size()>=3?"REVIEW_RANGE":"DEFAULT_NOTICE":"NEEDS_AGENT_SELECTION",
                "mapped_candidates",mapped.stream().map(GetRepairCandidatesTool::brief).toList(),"allowed_candidates",permitted.stream().map(GetRepairCandidatesTool::brief).toList(),
                "reference_cases",cases,"minimum_review_count",3,"agent_confidence_threshold",0.5,"product_confirmed",product!=null,
                "case_filter_notes",List.of("VALIDATION 제외","부품 코드들은 그룹 추정용이며 국내 실물 부품 호환을 확정하지 않음"));
        return support.result(s,permitted.isEmpty()&&cases.isEmpty()?"NO_DATA":"OK",data,List.of(costEvidence()),basis);
    }
    /** Parts of the consultation category that estimate_repair_cost may accept. */
    public List<Map<String,Object>> allowedParts(ConsultationService.Snapshot s){return catalog.parts.stream().filter(p->s.category().name().equals(text(p,"category"))).toList();}
    /** Binds a candidate set to the conversation, product revision (0 when unregistered) and data version so stale sets are rejected. */
    public String candidateSet(ConsultationService.Snapshot s,UserProduct product){
        String bound=product==null?"0:0":product.getId()+":"+product.getRevision();
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((s.id()+":"+s.version()+":"+bound+":"+catalog.version).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException("후보 묶음을 만들 수 없습니다.");}
    }
    private static Map<String,Object> brief(Map<String,Object> part){
        return map("id",part.get("id"),"part_code",part.get("id"),"name",part.get("name"),"category",part.get("category"),"work_grade",part.get("workGrade"),"is_core",part.get("isCore"));
    }
    static Map<String,Object> costEvidence(){return evidence("COST_V3","NORMALIZED_ESTIMATE","첨부 한국기준_예상수리비_표_v3.csv","2026-10-08");}
}
