package com.project.backend.mcp.tool;

import static com.project.backend.mcp.tool.ToolDataCatalog.map;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.project.backend.conversation.ProductCategory;
import com.project.backend.mcp.consultation.ConsultationService;
import com.project.backend.product.*;

/** Envelope, evidence and argument helpers shared by every tool handler. */
@Component
public class ToolSupport {
    public static final String KNOWLEDGE="homecare/knowledge.json",MODELS="homecare/models.json",PARTS="homecare/parts.json",
            CENTERS="homecare/centers.json",ROUTE="homecare/route.json",PLANS="homecare/approved-plans.json",POLICIES="warranty/policies.json";
    /** Data interpretation rule: every result is a candidate, never an approval. */
    public static final String CANDIDATE_CAVEAT="도구 결과는 판단을 돕는 후보 정보이며 조작·무상 보증·부품 장착을 승인하지 않습니다.";
    private static final Pattern MODEL_CODE=Pattern.compile("[A-Z0-9./-]{1,40}");
    private static final Map<String,ProductCategory> CATEGORY_NAMES=Map.of("세탁기",ProductCategory.WASHER,"건조기",ProductCategory.DRYER,
            "냉장고",ProductCategory.REFRIGERATOR,"김치냉장고",ProductCategory.REFRIGERATOR,"식기세척기",ProductCategory.DISHWASHER);
    /** Confidence, data files, fixed caveats and missing inputs of one result. */
    public record Basis(String confidence,List<String> files,List<String> caveats,List<String> missing){
        public static Basis of(String confidence,List<String> files,String... caveats){return new Basis(confidence,files,List.of(caveats),List.of());}
        public Basis missing(String... fields){return new Basis(confidence,files,caveats,List.of(fields));}
    }
    private final UserProductService products;
    private final ToolDataCatalog catalog;
    public ToolSupport(UserProductService products,ToolDataCatalog catalog){this.products=products;this.catalog=catalog;}
    public ToolEnvelope result(ConsultationService.Snapshot s,String status,Map<String,Object> data,List<Map<String,Object>> evidence,Basis basis,String... warnings){
        var caveats=new ArrayList<String>();caveats.add(CANDIDATE_CAVEAT);caveats.addAll(basis.caveats());
        var facts=evidence.stream().map(e->Objects.toString(e.get("evidence_id"))).distinct().toList();
        return new ToolEnvelope(status,data,evidence,basis.missing(),List.of(warnings),null,
                map("contract_version","3.0.0","data_version",catalog.version,"context_version",s.version(),"trace_id",UUID.randomUUID().toString()),
                map("data_version",catalog.version,"files",basis.files(),"fact_ids",facts),basis.confidence(),List.copyOf(caveats),false);
    }
    public ToolEnvelope needsProduct(ConsultationService.Snapshot s){
        return result(s,"NEEDS_INPUT",map("interaction","CONFIRM_PRODUCT"),List.of(),Basis.of("UNDETERMINED",List.of()).missing("product_id"),"마이페이지의 등록 제품 정보를 확인하고, 여러 제품이 있으면 표시 이름이나 모델명을 대화로 알려 주세요.");
    }
    public UserProduct product(String email,ConsultationService.Snapshot s){return s.productId()==null?null:products.owned(email,s.productId());}
    public static Map<String,Object> evidence(String id,String type,String source,String date){return map("evidence_id",id,"source_type",type,"source_ref",source,"base_date",date,"applicability_status","CONDITIONAL");}
    public static Long number(Map<String,Object> args,String key){Object v=args.get(key);if(!(v instanceof Number)||((Number)v).longValue()<1||((Number)v).doubleValue()!=((Number)v).longValue())bad("상담 ID를 확인해 주세요.");return ((Number)v).longValue();}
    /** Registration rule: keep the original input; only trim outer spaces and upper-case. Never cut SKU suffixes. */
    public static String model(String input){return input==null?"":input.strip().toUpperCase(Locale.ROOT);}
    /** Validated model_code argument. Only a code the customer typed in this consultation or registered may be used. */
    public String modelArgument(String email,ConsultationService.Snapshot s,Map<String,Object> args){
        Object raw=args.get("model_code");
        if(!(raw instanceof String))bad("INVALID_ARGUMENT: 모델 코드를 확인해 주세요.");
        String code=model((String)raw);
        if(!MODEL_CODE.matcher(code).matches())bad("INVALID_ARGUMENT: 모델 코드는 영문·숫자와 . - / 로 된 1~40자입니다.");
        var product=product(email,s);
        // Prompt-injection guard: a code that appears only in documents or tool results cannot be looked up.
        if(!(product!=null&&code.equals(model(product.warranty().modelName())))&&!mentions(s.input(),code))
            bad("MODEL_CODE_NOT_IN_INPUT: 고객이 입력하거나 등록한 모델 코드만 조회합니다.");
        return code;
    }
    /** The model_code argument when given, otherwise the registered product model, otherwise null. */
    public String consultationModel(String email,ConsultationService.Snapshot s,Map<String,Object> args){
        if(args.get("model_code")!=null)return modelArgument(email,s,args);
        var product=product(email,s);
        String code=product==null?"":model(product.warranty().modelName());
        return code.isEmpty()?null:code;
    }
    /** True when the customer's text contains the whole code; a prefix of a longer SKU such as RD21ESE.AKOR does not count. */
    static boolean mentions(String input,String code){
        return input!=null&&Pattern.compile("(?<![A-Z0-9./-])"+Pattern.quote(code)+"(?![A-Z0-9/-]|\\.[A-Z0-9])")
                .matcher(input.toUpperCase(Locale.ROOT)).find();
    }
    /** Optional product_category argument (Korean name or enum). It may only restate the consultation category. */
    public static void category(ConsultationService.Snapshot s,Map<String,Object> args){
        Object raw=args.get("product_category");if(raw==null)return;
        if(!(raw instanceof String text))throw invalid("INVALID_ARGUMENT: 품목을 확인해 주세요.");
        ProductCategory value=CATEGORY_NAMES.get(text.strip());
        if(value==null)try{value=ProductCategory.valueOf(text.strip());}catch(IllegalArgumentException e){throw invalid("INVALID_ARGUMENT: 지원하지 않는 품목입니다.");}
        if(value!=s.category())bad("CATEGORY_MISMATCH: 상담 품목과 다른 품목입니다.");
    }
    public static String confidenceOf(String grade){
        return switch(Objects.toString(grade,"")){case "높음"->"HIGH";case "중간"->"MEDIUM";case "낮음"->"LOW";default->"UNDETERMINED";};
    }
    public static void bad(String message){throw invalid(message);}
    private static ResponseStatusException invalid(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
