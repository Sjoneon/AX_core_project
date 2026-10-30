package com.project.backend.mcp.retrieval;

import static com.project.backend.mcp.tool.ToolDataCatalog.map;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import com.project.backend.data.DataManifest;
import com.project.backend.mcp.tool.ToolDataCatalog;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Audit probe, not a regression test. Builds the real 1,542-unit manufacturer index with an offline counting model
 * of the production shape (1,536 dimensions) and compares a cold build with loads from the disk cache.
 * The model adds no delay: build times here exclude the embedding API round trips by construction.
 */
class IndexCacheProbe {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final int DIMENSIONS=1536,BUILD_WARMUP=1,BUILD_REPEAT=5,LOAD_WARMUP=3,LOAD_REPEAT=20;
    static final String MODEL="audit-offline-bigram",PROVIDER="local";
    @TempDir Path directory;

    /**
     * Hashed character bigrams plus seeded noise: deterministic and offline; texts sharing wording get nearby vectors,
     * and every component is non-zero so the cache file has the size of real dense embeddings.
     */
    static final class CountingModel implements EmbeddingModel {
        final AtomicInteger requests=new AtomicInteger(),inputs=new AtomicInteger();
        final List<String> singleTexts=Collections.synchronizedList(new ArrayList<>());
        static float[] vector(String text){
            var vector=new float[DIMENSIONS];String t=text.toLowerCase(Locale.ROOT);
            for(int i=0;i+1<t.length();i++)vector[Math.floorMod((t.charAt(i)*31+t.charAt(i+1))*0x9E3779B1,DIMENSIONS)]+=1;
            var noise=new SplittableRandom(text.hashCode());for(int i=0;i<vector.length;i++)vector[i]+=(float)(noise.nextGaussian()*0.02);
            double norm=0;for(float v:vector)norm+=v*v;
            if(norm==0){vector[0]=1;norm=1;}
            float scale=(float)(1/Math.sqrt(norm));for(int i=0;i<vector.length;i++)vector[i]*=scale;
            return vector;
        }
        public float @NonNull[] embed(@NonNull Document document){return vector(Objects.requireNonNull(document.getText()));}
        public @NonNull EmbeddingResponse call(@NonNull EmbeddingRequest request){
            requests.incrementAndGet();var results=new ArrayList<Embedding>();int i=0;
            if(request.getInstructions().size()==1){String text=request.getInstructions().getFirst();singleTexts.add(text.length()>30?text.substring(0,30):text);}
            for(String text:request.getInstructions()){inputs.incrementAndGet();results.add(new Embedding(vector(text),i++));}
            return new EmbeddingResponse(results);
        }
    }

