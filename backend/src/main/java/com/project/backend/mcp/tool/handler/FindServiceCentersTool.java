package com.project.backend.mcp.tool.handler;

import static com.project.backend.mcp.tool.ToolDataCatalog.*;
import static com.project.backend.mcp.tool.ToolSupport.*;
import java.util.*;
import org.springframework.stereotype.Component;
import com.project.backend.mcp.consultation.ConsultationService;
import com.project.backend.mcp.tool.*;
import com.project.backend.product.UserProductService;

/**
 * Official service connection for the registered product's installation region.
 * No center directory is integrated, so the packaged seed centers are never presented as a nationwide lookup:
 * the result is the official on-site booking and center finder, plus whether the region is known.
 */
@Component
public class FindServiceCentersTool implements HomeCareTool {
    private final ToolSupport support;
    private final ToolDataCatalog catalog;
    public FindServiceCentersTool(ToolSupport support,ToolDataCatalog catalog){this.support=support;this.catalog=catalog;}
    public String name(){return "find_service_centers";}
    public Set<String> arguments(){return Set.of("conversation_id","region_override");}
    public ToolEnvelope execute(String email,ConsultationService.Snapshot s,Map<String,Object> args){
        if(s.productId()==null)return support.needsProduct(s);var p=support.product(email,s);
        String sido=Objects.toString(p.getDeliverySido(),""),sigungu=Objects.toString(p.getDeliverySigungu(),""),origin="PRODUCT_DELIVERY";
        if(args.get("region_override") instanceof Map<?,?> override){sido=UserProductService.normalizeSido(Objects.toString(override.get("sido"),""));sigungu=Objects.toString(override.get("sigungu"),"");if(sigungu.isBlank()||sigungu.length()>30)bad("지역을 확인해 주세요.");origin="USER_OVERRIDE";}
        boolean known=!sido.isBlank();
        var data=map("service_mode","ON_SITE","directory_status","NOT_INTEGRATED","region_status",known?"KNOWN":"REQUIRED",
                "base_region",map("sido",sido,"sigungu",sigungu),"region_source",origin,"centers",List.of(),
                "channels",map("onsite_booking_url",catalog.route.get("apply_url"),"center_finder_url",catalog.route.get("center_finder_url"),"phone",catalog.route.get("phone")),
                "booking_status","NOT_SUBMITTED","route",catalog.route);
        var evidence=List.of(evidence("LG_SERVICE_ROUTE","OFFICIAL_SERVICE",text(catalog.route,"source"),text(catalog.route,"base_date")));
        var basis=Basis.of(known?"MEDIUM":"UNDETERMINED",List.of(ROUTE),
                "센터 목록은 연동되지 않았습니다. 공식 출장 접수와 공식 센터 찾기로 연결합니다.","이 서비스는 예약·접수를 하지 않습니다. 공식 채널에서 진행하세요.");
        if(!known){
            data.put("interaction","CONFIRM_REGION");
            return support.result(s,"NEEDS_INPUT",data,evidence,basis.missing("delivery_region"),"설치 지역이 저장되어 있지 않습니다. 마이페이지에서 주소 검색으로 설치 주소를 다시 저장해 주세요.");
        }
        return support.result(s,"OK",data,evidence,basis,"공식 출장 접수로 연결합니다. 센터 방문은 공식 센터 찾기에서 접수 가능 제품을 확인하세요.");
    }
}
