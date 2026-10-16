package com.project.backend.mcp.retrieval;

import static java.util.Objects.requireNonNull;
import org.jspecify.annotations.NonNull;

import static com.project.backend.mcp.tool.ToolDataCatalog.*;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import com.project.backend.mcp.tool.ToolDataCatalog;

class KnowledgeVectorIndexTests {
    @TempDir Path directory;
    static class FakeModel implements EmbeddingModel {
        final AtomicInteger calls=new AtomicInteger();boolean fail;boolean malformed;boolean slow;
        public float @NonNull[] embed(@NonNull Document document){return requireNonNull(value(requireNonNull(document.getText())));}
        private float[] value(String text){
            if(slow)try{Thread.sleep(5000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("interrupted");}
            if(fail)throw new IllegalStateException("offline");
            if(text.contains("진동")||text.contains("춤"))return new float[]{1,0,0};
            return new float[]{0,1,0};
        }
        public @NonNull EmbeddingResponse call(@NonNull EmbeddingRequest request){
            calls.incrementAndGet();var results=new ArrayList<Embedding>();int i=0;
            for(String text:request.getInstructions())results.add(new Embedding(malformed?new float[]{Float.NaN}:requireNonNull(value(text)),i++));
            return new EmbeddingResponse(results);
        }
    }
    ToolDataCatalog catalog(){
        return new ToolDataCatalog(List.of(
            map("id","V1","category","WASHER","topic","MANUAL","title","진동이 심해요","text","진동 점검","modelCodes",List.of("MODEL1"),"errorCodes",List.of()),
            map("id","V2","category","WASHER","topic","MANUAL","title","진동이 심해요","text","다른 모델 진동 점검","modelCodes",List.of("MODEL2"),"errorCodes",List.of()),
            map("id","D1","category","WASHER","topic","MANUAL","title","배수 불량","text","배수 점검","modelCodes",List.of("MODEL1"),"errorCodes",List.of())
        ),List.of(),List.of(),List.of(),List.of(),Map.of("MODEL1",Map.of(),"MODEL2",Map.of()),Map.of());
    }
    @Test void semanticSearchFiltersApplicabilityBeforeTopKAndCachesQueryEmbeddings(){
        var model=new FakeModel();var catalog=catalog();
        try(var index=new KnowledgeVectorIndex(catalog,model,true,directory,"fake-model","test")){
            index.prepare();var retriever=new KnowledgeRetriever(catalog,index);
            var result=retriever.retrieve("춤을 춰요","WASHER","MODEL1","MANUAL",null,1);
            assertThat(result.mode()).isEqualTo("HYBRID");
            assertThat(result.documents()).extracting((KnowledgeRetriever.Hit element)->requireNonNull(element).id()).containsExactly("V1");
            int calls=model.calls.get();retriever.retrieve("춤을 춰요","WASHER","MODEL1","MANUAL",null,1);
            assertThat(model.calls.get()).isEqualTo(calls);
        }
    }
    @Test void strongSemanticOnlyMatchCanRetrieveWithoutLexicalOverlap(){
        var model=new FakeModel();var catalog=catalog();
        try(var index=new KnowledgeVectorIndex(catalog,model,true,directory,"fake-model","test")){
            index.prepare();var result=new KnowledgeRetriever(catalog,index).retrieve("원래 상태로 돌아가질 못해요","WASHER","MODEL1","MANUAL",null,3);
            assertThat(result.documents()).extracting((KnowledgeRetriever.Hit element)->requireNonNull(element).id()).containsExactly("D1");
            assertThat(result.documents().getFirst().match()).isEqualTo("SEMANTIC_ONLY");
        }
    }
    @Test void cacheReloadDoesNotReembedAndTamperingForcesRebuild()throws Exception {
        var model=new FakeModel();var catalog=catalog();Path path;
        try(var first=new KnowledgeVectorIndex(catalog,model,true,directory,"fake-model","test")){first.prepare();path=first.cachePath();}
        int before=model.calls.get();
        try(var second=new KnowledgeVectorIndex(catalog,model,true,directory,"fake-model","test")){
            second.prepare();assertThat(second.metrics()).containsEntry("cache_hit",true);assertThat(model.calls.get()).isEqualTo(before);
        }
        Files.writeString(path,"{}");
        try(var third=new KnowledgeVectorIndex(catalog,model,true,directory,"fake-model","test")){
            third.prepare();assertThat(third.metrics()).containsEntry("cache_hit",false);assertThat(model.calls.get()).isGreaterThan(before);
        }
    }
    @Test void changingEmbeddingIdentityInvalidatesTheCache(){
        var model=new FakeModel();
        try(var first=new KnowledgeVectorIndex(catalog(),model,true,directory,"fake-model","test");
            var second=new KnowledgeVectorIndex(catalog(),model,true,directory,"fake-model-v2","test")){
            assertThat(first.cachePath()).isNotEqualTo(second.cachePath());
        }
    }
    @Test void changingDimensionsInvalidatesTheCache(){
        var model=new FakeModel();
        try(var first=new KnowledgeVectorIndex(catalog(),model,true,directory,"fake-model","test",3);
            var second=new KnowledgeVectorIndex(catalog(),model,true,directory,"fake-model","test",2)){
            assertThat(first.cachePath()).isNotEqualTo(second.cachePath());
        }
    }
    @Test void queryFailureFallsBackWithoutPublishingFalseSemanticResults(){
        var model=new FakeModel();
        try(var index=new KnowledgeVectorIndex(catalog(),model,true,directory,"fake-model","test")){
            index.prepare();model.fail=true;
            var result=new KnowledgeRetriever(catalog(),index).retrieve("진동이 심해요","WASHER","MODEL1","MANUAL",null,3);
            assertThat(result.mode()).isEqualTo("LEXICAL_FALLBACK");assertThat(result.indexStatus()).isEqualTo("QUERY_UNAVAILABLE");
            assertThat(result.documents()).extracting((KnowledgeRetriever.Hit element)->requireNonNull(element).id()).containsExactly("V1");
            assertThat(result.documents().getFirst().similarity()).isZero();
        }
    }
    @Test void malformedEmbeddingDoesNotPublishAPartialIndex(){
        var model=new FakeModel();model.malformed=true;
        try(var index=new KnowledgeVectorIndex(catalog(),model,true,directory,"fake-model","test")){
            assertThatThrownBy(index::prepare).isInstanceOf(IllegalStateException.class);
            assertThat(index.metrics().get("status")).isNotEqualTo("READY");
            assertThat(Files.exists(index.cachePath())).isFalse();
        }
    }
    @Test void slowQueryHonorsTheToolLatencyBudget(){
        var model=new FakeModel();
        try(var index=new KnowledgeVectorIndex(catalog(),model,true,directory,"fake-model","test")){
            index.prepare();model.slow=true;long start=System.nanoTime();
            var result=index.search("slow",List.of("V1"));
            assertThat(result.status()).isEqualTo("QUERY_TIMEOUT");assertThat(result.scores()).isEmpty();
            assertThat((System.nanoTime()-start)/1_000_000).isLessThan(3000);
        }
    }
}
