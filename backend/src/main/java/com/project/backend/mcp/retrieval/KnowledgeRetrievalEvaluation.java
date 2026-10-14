package com.project.backend.mcp.retrieval;

import static com.project.backend.mcp.tool.ToolDataCatalog.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.ai.openai.*;
import com.project.backend.data.DataManifest;
import com.project.backend.mcp.tool.ToolDataCatalog;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Explicit local preparation/evaluation command. No web server, user database, chat model, or application restart. */
public final class KnowledgeRetrievalEvaluation {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public static void main(String[] args)throws Exception {
        Path root=Path.of("..").toAbsolutePath().normalize();
        var catalog=new ToolDataCatalog(DataManifest.classpath());
        var dotenv=Dotenv.configure().ignoreIfMissing().load();
        String key=dotenv.get("OPENAI_API_KEY");
        if(key==null||key.isBlank())throw new IllegalStateException("기존 OPENAI_API_KEY가 필요합니다.");
        var options=OpenAiEmbeddingOptions.builder().model("text-embedding-3-small").apiKey(key)
                .timeout(Duration.ofSeconds(30)).maxRetries(0).build();
        var model=OpenAiEmbeddingModel.builder().options(options).build();
        try(var vectors=new KnowledgeVectorIndex(catalog,model,true,Path.of(".homecare-cache/manufacturer"),"text-embedding-3-small","https://api.openai.com")){
            vectors.prepare();
            Map<String,Object> specification=JSON.readValue(Files.readAllBytes(root.resolve("docs/mcp/retrieval_evaluation.json")),new TypeReference<>(){});
            var hybrid=new KnowledgeRetriever(catalog,vectors);var lexical=KnowledgeRetriever.lexical(catalog);
            var rows=new ArrayList<Map<String,Object>>();
            for(Object value:(List<?>)specification.get("cases")){
                @SuppressWarnings("unchecked") var test=(Map<String,Object>)value;
                String query=text(test,"query"),category=text(test,"category"),sale=Objects.toString(test.get("model"),null);
                var baseline=lexical.retrieve(query,category,sale,"MANUAL",null,3);
                var result=hybrid.retrieve(query,category,sale,"MANUAL",null,3);
                var repeated=hybrid.retrieve(query,category,sale,"MANUAL",null,3);
                rows.add(map("id",test.get("id"),"split",test.get("split"),"query",query,"category",category,"model",sale,
                        "lexical_ids",baseline.documents().stream().map(KnowledgeRetriever.Hit::id).toList(),
                        "documents",result.documents(),"safety",result.safety(),"passed",passes(test,result,catalog),
                        "mode",result.mode(),"index_status",result.indexStatus(),"first_query_ms",result.elapsedMs(),"cached_query_ms",repeated.elapsedMs(),
                        "cached_mode",repeated.mode(),"cached_index_status",repeated.indexStatus(),"cached_documents",repeated.documents()));
                if(!passes(test,result,catalog)){
                    var ids=catalog.knowledge.stream().map(d->text(d,"id")).filter(id->hybrid.eligible(id,category,sale)).toList();
                    var scores=vectors.search(category+"\n"+query,ids);
                    rows.getLast().put("diagnostic_semantic_candidates",scores.scores().entrySet().stream().sorted(Map.Entry.<String,Double>comparingByValue().reversed()).limit(5).toList());
                }
            }
            var report=map("evaluated_date","2026-10-14","data_version",catalog.version,"algorithm",KnowledgeRetriever.VERSION,
                    "real_embeddings",true,"index",vectors.metrics(),"cases",rows,"passed",rows.stream().filter(row->Boolean.TRUE.equals(row.get("passed"))).count(),
                    "total",rows.size(),"embedding_price_per_million_usd",.02,"pricing_source","https://developers.openai.com/api/docs/models/text-embedding-3-small");
            var output=root.resolve("docs/benchmarks/manufacturer_retrieval_20261014.json");Files.writeString(output,JSON.writeValueAsString(report)+"\n");
            System.out.println("Manufacturer evaluation: "+report.get("passed")+"/"+rows.size()+"; index "+vectors.metrics());
            rows.stream().filter(row->!Boolean.TRUE.equals(row.get("passed"))).forEach(row->System.out.println("FAILED "+row.get("id")+" "+row.get("documents")));
            if(rows.stream().anyMatch(row->!Boolean.TRUE.equals(row.get("passed"))))throw new IllegalStateException("제조사 검색 평가 실패: 보고서를 확인하세요.");
        }
    }
    public static boolean passes(Map<String,Object> test,KnowledgeRetriever.Result result,ToolDataCatalog catalog){
        var ids=result.documents().stream().map(KnowledgeRetriever.Hit::id).toList();
        var safety=result.safety().stream().map(KnowledgeRetriever.Hit::id).toList();
        var all=new ArrayList<>(ids);all.addAll(safety);
        if(Boolean.TRUE.equals(test.get("expect_empty"))&&!all.isEmpty())return false;
        if(Boolean.TRUE.equals(test.get("no_safety"))&&!safety.isEmpty())return false;
        if(!strings(test,"expected_any").isEmpty()&&strings(test,"expected_any").stream().noneMatch(ids::contains))return false;
        if(!strings(test,"expected_safety_any").isEmpty()&&strings(test,"expected_safety_any").stream().noneMatch(safety::contains))return false;
        if(strings(test,"forbidden_ids").stream().anyMatch(all::contains))return false;
        if(all.stream().anyMatch(id->strings(test,"forbidden_prefixes").stream().anyMatch(id::startsWith)))return false;
        if(all.stream().anyMatch(id->{var doc=catalog.structuredKnowledgeById.get(id);String subject=doc.title()+" "+strings(doc.fields(),"symptoms");
            return strings(test,"forbidden_subjects").stream().anyMatch(subject::contains);}))return false;
        if(test.get("expect_match") instanceof String match&&result.documents().stream().noneMatch(h->h.match().equals(match)))return false;
        return true;
    }
    private KnowledgeRetrievalEvaluation(){ }
}
