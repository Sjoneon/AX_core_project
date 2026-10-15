package com.project.backend.mcp.agent;

import static com.project.backend.mcp.tool.ToolDataCatalog.*;
import java.util.*;
import java.util.regex.Pattern;
import com.project.backend.mcp.consultation.*;
import com.project.backend.mcp.retrieval.KnowledgeRetriever;
import com.project.backend.mcp.tool.*;

/** Immutable, checked facts for answer composition. Raw manuals and execution candidates are never prompts. */
public record AnswerContext(List<Fact> facts) {
    public record Fact(String id,String kind,String text,List<String> evidenceIds,boolean required) { }
    public AnswerContext { facts=List.copyOf(facts); }
    public static final class InvalidEvidence extends RuntimeException {
        public InvalidEvidence(){super("INVALID_EVIDENCE");}
    }
    private static final Pattern INSTRUCTION=Pattern.compile("(?is)(ignore.{0,30}(instruction|rule)|system\\s*:|assistant\\s*:|이전.{0,15}지시.{0,15}무시|규칙.{0,10}무시|역할.{0,10}변경|\\{\\{|<[/!]?)");
    private static final Pattern ACTION=Pattern.compile("분해|교체|제거하|설치하|조정하|청소하|누르|돌리세요|맞추세요|하세요|하십시오|해보세요");
    /** Constructed once per Agent; reuses retrieval's exact model/category/exclusion policy. */
    public static final class Builder {
        private final ToolDataCatalog catalog;
        private final KnowledgeRetriever policy;
        public Builder(ToolDataCatalog catalog){this.catalog=catalog;this.policy=KnowledgeRetriever.lexical(catalog);}
        public AnswerContext build(ConsultationService.Snapshot s,String selectedModel,Map<String,ToolEnvelope> outputs,String fixedReply){
            var facts=new ArrayList<Fact>();
            for(var result:outputs.values()){
                if(result.meta()==null||!(result.meta().get("context_version") instanceof Number version)||version.longValue()!=s.version())throw new InvalidEvidence();
                if(result.meta().get("data_version")!=null&&!catalog.version.equals(result.meta().get("data_version")))throw new InvalidEvidence();
            }
            String symptom=SymptomCatalog.label(s.category(),s.state().symptomCode());
            if(!symptom.isBlank()){
                String timing=s.state().dialogue().answers().get("VIBRATION_WHEN");
                String when=Map.of("SPIN","탈수할 때","WASH","세탁할 때","ALWAYS","항상").getOrDefault(Objects.toString(timing,""),"");
                facts.add(new Fact("SYMPTOM","CONFIRMED","상담에서 확인한 증상은 ‘"+symptom+"’"+(when.isBlank()?"":"이며 발생 시점은 ‘"+when+"’")+"입니다.",List.of(),true));
            }
            var search=outputs.get("search_knowledge");
            String model=selectedModel==null?null:ToolSupport.model(selectedModel);
            if(search!=null&&search.data()!=null&&search.data().get("model_code") instanceof String searched){
                if(model!=null&&!ToolSupport.model(model).equals(searched))throw new InvalidEvidence();
                if(model==null&&!Pattern.compile("(?i)(?<![A-Z0-9./-])"+Pattern.quote(searched)+"(?![A-Z0-9./-])").matcher(s.input()).find())throw new InvalidEvidence();
                model=searched;
            }
            var diagnostic=outputs.get("get_diagnostic_plan");
            if(diagnostic!=null&&diagnostic.data()!=null&&diagnostic.data().get("steps") instanceof List<?> steps&&!steps.isEmpty()){
                if(!"OK".equals(diagnostic.status())||s.productId()==null||model==null)throw new InvalidEvidence();
                if(!steps.equals(DiagnosticPlans.pendingSteps(catalog,s,model)))throw new InvalidEvidence();
            }
            var seen=new HashSet<String>();
            for(String name:List.of("search_knowledge","get_diagnostic_plan")){
                var tool=outputs.get(name);if(tool==null||tool.data()==null)continue;
                String key=name.equals("search_knowledge")?"documents":"references";
                if(tool.data().get(key) instanceof List<?> documents)for(var raw:documents){
                    if(!(raw instanceof Map<?,?> document)||!(document.get("document_id") instanceof String id))throw new InvalidEvidence();
                    if(!seen.add(id))continue;
                    if(!catalog.knowledgeById.containsKey(id)||!policy.eligible(id,s.category().name(),model))throw new InvalidEvidence();
                    String status=policy.applicability(id,model);
                    if(!Objects.equals(status,document.get("applicability_status"))||tool.evidence().stream().noneMatch(e->id.equals(e.get("evidence_id"))))throw new InvalidEvidence();
                    var source=catalog.knowledgeById.get(id);
                    if(!"MANUAL".equals(source.get("topic")))continue; // Warranty and fees come only from their domain tools.
                    var projection=catalog.structuredKnowledgeById.get(id);
                    var descriptions=strings(projection.fields(),"symptoms");
                    var causes=new ArrayList<>(strings(projection.fields(),"causes"));
                    // Branch conditions describe possibilities; action_summary and feature-gated claims are excluded.
                    if(projection.fields().get("branches") instanceof Map<?,?> branches&&branches.get("action_branches") instanceof List<?> list)
                        for(var branch:list)if(branch instanceof Map<?,?> b&&b.get("feature_gate")==null&&b.get("condition_summary") instanceof String condition)causes.add(condition);
                    if(descriptions.isEmpty()&&causes.isEmpty())continue;
                    var text=new ArrayList<String>();
                    if(!descriptions.isEmpty())text.add("제조사 자료에는 ‘"+String.join(" / ",descriptions)+"’ 증상이 설명되어 있어요.");
                    var safeCauses=causes.stream().distinct().filter(c->!ACTION.matcher(c).find()).limit(2).toList();
                    if(!safeCauses.isEmpty())text.add("자료에서 구분하는 원인 후보·상황은 ‘"+String.join(" / ",safeCauses)+"’입니다. 현재 제품의 실제 원인은 아직 확인되지 않았어요.");
                    else text.add("이 자료만으로는 현재 증상의 원인을 특정할 수 없어 원인 확인을 위한 공식 점검이 필요합니다.");
                    if(!projection.preconditions().isEmpty())text.add("자료의 적용 조건(현재 확인되지 않음): "+String.join(" / ",projection.preconditions().stream().map(Builder::customerCondition).toList()));
                    if("CONDITIONAL".equals(status))text.add("이 자료는 모델 적용 조건을 추가로 확인해야 하는 후보입니다.");
                    else if("UNDETERMINED".equals(status))text.add("검증된 품목 공통 자료이며 이 제품 모델의 직접 적용은 확정되지 않았어요.");
                    text.add("이 설명은 고장 확정이나 직접 실행할 조치의 승인이 아닙니다.");
                    var urls=strings(source,"sourceUrls");if(!urls.isEmpty())text.add("[제조사 설명서]("+urls.getFirst()+")");
                    String body=String.join(" ",text);if(INSTRUCTION.matcher(body).find())throw new InvalidEvidence();
                    if(facts.stream().filter(f->f.kind().equals("REFERENCE")).count()<3)facts.add(new Fact("REFERENCE_"+(facts.size()+1),"REFERENCE",body,List.of(id),false));
                }
            }
            if(search!=null&&(facts.stream().noneMatch(f->f.kind().equals("REFERENCE"))))
                facts.add(new Fact("NO_EVIDENCE","MISSING","현재 제품·증상에 적용할 수 있는 확인된 설명 근거가 부족해요. 원인이나 조치 방법을 임의로 정하지 않고 추가 확인이 필요합니다.",List.of(),true));
            var toolEvidence=outputs.values().stream().flatMap(t->t.evidence().stream()).map(e->Objects.toString(e.get("evidence_id"),"")).filter(id->!id.isBlank()).distinct().toList();
            if(!fixedReply.isBlank())facts.add(new Fact("TOOL_GUIDANCE","AUTHORITATIVE",fixedReply,toolEvidence,true));
            return new AnswerContext(facts);
        }
        private static String customerCondition(String condition){
            if(condition.contains("모델별 T4 우선"))return "위험 징후가 있으면 조작을 중지하고 해당 모델의 안전 안내를 먼저 확인해야 합니다.";
            if(condition.contains("실제 원인/수치/문장"))return "해당 상황의 원인과 추가 적용 조건을 확인해야 합니다.";
            if(condition.contains("참조 절차는 해당 모델의 검증 절"))return "해당 모델의 공식 설명서에서 적용 조건을 확인해 주세요.";
            return condition;
        }
    }
}
