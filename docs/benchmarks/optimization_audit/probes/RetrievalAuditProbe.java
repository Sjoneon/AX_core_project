package com.project.backend.mcp.retrieval;

import static com.project.backend.mcp.tool.ToolDataCatalog.map;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.project.backend.data.DataManifest;
import com.project.backend.mcp.tool.ToolDataCatalog;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Audit probe, not a regression test. Without any embedding request it
 * (1) re-judges the stored retrieval runs with the committed criteria,
 * (2) runs the same cases with the lexical/filter path only, and
 * (3) times that local path. Copied into an exported tree by run_audit.py.
 */
class RetrievalAuditProbe {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final int WARMUP=3,REPEAT=20;
    static final List<String> STORED=List.of("manufacturer_retrieval_initial_20261014","manufacturer_retrieval_20261014");

    @Test void probeRetrieval() throws Exception {
        Path root=Path.of("..").toAbsolutePath().normalize();
        var catalog=new ToolDataCatalog(DataManifest.classpath());
        Map<String,Object> specification=JSON.readValue(Files.readAllBytes(root.resolve("docs/mcp/retrieval_evaluation.json")),new TypeReference<>(){});
        var cases=new ArrayList<Map<String,Object>>();
        for(Object value:(List<?>)specification.get("cases")){@SuppressWarnings("unchecked") var test=(Map<String,Object>)value;cases.add(test);}
        var byId=new LinkedHashMap<String,Map<String,Object>>();cases.forEach(test->byId.put(test.get("id").toString(),test));
        var report=map("data_version",catalog.version,"algorithm",KnowledgeRetriever.VERSION,"knowledge_units",catalog.knowledge.size(),
                "evaluation_version",specification.get("version"),"cases",cases.size());

        // (1) Stored runs judged again. Only meaningful when the packaged data is the version the run used.
        var rejudged=new LinkedHashMap<String,Object>();
        for(String run:STORED){
            Map<String,Object> stored=JSON.readValue(Files.readAllBytes(root.resolve("docs/benchmarks/"+run+".json")),new TypeReference<>(){});
            if(!catalog.version.equals(stored.get("data_version"))){rejudged.put(run,map("skipped","data version "+stored.get("data_version")+" != "+catalog.version));continue;}
            var rows=new ArrayList<Map<String,Object>>();int agree=0,now=0;
            for(Object value:(List<?>)stored.get("cases")){
                var row=(Map<?,?>)value;var test=byId.get(row.get("id").toString());
                if(test==null){rows.add(map("id",row.get("id"),"criteria","MISSING"));continue;}
                var result=new KnowledgeRetriever.Result(hits(row.get("safety")),hits(row.get("documents")),Objects.toString(row.get("mode")),
                        Objects.toString(row.get("index_status")),0);
                boolean passes=KnowledgeRetrievalEvaluation.passes(test,result,catalog);
                boolean recorded=Boolean.TRUE.equals(row.get("passed"));
                if(passes==recorded)agree++;if(passes)now++;
                rows.add(map("id",row.get("id"),"recorded",recorded,"rejudged",passes));
            }
            rejudged.put(run,map("total",rows.size(),"rejudged_passed",now,"agrees_with_record",agree,"rows",rows));
        }
        report.put("rejudged_stored_runs",rejudged);

        // (2) Lexical/filter path only, the same retriever without the vector index.
        var lexical=KnowledgeRetriever.lexical(catalog);
        var ablation=new ArrayList<Map<String,Object>>();int passed=0;
        for(var test:cases){
            var result=lexical.retrieve(test.get("query").toString(),test.get("category").toString(),
                    test.get("model")==null?null:test.get("model").toString(),"MANUAL",null,3);
            boolean passes=KnowledgeRetrievalEvaluation.passes(test,result,catalog);if(passes)passed++;
            ablation.add(map("id",test.get("id"),"split",test.get("split"),"passed",passes,"mode",result.mode(),
                    "documents",result.documents().stream().map(hit->hit.id()+":"+hit.match()).toList(),
                    "safety",result.safety().stream().map(hit->hit.id()).toList()));
        }
        report.put("lexical_only",map("passed",passed,"total",cases.size(),"cases",ablation));

        // (3) Local cost of the lexical/filter path per query; no network involved.
        var samples=new ArrayList<Long>();
        for(int round=0;round<WARMUP+REPEAT;round++){
            for(var test:cases){
                long start=System.nanoTime();
                lexical.retrieve(test.get("query").toString(),test.get("category").toString(),test.get("model")==null?null:test.get("model").toString(),"MANUAL",null,3);
                if(round>=WARMUP)samples.add((System.nanoTime()-start)/1000);
            }
        }
        report.put("lexical_local_time_us",map("warmup_rounds",WARMUP,"rounds",REPEAT,"queries_per_round",cases.size(),"samples",samples));
        report.put("environment",map("java",System.getProperty("java.version"),"os",System.getProperty("os.name")+" "+System.getProperty("os.version"),
                "processors",Runtime.getRuntime().availableProcessors()));
        report.put("label",Objects.toString(System.getenv("AUDIT_LABEL"),"tree"));
        var directory=Path.of("build","optimization-audit");Files.createDirectories(directory);
        Files.writeString(directory.resolve("retrieval.json"),JSON.writeValueAsString(report)+"\n");
    }

    static List<KnowledgeRetriever.Hit> hits(Object value){
        var hits=new ArrayList<KnowledgeRetriever.Hit>();
        if(value instanceof List<?> list)for(Object item:list){
            var hit=(Map<?,?>)item;
            hits.add(new KnowledgeRetriever.Hit(hit.get("id").toString(),Objects.toString(hit.get("applicability")),
                    ((Number)hit.get("score")).doubleValue(),((Number)hit.get("similarity")).doubleValue(),hit.get("match").toString()));
        }
        return hits;
    }
}