    @Test void probeIndexCache() throws Exception {
        var catalog=new ToolDataCatalog(DataManifest.classpath());
        Map<String,Object> specification=JSON.readValue(Files.readAllBytes(Path.of("..").toAbsolutePath().normalize().resolve("docs/mcp/retrieval_evaluation.json")),new TypeReference<>(){});
        var queries=new ArrayList<Map<?,?>>();for(Object value:(List<?>)specification.get("cases"))queries.add((Map<?,?>)value);
        var report=map("data_version",catalog.version,"knowledge_units",catalog.knowledge.size(),"dimensions",DIMENSIONS,"model",MODEL);

        // Cold builds in empty directories: local work only (vectors are computed in-process, no network).
        var builds=new ArrayList<Map<String,Object>>();
        for(int i=0;i<BUILD_WARMUP+BUILD_REPEAT;i++){
            var model=new CountingModel();var cold=Files.createDirectory(directory.resolve("cold-"+i));
            try(var index=new KnowledgeVectorIndex(catalog,model,true,cold,MODEL,PROVIDER,DIMENSIONS)){
                long start=System.nanoTime();index.prepare();long micros=(System.nanoTime()-start)/1000;
                builds.add(map("warmup",i<BUILD_WARMUP,"prepare_us",micros,"embedding_requests",model.requests.get(),"embedded_texts",model.inputs.get(),
                        "single_text_requests",List.copyOf(model.singleTexts),
                        "cache_hit",index.metrics().get("cache_hit"),"cache_persisted",index.metrics().get("cache_persisted"),"cache_bytes",Files.size(index.cachePath())));
            }
        }
        report.put("cold_builds",builds);

        // One cache, then different questions against it.
        var model=new CountingModel();var shared=Files.createDirectory(directory.resolve("shared"));
        List<Object> built;
        try(var index=new KnowledgeVectorIndex(catalog,model,true,shared,MODEL,PROVIDER,DIMENSIONS)){
            index.prepare();int documentRequests=model.requests.get();
            var retriever=new KnowledgeRetriever(catalog,index);
            built=run(retriever,queries);int afterDistinct=model.requests.get();
            run(retriever,queries);int afterRepeat=model.requests.get();
            report.put("questions",map("distinct_questions",queries.size(),"document_embedding_requests",documentRequests,
                    "requests_for_distinct_questions",afterDistinct-documentRequests,"requests_for_same_questions_again",afterRepeat-afterDistinct,
                    "questions_with_semantic_match",built.stream().filter(row->row.toString().contains(":HYBRID:")||row.toString().contains(":SEMANTIC_ONLY:")).count()));
        }

        // Loads as after a restart: a new instance on the same directory, timed inside prepare() like initialization_ms.
        var loads=new ArrayList<Map<String,Object>>();boolean sameResults=true;
        for(int i=0;i<LOAD_WARMUP+LOAD_REPEAT;i++){
            var counting=new CountingModel();
            long constructed=System.nanoTime();
            try(var index=new KnowledgeVectorIndex(catalog,counting,true,shared,MODEL,PROVIDER,DIMENSIONS)){
                long start=System.nanoTime();index.prepare();long micros=(System.nanoTime()-start)/1000;
                loads.add(map("warmup",i<LOAD_WARMUP,"prepare_us",micros,"constructor_us",(start-constructed)/1000,"cache_hit",index.metrics().get("cache_hit"),
                        "document_embedding_requests",counting.requests.get()));
                if(i==LOAD_WARMUP+LOAD_REPEAT-1){
                    int before=counting.requests.get();
                    sameResults=built.equals(run(new KnowledgeRetriever(catalog,index),queries));
                    report.put("loaded_index_questions",map("requests",counting.requests.get()-before,"single_text_requests_first",
                            counting.singleTexts.isEmpty()?null:counting.singleTexts.getFirst()));
                }
            }
        }
        report.put("cache_loads",loads);
        report.put("loaded_results_equal_built_results",sameResults);

        // Invalidation: a changed payload or embedding identity is not served from the cache.
        Path cache;
        try(var index=new KnowledgeVectorIndex(catalog,new CountingModel(),true,shared,MODEL,PROVIDER,DIMENSIONS)){cache=index.cachePath();}
        Files.writeString(cache,Files.readString(cache)+" ");
        var tampered=new CountingModel();
        try(var index=new KnowledgeVectorIndex(catalog,tampered,true,shared,MODEL,PROVIDER,DIMENSIONS)){
            index.prepare();report.put("tampered_payload",map("cache_hit",index.metrics().get("cache_hit"),"embedding_requests",tampered.requests.get()));
        }
        var renamed=new CountingModel();
        try(var index=new KnowledgeVectorIndex(catalog,renamed,true,shared,MODEL+"-v2",PROVIDER,DIMENSIONS)){
            index.prepare();report.put("changed_model_name",map("cache_hit",index.metrics().get("cache_hit"),"embedding_requests",renamed.requests.get(),
                    "same_cache_file",index.cachePath().equals(cache)));
        }
        report.put("environment",map("java",System.getProperty("java.version"),"os",System.getProperty("os.name")+" "+System.getProperty("os.version"),
                "processors",Runtime.getRuntime().availableProcessors(),"max_heap_mb",Runtime.getRuntime().maxMemory()/1024/1024));
        report.put("label",Objects.toString(System.getenv("AUDIT_LABEL"),"tree"));
        var output=Path.of("build","optimization-audit");Files.createDirectories(output);
        Files.writeString(output.resolve("index_cache.json"),JSON.writeValueAsString(report)+"\n");
    }

    static List<Object> run(KnowledgeRetriever retriever,List<Map<?,?>> queries){
        var rows=new ArrayList<Object>();
        for(var test:queries){
            var result=retriever.retrieve(test.get("query").toString(),test.get("category").toString(),test.get("model")==null?null:test.get("model").toString(),"MANUAL",null,3);
            rows.add(List.of(result.mode(),result.documents().stream().map(hit->hit.id()+":"+hit.match()+":"+Math.round(hit.similarity()*1e9)).toList(),
                    result.safety().stream().map(hit->hit.id()).toList()));
        }
        return rows;
    }
}
