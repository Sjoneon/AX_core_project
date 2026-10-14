package com.project.backend.mcp.tool.handler;

import static com.project.backend.mcp.tool.ToolDataCatalog.*;
import static com.project.backend.mcp.tool.ToolSupport.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.project.backend.mcp.consultation.ConsultationService;
import com.project.backend.mcp.tool.*;
import com.project.backend.mcp.retrieval.KnowledgeRetriever;

/**
 * Candidate manual facts for the consultation model. Safety notices come first; excluded or other-model records are
 * dropped; records without a model list are labelled UNDETERMINED; supplements stay separate from the original procedure.
 */
@Component
public class SearchKnowledgeTool implements HomeCareTool {
    private final ToolSupport support;
    private final ToolDataCatalog catalog;
    private final KnowledgeRetriever retriever;
    @Autowired public SearchKnowledgeTool(ToolSupport support,ToolDataCatalog catalog,KnowledgeRetriever retriever){this.support=support;this.catalog=catalog;this.retriever=retriever;}
    public SearchKnowledgeTool(ToolSupport support,ToolDataCatalog catalog){this(support,catalog,KnowledgeRetriever.lexical(catalog));}
    public String name(){return "search_knowledge";}
    public Set<String> arguments(){return Set.of("conversation_id","query","topic","top_k","model_code");}
    public ToolEnvelope execute(String email,ConsultationService.Snapshot s,Map<String,Object> args){
        String query=Objects.toString(args.get("query"),s.state().searchQuery(s.input()));
        if(query.isBlank()||query.length()>300)bad("검색어는 1~300자입니다.");
        int top=args.get("top_k") instanceof Number n?n.intValue():3;if(top<1||top>5)bad("top_k는 1~5입니다.");
        String topic=Objects.toString(args.get("topic"),"MANUAL");if(!List.of("MANUAL","WARRANTY","SAFETY","SERVICE").contains(topic))bad("검색 주제를 확인해 주세요.");
        String model=support.consultationModel(email,s,args);
        var found=retriever.retrieve(query,s.category().name(),model,topic,s.state().errorCode(),top);
        var safety=found.safety();var docs=found.documents();
        var evidence=new ArrayList<Map<String,Object>>();
        for(var h:safety)evidence.add(source(h));for(var h:docs)evidence.add(source(h));
        String confidence=docs.stream().anyMatch(h->h.applicability().equals("MODEL_LISTED"))?"MEDIUM":docs.isEmpty()&&safety.isEmpty()?"UNDETERMINED":"LOW";
        var basis=Basis.of(confidence,List.of(KNOWLEDGE,"homecare/knowledge-structured.json",MODELS),"모델 목록에 있어도 조작·무상 보증·부품 장착을 승인하지 않습니다.",
                "모델 목록이 없는 문서는 '적용 미판정'이며 모든 모델에 적용된다는 뜻이 아닙니다.",
                "절차 보완 자료는 원래 절차를 대체하지 않습니다. 서로 다른 원인·부품·세척 재료의 절차를 섞지 않습니다.");
        var warnings=new ArrayList<String>();
        if(!safety.isEmpty())warnings.add("안전 주의 문서가 있습니다. 다른 안내보다 먼저 확인하세요.");
        if("LEXICAL_FALLBACK".equals(found.mode()))warnings.add("일부 검색 기능을 사용할 수 없어 확인된 증상·오류 코드에 맞는 자료를 우선 제공합니다.");
        if(docs.isEmpty()&&safety.isEmpty())warnings.add("해당 모델·증상에 맞는 근거가 부족합니다. 증상이나 표시 코드를 추가로 확인해 주세요.");
        if(model==null){warnings.add("모델을 확인하지 못해 모델별 문서는 판정하지 않았습니다. 제품 라벨의 모델명을 알려 주세요.");basis=basis.missing("model_code");}
        var data=map("safety_notices",safety.stream().map(h->view(h,s.category().name(),model)).toList(),"documents",docs.stream().map(h->view(h,s.category().name(),model)).toList(),
                "model_code",model,"model_filter_status",model==null?"MODEL_UNCONFIRMED":"EXACT","retrieval_version",catalog.version,
                "retrieval_algorithm",KnowledgeRetriever.VERSION,"retrieval_mode",found.mode(),"index_status",found.indexStatus(),"retrieval_elapsed_ms",found.elapsedMs());
        return support.result(s,docs.isEmpty()&&safety.isEmpty()?(model==null?"NEEDS_INPUT":"NO_DATA"):"OK",data,evidence,basis,warnings.toArray(String[]::new));
    }
    private Map<String,Object> source(KnowledgeRetriever.Hit h){
        var source=catalog.knowledgeById.get(h.id());var urls=strings(source,"sourceUrls");
        var value=evidence(h.id(),"OFFICIAL_MANUAL",urls.isEmpty()?KNOWLEDGE:urls.getFirst(),text(source,"baseDate"));
        value.put("applicability_status",h.applicability());return value;
    }
    private Map<String,Object> view(KnowledgeRetriever.Hit h,String category,String model){
        var d=catalog.knowledgeById.get(h.id());String id=h.id();var overlay=catalog.overlays.get(id);
        var supplements=overlay.supplementIds().stream().filter(link->retriever.eligible(link,category,model)).map(catalog.knowledgeById::get)
                .map(x->{var value=catalog.structuredKnowledgeById.get(text(x,"id")).view();
                    value.putAll(map("document_id",text(x,"id"),"title",catalog.structuredKnowledgeById.get(text(x,"id")).title(),
                            "applicability_status",retriever.applicability(text(x,"id"),model),"relation","PROCEDURE_SUPPLEMENT"));return value;}).toList();
        var value=catalog.structuredKnowledgeById.get(id).view();
        value.putAll(map("document_id",id,"title",catalog.structuredKnowledgeById.get(id).title(),"record_type",d.get("recordType"),"topic",d.get("topic"),
                "evidence_ids",List.of(id),"applicability_status",h.applicability(),"applicability_basis",overlay.basis(),
                "scope_basis",retriever.verifiedCommon(id)?"VERIFIED_CATEGORY_COMMON":"MODEL_LIST",
                "retrieval_match",h.match(),"semantic_similarity",h.similarity(),
                "has_qa_review",overlay.qaReview(),"has_applicability_update",overlay.applicabilityUpdate(),
                "conditions",catalog.structuredKnowledgeById.get(id).preconditions(),"supplements",supplements,"is_action_approved",false));
        return value;
    }
}
