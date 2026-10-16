package com.project.backend.mcp.tool.handler;

import static com.project.backend.mcp.tool.ToolDataCatalog.*;
import static com.project.backend.mcp.tool.ToolSupport.*;
import java.util.*;
import org.springframework.stereotype.Component;
import com.project.backend.conversation.ProductCategory;
import com.project.backend.mcp.consultation.ConsultationService;
import com.project.backend.mcp.tool.*;

/** Exact sales-code lookup before product registration. Suffixes are never cut and similar codes are never suggested. */
@Component
public class LookupModelTool implements HomeCareTool {
    private final ToolSupport support;
    private final ToolDataCatalog catalog;
    public LookupModelTool(ToolSupport support,ToolDataCatalog catalog){this.support=support;this.catalog=catalog;}
    public String name(){return "lookup_model";}
    public Set<String> arguments(){return Set.of("conversation_id","model_code");}
    public ToolEnvelope execute(String email,ConsultationService.Snapshot s,Map<String,Object> args){
        String code=support.modelArgument(email,s,args);
        var basis=Basis.of("UNDETERMINED",List.of(MODELS),"판매 코드가 정확히 같을 때만 연결합니다. 접미사를 자르거나 비슷한 코드로 바꾸지 않습니다.",
                "대표 모델은 공통 자료 조회용이며 부품 호환이나 무상 보증을 뜻하지 않습니다.");
        if(!(catalog.models.get(code) instanceof Map<?,?> entry))
            return support.result(s,"NO_DATA",map("input_code",args.get("model_code"),"normalized_code",code,"match_status","NOT_FOUND"),List.of(),basis,
                    "등록된 판매 코드와 정확히 일치하지 않습니다. 제품 라벨의 모델명을 다시 확인해 주세요.");
        var category=ProductCategory.valueOf(text(entry,"category"));
        boolean common=Boolean.TRUE.equals(entry.get("reuse_approved_common_data_only"));
        var data=map("input_code",args.get("model_code"),"normalized_code",code,"match_status","EXACT",
                "sales_model_code",entry.get("sales_model_code"),"representative_model_code",entry.get("representative_model_code"),
                "common_data_lookup_model_code",common?entry.get("common_data_lookup_model_code"):null,
                "family_id",entry.get("family_id"),"category",category,"category_label",category.getLabel(),
                "release_month",entry.get("release_month"),"is_color_alias",entry.get("is_color_alias"),
                "manual_ids",entry.containsKey("manual_ids")?entry.get("manual_ids"):List.of(),"category_matches_consultation",category==s.category(),
                "parts_lookup_model_code",entry.get("part_number_overrides_lookup_model_code"));
        var evidence=List.of(evidence("MODEL_"+code,"OFFICIAL_PRODUCT",text(entry,"official_support_url"),"2026-10-11"));
        var exact=new Basis("HIGH",basis.files(),basis.caveats(),List.of());
        return category==s.category()?support.result(s,"OK",data,evidence,exact)
                :support.result(s,"OK",data,evidence,exact,"상담 품목("+s.category().getLabel()+")과 모델 품목("+category.getLabel()+")이 다릅니다. 제품을 다시 확인해 주세요.");
    }
}
