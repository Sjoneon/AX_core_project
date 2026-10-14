package com.project.backend.mcp.tool.handler;

import static com.project.backend.mcp.tool.ToolDataCatalog.map;
import static com.project.backend.mcp.tool.ToolSupport.model;
import java.util.*;
import org.springframework.stereotype.Component;
import com.project.backend.mcp.consultation.ConsultationService;
import com.project.backend.mcp.tool.*;
import com.project.backend.product.UserProductService;

@Component
public class GetProductContextTool implements HomeCareTool {
    private final ToolSupport support;
    private final UserProductService products;
    private final ToolDataCatalog catalog;
    public GetProductContextTool(ToolSupport support,UserProductService products,ToolDataCatalog catalog){this.support=support;this.products=products;this.catalog=catalog;}
    public String name(){return "get_product_context";}
    public ToolEnvelope execute(String email,ConsultationService.Snapshot s,Map<String,Object> args){
        var list=products.list(email).stream().filter(p->p.warranty().category()==s.category()).limit(20).map(this::brief).toList();
        var selected=support.product(email,s);
        var basis=ToolSupport.Basis.of(selected==null?"UNDETERMINED":"HIGH",List.of(ToolSupport.MODELS),"등록 제품은 고객이 직접 선택한 제품만 상담에 사용합니다. 자동으로 고르지 않습니다.");
        return support.result(s,selected==null?"NEEDS_INPUT":"OK",map("candidates",list,"selected_product",selected==null?null:brief(products.view(selected)),"product_confirmed",selected!=null,"confirmation_required",selected==null,"next_interaction",selected!=null?null:list.isEmpty()?"REGISTER_PRODUCT":"CONFIRM_PRODUCT"),List.of(),
                selected==null?basis.missing("product_id"):basis);
    }
    private Map<String,Object> brief(UserProductService.View p){return map("product_id",p.id(),"category",p.warranty().category(),"display_name",p.displayName(),"model_name",p.warranty().modelName(),"purchase_date",p.warranty().purchaseDate(),"delivery_region",map("sido",p.deliverySido(),"sigungu",p.deliverySigungu()),"model_match_status",catalog.models.containsKey(model(p.warranty().modelName()))?"EXACT":"UNREGISTERED");}
}
