package com.project.backend.mcp.tool.handler;

import static com.project.backend.mcp.tool.ToolDataCatalog.*;
import static com.project.backend.mcp.tool.ToolSupport.*;
import java.util.*;
import org.springframework.stereotype.Component;
import com.project.backend.mcp.consultation.ConsultationService;
import com.project.backend.mcp.tool.*;

/**
 * Candidate manual facts for the consultation model. Safety notices come first; excluded or other-model records are
 * dropped; records without a model list are labelled UNDETERMINED; supplements stay separate from the original procedure.
 */
@Component
public class SearchKnowledgeTool implements HomeCareTool {
    private static final Map<String,Integer> SHOWN=Map.of("MODEL_LISTED",0,"CONDITIONAL",1,"UNDETERMINED",2);
    private static final String PARTICLES="가이은는을를에도요서";
    private record Hit(Map<String,Object> doc,String status,int score){ }
    private final ToolSupport support;
    private final ToolDataCatalog catalog;
    public SearchKnowledgeTool(ToolSupport support,ToolDataCatalog catalog){this.support=support;this.catalog=catalog;}
    public String name(){return "search_knowledge";}
    public Set<String> arguments(){return Set.of("conversation_id","query","topic","top_k","model_code");}
    public ToolEnvelope execute(String email,ConsultationService.Snapshot s,Map<String,Object> args){
        String query=Objects.toString(args.get("query"),Objects.toString(s.input(),""));
        if(query.isBlank()||query.length()>300)bad("검색어는 1~300자입니다.");
        int top=args.get("top_k") instanceof Number n?n.intValue():3;if(top<1||top>5)bad("top_k는 1~5입니다.");
        String topic=Objects.toString(args.get("topic"),"MANUAL");if(!List.of("MANUAL","WARRANTY","SAFETY","SERVICE").contains(topic))bad("검색 주제를 확인해 주세요.");
        String model=support.consultationModel(email,s,args),common=commonDataModel(model);
        final String q=query.toLowerCase(Locale.ROOT);
        var hits=catalog.knowledge.stream().filter(d->s.category().name().equals(text(d,"category")))
                .map(d->new Hit(d,catalog.overlays.get(text(d,"id")).status(model,common),score(d,q,s.state().errorCode()))).filter(h->h.score()>0).toList();
        Comparator<Hit> order=Comparator.comparingInt(Hit::score).reversed().thenComparing(h->SHOWN.getOrDefault(h.status(),9));
        // Safety notices apply to the whole category unless this exact model is excluded.
        var safety=hits.stream().filter(h->"SAFETY".equals(text(h.doc(),"topic"))&&!"EXCLUDED".equals(h.status())).sorted(order).limit(2).toList();
        var docs=hits.stream().filter(h->topic.equals(text(h.doc(),"topic"))&&SHOWN.containsKey(h.status())&&!safety.contains(h)).sorted(order).limit(top).toList();
        var evidence=new ArrayList<Map<String,Object>>();
        for(var h:safety)evidence.add(source(h));for(var h:docs)evidence.add(source(h));
        String confidence=docs.stream().anyMatch(h->h.status().equals("MODEL_LISTED"))?"MEDIUM":docs.isEmpty()&&safety.isEmpty()?"UNDETERMINED":"LOW";
        var basis=Basis.of(confidence,List.of(KNOWLEDGE,MODELS),"모델 목록에 있어도 조작·무상 보증·부품 장착을 승인하지 않습니다.",
                "모델 목록이 없는 문서는 '적용 미판정'이며 모든 모델에 적용된다는 뜻이 아닙니다.",
                "절차 보완 자료는 원래 절차를 대체하지 않습니다. 서로 다른 원인·부품·세척 재료의 절차를 섞지 않습니다.");
        var warnings=new ArrayList<String>();
        if(!safety.isEmpty())warnings.add("안전 주의 문서가 있습니다. 다른 안내보다 먼저 확인하세요.");
        if(model==null){warnings.add("모델을 확인하지 못해 모델별 문서는 판정하지 않았습니다. 제품 라벨의 모델명을 알려 주세요.");basis=basis.missing("model_code");}
        var data=map("safety_notices",safety.stream().map(this::view).toList(),"documents",docs.stream().map(this::view).toList(),
                "model_code",model,"model_filter_status",model==null?"MODEL_UNCONFIRMED":"EXACT","retrieval_version",catalog.version);
        return support.result(s,docs.isEmpty()&&safety.isEmpty()?"NO_DATA":"OK",data,evidence,basis,warnings.toArray(String[]::new));
    }
    /** Common data of a color-family representative may be reused only when the model entry says so. */
    private String commonDataModel(String model){
        if(model==null||!(catalog.models.get(model) instanceof Map<?,?> entry)||!Boolean.TRUE.equals(entry.get("reuse_approved_common_data_only")))return null;
        return Objects.toString(entry.get("common_data_lookup_model_code"),null);
    }
    private Map<String,Object> source(Hit h){
        var urls=strings(h.doc(),"sourceUrls");
        var value=evidence(text(h.doc(),"id"),"OFFICIAL_MANUAL",urls.isEmpty()?KNOWLEDGE:urls.getFirst(),text(h.doc(),"baseDate"));
        value.put("applicability_status",h.status());return value;
    }
    private Map<String,Object> view(Hit h){
        var d=h.doc();String id=text(d,"id");var overlay=catalog.overlays.get(id);
        var supplements=overlay.supplementIds().stream().map(catalog.knowledgeById::get).filter(Objects::nonNull)
                .map(x->map("document_id",text(x,"id"),"title",x.get("title"),"content",x.get("text"),"relation","PROCEDURE_SUPPLEMENT")).toList();
        return map("document_id",id,"title",d.get("title"),"record_type",d.get("recordType"),"topic",d.get("topic"),"content",d.get("text"),
                "evidence_ids",List.of(id),"applicability_status",h.status(),"applicability_basis",overlay.basis(),
                "has_qa_review",overlay.qaReview(),"has_applicability_update",overlay.applicabilityUpdate(),
                "conditions",d.getOrDefault("conditions",List.of()),"supplements",supplements,"is_action_approved",false);
    }
    private int score(Map<String,Object> d,String query,String error){
        int value=error!=null&&strings(d,"errorCodes").contains(error)?100:0;
        String title=text(d,"title").toLowerCase(Locale.ROOT),body=text(d,"text").toLowerCase(Locale.ROOT);
        for(var raw:query.split("[^\\p{L}\\p{N}]+")){
            if(raw.length()<2)continue;
            // "배수가" also matches "배수": drop one trailing Korean particle when the stem keeps two letters.
            String token=body.contains(raw)||title.contains(raw)||raw.length()<3||PARTICLES.indexOf(raw.charAt(raw.length()-1))<0?raw:raw.substring(0,raw.length()-1);
            if(title.contains(token))value+=8;if(body.contains(token))value++;
        }
        return value;
    }
}
