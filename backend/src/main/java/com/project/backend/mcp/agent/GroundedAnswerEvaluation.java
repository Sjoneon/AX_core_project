package com.project.backend.mcp.agent;

import static java.util.Objects.requireNonNull;

import static com.project.backend.mcp.tool.ToolDataCatalog.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.*;
import com.project.backend.conversation.ProductCategory;
import com.project.backend.data.DataManifest;
import com.project.backend.mcp.consultation.*;
import com.project.backend.mcp.retrieval.KnowledgeRetriever;
import com.project.backend.mcp.tool.*;
import com.project.backend.warranty.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Explicit synthetic answer evaluation: canonical local facts and real model; no user DB, MCP or app restart. */
public final class GroundedAnswerEvaluation {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public static void main(String[] args)throws Exception{
        Path root=Path.of("..").toAbsolutePath().normalize();var env=Dotenv.configure().ignoreIfMissing().load();
        String key=env.get("OPENAI_API_KEY"),modelName=env.get("OPENAI_MODEL");
        if(key==null||key.isBlank()||modelName==null||modelName.isBlank())throw new IllegalStateException("Existing model configuration is required");
        var options=OpenAiChatOptions.builder().model(modelName).apiKey(key).timeout(Duration.ofSeconds(5)).maxRetries(0).build();
        var client=ChatClient.create(OpenAiChatModel.builder().options(options).build());
        var rawDraft=new java.util.concurrent.atomic.AtomicReference<String>();
        var generator=new GroundedAnswerGenerator(input->{String draft=client.prompt().system(GroundedAnswerGenerator.SYSTEM).user(requireNonNull(input)).call().content();rawDraft.set(draft);return draft;},Duration.ofSeconds(5));
        var catalog=new ToolDataCatalog(DataManifest.classpath());var retrieval=KnowledgeRetriever.lexical(catalog);var builder=new AnswerContext.Builder(catalog);
        var warranty=new WarrantyService(new WarrantyCatalog(),Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        Map<String,Object> specification=JSON.readValue(Files.readString(root.resolve("docs/mcp/answer_evaluation.json")),new TypeReference<>(){});
        var rows=new ArrayList<Map<String,Object>>();
        for(var raw:(List<?>)specification.get("cases")){
            var test=requireNonNull((Map<?,?>)raw);var category=ProductCategory.valueOf(text(test,"category"));String model=text(test,"model"),query=text(test,"query");
            String symptom=Objects.toString(test.get("symptom"),null);String kind=Objects.toString(test.get("kind"),"manual");
            var state=new ConsultationState("CLEAR",null,0,null,symptom,List.of(),0,false);
            var snapshot=new ConsultationService.Snapshot(1L,category,2L,query,state,3);
            var tools=new LinkedHashMap<String,ToolEnvelope>();String fixed;
            if(kind.equals("warranty")){
                var result=warranty.check(new WarrantyModels.Request(category,model,LocalDate.of(2024,1,1),null,null,null,"GENERAL",true,true));
                tools.put("check_warranty",ToolEnvelope.policy("OK",Map.of(),map("context_version",3),List.of()));fixed=warranty.explain(result);
            }else if(kind.equals("cost")){
                var part=catalog.parts.stream().filter(p->text(p,"id").equals(test.get("part"))).findFirst().orElseThrow();
                fixed=String.format(Locale.ROOT,"참고용 예상 수리비(견적 아님): %,d ~ %,d원, 가운데 값 %,d원. 실제 비용과 보증 적용은 공식 점검 후 결정됩니다.",part.get("empiricalMin"),part.get("empiricalMax"),part.get("empiricalMedian"));
                tools.put("estimate_repair_cost",ToolEnvelope.policy("PARTIAL",Map.of(),map("context_version",3),List.of()));
            }else{
                var found=retrieval.retrieve(query,category.name(),model,"MANUAL",null,3);
                var documents=found.documents().stream().map(h->map("document_id",h.id(),"applicability_status",h.applicability())).toList();
                var evidence=found.documents().stream().map(h->map("evidence_id",h.id())).toList();
                tools.put("search_knowledge",new ToolEnvelope(documents.isEmpty()?"NO_DATA":"OK",map("model_code",model,"documents",documents),evidence,List.of(),List.of(),null,map("context_version",3,"data_version",catalog.version),Map.of(),"LOW",List.of(),false));
                fixed="현재 모델에서 직접 실행할 조치가 확인되지 않았습니다. 원문 근거와 공식 상담 경로를 확인해 주세요.";
            }
            rawDraft.set(null);var context=builder.build(snapshot,model,tools,fixed);var result=generator.generate(context,Duration.ofSeconds(20),fixed);
            boolean passed="MODEL_GROUNDED".equals(result.mode())&&result.reply().contains(fixed);
            if(Boolean.TRUE.equals(test.get("reference")))passed&=context.facts().stream().anyMatch(f->f.kind().equals("REFERENCE"))&&!result.evidenceIds().isEmpty();
            if(Boolean.TRUE.equals(test.get("no_reference")))passed&=result.reply().contains("확인된 설명 근거가 부족");
            if(test.get("expected_text") instanceof String expected)passed&=result.reply().contains(expected);
            if(test.get("forbidden") instanceof List<?> forbidden)for(var phrase:forbidden)passed&=!result.reply().contains(requireNonNull(phrase).toString());
            rows.add(map("id",test.get("id"),"kind",kind,"category",category,"model",model,"query",query,"facts",context.facts(),"result",result,"passed",passed));
            if(!passed)rows.getLast().put("synthetic_invalid_draft",rawDraft.get());
            System.out.println("Grounded answer "+rows.size()+": "+test.get("id")+" "+(passed?"PASS":"FAIL")+" "+result.mode());
        }
        long passed=rows.stream().filter(row->Boolean.TRUE.equals(row.get("passed"))).count();
        Files.writeString(root.resolve("docs/benchmarks/grounded_answer_20261014.json"),JSON.writeValueAsString(map("evaluated_date","2026-10-14","model",modelName,"real_model",true,"generation_limit_ms",5000,"total",rows.size(),"passed",passed,"cases",rows))+"\n");
        System.out.println("Real grounded answer evaluation: "+passed+"/"+rows.size());
        if(passed!=rows.size())throw new IllegalStateException("Review the grounded answer report");
    }
    private GroundedAnswerEvaluation(){ }
}
