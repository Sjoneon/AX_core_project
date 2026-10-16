package com.project.backend.mcp.retrieval;

import static java.util.Objects.requireNonNull;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.metadata.Usage;

import static com.project.backend.mcp.tool.ToolDataCatalog.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import org.springframework.ai.vectorstore.*;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import com.project.backend.mcp.tool.ToolDataCatalog;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Small manufacturer-only SimpleVectorStore with versioned disk cache and bounded query latency. */
@Component
public class KnowledgeVectorIndex implements AutoCloseable {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public record Search(Map<String,Double> scores,String mode,String status){
        public static Search disabled(){return new Search(Map.of(),"LEXICAL_ONLY","DISABLED");}
    }
    private final EmbeddingModel model;
    private final boolean enabled;
    private final List<Document> documents;
    private final Path cache,manifest;
    private final String fingerprint;
    private final ExecutorService builder=Executors.newSingleThreadExecutor(r->daemon(r,"manufacturer-index"));
    private final ExecutorService queries=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),
            r->daemon(r,"manufacturer-query"),new ThreadPoolExecutor.AbortPolicy());
    private final AtomicBoolean started=new AtomicBoolean();
    private final AtomicLong queryTokens=new AtomicLong(),queryRequests=new AtomicLong();
    private volatile SimpleVectorStore index;
    private volatile String status;
    private volatile long lastAttempt;
    private final Map<String,float[]> queryCache=Collections.synchronizedMap(new LinkedHashMap<>(256,.75f,true){
        protected boolean removeEldestEntry(Map.Entry<String,float[]> eldest){return size()>256;}
    });
    private final Map<String,Object> metrics=new ConcurrentHashMap<>();
    @Autowired public KnowledgeVectorIndex(ToolDataCatalog catalog,EmbeddingModel model,
            @Value("${homecare.knowledge.vector.enabled:true}") boolean enabled,
            @Value("${homecare.agent.enabled:true}") boolean agentEnabled,
            @Value("${homecare.knowledge.vector.cache-dir:.homecare-cache/manufacturer}") String directory,
            @Value("${spring.ai.openai.embedding.model:text-embedding-3-small}") String modelName,
            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String provider,
            @Value("${spring.ai.openai.embedding.options.dimensions:0}") int dimensions){
        this(catalog,model,enabled&&agentEnabled,Path.of(directory),modelName,provider,dimensions);
    }
    public KnowledgeVectorIndex(ToolDataCatalog catalog,EmbeddingModel model,boolean enabled,Path directory,String modelName,String provider){
        this(catalog,model,enabled,directory,modelName,provider,0);
    }
    public KnowledgeVectorIndex(ToolDataCatalog catalog,EmbeddingModel model,boolean enabled,Path directory,String modelName,String provider,int dimensions){
        this.model=model;this.enabled=enabled;this.status=enabled?"NOT_READY":"DISABLED";
        documents=catalog.knowledge.stream().map(source->{
            String id=text(source,"id");var doc=catalog.structuredKnowledgeById.get(id);
            // One fact per unit. No source JSON, review history, virtual manuals, or cross-fact merging.
            // Long procedure/caution text can swamp a short symptom. Use the subject for symptom facts,
            // while procedure/reference facts retain their semantic body. The full source remains in the result.
            var symptoms=strings(doc.fields(),"symptoms");
            String content=source.get("category")+"\n"+doc.title()+"\n"+String.join(" ",symptoms)
                    +"\n"+String.join(" ",strings(source,"errorCodes"))+"\n"
                    +(symptoms.isEmpty()?doc.searchText():String.join(" ",strings(doc.fields(),"causes")));
            return new Document(requireNonNull(id),content,requireNonNull(map("fact_id",id,"category",source.get("category"),"topic",source.get("topic"))));
        }).toList();
        var identity=map("algorithm",KnowledgeRetriever.VERSION,"dataVersion",catalog.version,
                "model",modelName,"provider",provider,"documents",documents.stream().map(d->map("id",d.getId(),"text",d.getText(),"metadata",d.getMetadata())).toList());
        if(dimensions>0)identity.put("configuredDimensions",dimensions);
        fingerprint=sha(JSON.writeValueAsBytes(identity));
        cache=directory.toAbsolutePath().normalize().resolve(fingerprint+".json");
        manifest=cache.resolveSibling(fingerprint+".meta.json");
        metrics.putAll(map("fingerprint",fingerprint,"model",modelName,"unit_count",documents.size(),"corpus","MANUFACTURER_ONLY"));
    }
    private static Thread daemon(Runnable task,String name){var t=new Thread(task,name);t.setDaemon(true);return t;}
    @EventListener(ApplicationReadyEvent.class) public void warmUp(){
        if(!enabled||index!=null||lastAttempt!=0&&System.nanoTime()-lastAttempt<Duration.ofMinutes(1).toNanos()||!started.compareAndSet(false,true))return;
        lastAttempt=System.nanoTime();
        builder.submit(()->{try{prepare();}catch(RuntimeException failed){status="UNAVAILABLE";metrics.put("failure_type",failed.getClass().getSimpleName());}finally{started.set(false);}});
    }
    /** Explicit build/load entrypoint used by the offline preparation command and tests. No partial index is published. */
    public synchronized void prepare(){
        if(!enabled||index!=null)return;
        long start=System.nanoTime();status="BUILDING";
        var prepared=SimpleVectorStore.builder(adapter(Map.of())).build();
        if(load(prepared)){
            index=prepared;status="READY";metrics.put("cache_hit",true);metrics.put("initialization_ms",elapsed(start));return;
        }
        var embeddings=new HashMap<String,float[]>();long tokens=0;
        for(int offset=0;offset<documents.size();offset+=64){
            var batch=documents.subList(offset,Math.min(offset+64,documents.size()));
            var response=model.call(new EmbeddingRequest(requireNonNull(batch.stream().map((Document element)->requireNonNull(element.getText())).toList()),null));
            if(response.getResults().size()!=batch.size())throw new IllegalStateException("제조사 임베딩 개수 불일치");
            var used=new HashSet<Integer>();
            for(var embedding:response.getResults()){
                int i=embedding.getIndex();
                if(i<0||i>=batch.size()||!used.add(i))throw new IllegalStateException("제조사 임베딩 순서 불일치");
                validate(embedding.getOutput());embeddings.put(batch.get(i).getId(),embedding.getOutput());
            }
            @Nullable Integer usedTokens=totalTokens(response.getMetadata().getUsage());
            if(usedTokens!=null)tokens+=usedTokens;
        }
        if(embeddings.values().stream().map(v->v.length).distinct().count()!=1)throw new IllegalStateException("제조사 임베딩 차원 불일치");
        prepared=SimpleVectorStore.builder(adapter(embeddings)).build();prepared.add(requireNonNull(documents));
        metrics.putAll(map("cache_hit",false,"embedding_tokens",tokens,"initialization_ms",elapsed(start),"build_initialization_ms",elapsed(start),
                "dimensions",embeddings.values().iterator().next().length));
        try {
            Files.createDirectories(cache.getParent());
            var temporary=Files.createTempFile(cache.getParent(),fingerprint,".tmp");
            prepared.save(requireNonNull(temporary.toFile()));
            var info=new LinkedHashMap<>(metrics);info.put("payload_sha256",sha(Files.readAllBytes(temporary)));
            var metaTemporary=Files.createTempFile(cache.getParent(),fingerprint,".meta.tmp");
            Files.write(metaTemporary,JSON.writeValueAsBytes(info));
            move(temporary,cache);move(metaTemporary,manifest);metrics.put("cache_persisted",true);
        }catch(Exception unavailableDisk){metrics.put("cache_persisted",false);}
        index=prepared;status="READY";
    }
    private boolean load(SimpleVectorStore prepared){
        try {
            if(!Files.isRegularFile(cache)||!Files.isRegularFile(manifest))return false;
            Map<String,Object> info=JSON.readValue(Files.readAllBytes(manifest),new TypeReference<>(){});
            byte[] payload=Files.readAllBytes(cache);
            if(!fingerprint.equals(info.get("fingerprint"))||!sha(payload).equals(info.get("payload_sha256")))return false;
            Map<String,Map<String,Object>> entries=JSON.readValue(payload,new TypeReference<>(){});
            if(!entries.keySet().equals(documents.stream().map((Document element)->requireNonNull(element).getId()).collect(java.util.stream.Collectors.toSet())))return false;
            int dimensions=((Number)info.get("dimensions")).intValue();
            for(Document doc:documents){
                var entry=entries.get(doc.getId());
                if(!Objects.equals(doc.getText(),entry.get("text"))||!Objects.equals(doc.getMetadata(),entry.get("metadata")))return false;
                if(!(entry.get("embedding") instanceof List<?> values)||values.size()!=dimensions)return false;
                var vector=new float[values.size()];for(int i=0;i<vector.length;i++)vector[i]=((Number)requireNonNull(values.get(i))).floatValue();validate(vector);
            }
            prepared.load(requireNonNull(cache.toFile()));metrics.putAll(info);metrics.put("build_initialization_ms",info.getOrDefault("build_initialization_ms",info.get("initialization_ms")));return true;
        }catch(Exception staleOrCorrupt){return false;}
    }
    private @NonNull EmbeddingModel adapter(Map<String,float[]> embeddings){
        return new EmbeddingModel(){
            public float @NonNull[] embed(@NonNull Document document){return Objects.requireNonNull(embeddings.get(document.getId()));}
            public float @NonNull[] embed(@NonNull String query){
                var cached=queryCache.get(query);if(cached!=null)return cached;
                queryRequests.incrementAndGet();
                var response=model.call(new EmbeddingRequest(requireNonNull(List.of(query)),null));
                if(response.getResults().size()!=1)throw new IllegalStateException("검색어 임베딩 개수 불일치");
                @Nullable Integer usedTokens=totalTokens(response.getMetadata().getUsage());
                if(usedTokens!=null)queryTokens.addAndGet(usedTokens);
                float[] value=response.getResult().getOutput();validate(value);queryCache.put(query,value);return value;
            }
            public @NonNull EmbeddingResponse call(@NonNull EmbeddingRequest request){return model.call(request);}
        };
    }
    private static @Nullable Integer totalTokens(@Nullable Usage usage){return usage==null?null:usage.getTotalTokens();}
    public Search search(String query,List<String> eligibleIds){
        if(!enabled)return Search.disabled();
        if(eligibleIds.isEmpty())return new Search(Map.of(),"LEXICAL_ONLY",status);
        if(index==null){warmUp();return new Search(Map.of(),"LEXICAL_FALLBACK",status);}
        Future<List<Document>> task=null;
        try {
            var filter=new FilterExpressionBuilder().in("fact_id",requireNonNull(eligibleIds.toArray())).build();
            var current=index;
            task=queries.submit(()->current.similaritySearch(SearchRequest.builder().query(requireNonNull(query)).topK(eligibleIds.size())
                    .similarityThreshold(.45).filterExpression(filter).build()));
            var scores=new HashMap<String,Double>();
            for(var doc:task.get(2,TimeUnit.SECONDS))if(doc.getScore()!=null)scores.put(doc.getId(),doc.getScore());
            return new Search(Map.copyOf(scores),"HYBRID","READY");
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return new Search(Map.of(),"LEXICAL_FALLBACK","INTERRUPTED");}
        catch(Exception failed){return new Search(Map.of(),"LEXICAL_FALLBACK",failed instanceof TimeoutException?"QUERY_TIMEOUT":"QUERY_UNAVAILABLE");}
        finally{if(task!=null&&!task.isDone())task.cancel(true);}
    }
    public Map<String,Object> metrics(){var copy=new LinkedHashMap<>(metrics);copy.put("status",status);
        copy.put("query_embedding_tokens",queryTokens.get());copy.put("query_embedding_requests",queryRequests.get());return copy;}
    public Path cachePath(){return cache;}
    private static long elapsed(long start){return (System.nanoTime()-start)/1_000_000;}
    private static void validate(float[] vector){
        if(vector.length==0)throw new IllegalStateException("빈 제조사 임베딩");double norm=0;
        for(float v:vector){if(!Float.isFinite(v))throw new IllegalStateException("잘못된 제조사 임베딩");norm+=v*v;}
        if(norm==0)throw new IllegalStateException("잘못된 제조사 임베딩");
    }
    private static void move(Path source,Path target)throws java.io.IOException{
        try{Files.move(source,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException unsupported){Files.move(source,target,StandardCopyOption.REPLACE_EXISTING);}
    }
    private static String sha(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception e){throw new IllegalStateException(e);}}
    @PreDestroy public void close(){builder.shutdownNow();queries.shutdownNow();queryCache.clear();}
}
