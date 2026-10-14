package com.project.backend.mcp.tool.handler;

import static com.project.backend.mcp.tool.ToolDataCatalog.*;
import static com.project.backend.mcp.tool.ToolSupport.*;
import java.util.*;
import org.springframework.stereotype.Component;
import com.project.backend.mcp.consultation.ConsultationService;
import com.project.backend.mcp.tool.*;
import com.project.backend.product.UserProductService;

@Component
public class FindServiceCentersTool implements HomeCareTool {
    private final ToolSupport support;
    private final ToolDataCatalog catalog;
    public FindServiceCentersTool(ToolSupport support,ToolDataCatalog catalog){this.support=support;this.catalog=catalog;}
    public String name(){return "find_service_centers";}
    public Set<String> arguments(){return Set.of("conversation_id","region_override");}
    public ToolEnvelope execute(String email,ConsultationService.Snapshot s,Map<String,Object> args){
        if(s.productId()==null)return support.needsProduct(s);var p=support.product(email,s);
        String sido=p.getDeliverySido(),sigungu=p.getDeliverySigungu(),origin="PRODUCT_DELIVERY";
        if(args.get("region_override") instanceof Map<?,?> override){sido=UserProductService.normalizeSido(Objects.toString(override.get("sido"),""));sigungu=Objects.toString(override.get("sigungu"),"");if(sigungu.isBlank()||sigungu.length()>30)bad("지역을 확인해 주세요.");origin="USER_OVERRIDE";}
        String selectedSido=sido,selectedSigungu=sigungu;
        var exact=catalog.centers.stream().filter(c->sidoMatches(text(c,"sido"),selectedSido)&&text(c,"sigungu").equals(selectedSigungu)).limit(3).toList();
        var fallback=exact.isEmpty()?catalog.centers.stream().filter(c->sidoMatches(text(c,"sido"),selectedSido)).limit(3).toList():exact;
        String level=!exact.isEmpty()?"SIGUNGU":!fallback.isEmpty()?"SIDO":"NONE";
        var evidence=fallback.stream().map(c->evidence("CENTER_"+c.get("id"),"OFFICIAL_SERVICE",text(c,"detail_url"),text(c,"base_date"))).toList();
        var basis=Basis.of(level.equals("SIGUNGU")?"MEDIUM":level.equals("SIDO")?"LOW":"UNDETERMINED",List.of(CENTERS,ROUTE),
                "센터 목록은 같은 지역 기준이며 거리순이 아닙니다.","이 서비스는 예약·접수를 하지 않습니다. 공식 채널에서 진행하세요.");
        return support.result(s,"OK",map("base_region",map("sido",sido,"sigungu",sigungu),"region_source",origin,"fallback_level",level,"centers",fallback,"route",catalog.route),evidence,basis,"같은 지역 센터이며 거리순이 아닙니다. 운영시간·접수 가능 제품은 공식 페이지에서 확인하세요.");
    }
    private boolean sidoMatches(String actual,String requested){return actual.equals(requested);}
}
