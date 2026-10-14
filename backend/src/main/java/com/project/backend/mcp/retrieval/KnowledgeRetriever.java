package com.project.backend.mcp.retrieval;

import static com.project.backend.mcp.tool.ToolDataCatalog.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.project.backend.mcp.tool.*;

/** Manufacturer retrieval, independent of the legacy virtual ManualRetriever. */
@Service
public class KnowledgeRetriever {
    public static final String VERSION="manufacturer-hybrid-v1";
    public record Hit(String id,String applicability,double score,double similarity,String match){ }
    public record Result(List<Hit> safety,List<Hit> documents,String mode,String indexStatus,long elapsedMs){ }
    private final ToolDataCatalog catalog;
    private final KnowledgeRetrievalPolicy policy;
    private final KnowledgeVectorIndex vectors;
    @Autowired public KnowledgeRetriever(ToolDataCatalog catalog,KnowledgeVectorIndex vectors){
        this.catalog=catalog;this.policy=new KnowledgeRetrievalPolicy(catalog);this.vectors=vectors;
    }
    public static KnowledgeRetriever lexical(ToolDataCatalog catalog){return new KnowledgeRetriever(catalog,null);}
    public String applicability(String id,String model){return policy.status(id,model);}
    public boolean eligible(String id,String category,String model){return policy.eligible(id,category,model);}
    public boolean verifiedCommon(String id){return policy.common(id);}
    public Result retrieve(String query,String category,String model,String topic,String stateError,int top){
        long started=System.nanoTime();
        if(stateError==null&&KnowledgeTextMatcher.concepts(query).isEmpty()&&KnowledgeTextMatcher.tokens(query).isEmpty()
                &&catalog.knowledge.stream().noneMatch(source->exactCode(source,query,null)))
            return new Result(List.of(),List.of(),"LEXICAL_ONLY",vectors==null?"DISABLED":Objects.toString(vectors.metrics().get("status")),0);
        var candidates=catalog.knowledge.stream().map(d->text(d,"id")).filter(id->policy.eligible(id,category,model))
                .filter(id->topic.equals(text(catalog.knowledgeById.get(id),"topic"))||"SAFETY".equals(text(catalog.knowledgeById.get(id),"topic"))).toList();
        var similarities=vectors==null?KnowledgeVectorIndex.Search.disabled():vectors.search(category+"\n"+query,candidates);
        var subjectScores=new HashMap<String,Double>();
        similarities.scores().forEach((id,score)->{
            if(!policy.displaySubject(id))subjectScores.merge(policy.primary(id).replaceAll("[\\p{Punct}\\s]+",""),score,Math::max);
        });
        var leaders=subjectScores.values().stream().sorted(Comparator.reverseOrder()).toList();
        double best=leaders.isEmpty()?0:leaders.getFirst(),runnerUp=leaders.size()<2?0:leaders.get(1);
        var hits=new ArrayList<Hit>();
        for(String id:candidates){
            var source=catalog.knowledgeById.get(id);String primary=policy.primary(id);
            boolean exact=exactCode(source,query,stateError);
            boolean display=policy.exactDisplay(id,query);
            double lexical=KnowledgeTextMatcher.lexical(query,primary,catalog.structuredKnowledgeById.get(id).searchText().toLowerCase(Locale.ROOT));
            double similarity=similarities.scores().getOrDefault(id,0.0);
            boolean semantic=similarity>=.60&&KnowledgeTextMatcher.compatible(query,primary);
            // Similarity alone can prefer arbitrary appliance facts for unsupported requests.
            // Pure semantic matches need a strong, distinct subject; ambiguous neighbors cause abstention.
            if(lexical<2)semantic=semantic&&similarity>=.72&&similarity>=best-.005&&best-runnerUp>=.05;
            // No padding by category words or low semantic similarity. An unrelated error code is not a symptom match.
            if(!exact&&!display&&lexical<2&&!semantic)continue;
            if(!exact&&!display&&policy.displaySubject(id))continue;
            double score=exact||display?100:lexical+similarity*5;
            hits.add(new Hit(id,policy.status(id,model),score,similarity,exact?"EXACT_ERROR_CODE":display?"EXACT_DISPLAY_LABEL":semantic?(lexical<2?"SEMANTIC_ONLY":"HYBRID"):"LEXICAL"));
        }
        if(KnowledgeTextMatcher.concepts(query).isEmpty()&&hits.stream().anyMatch(h->h.match().equals("EXACT_ERROR_CODE")))
            hits.removeIf(h->!h.match().equals("EXACT_ERROR_CODE")&&!"SAFETY".equals(text(catalog.knowledgeById.get(h.id()),"topic")));
        if(hits.stream().anyMatch(h->h.match().equals("EXACT_DISPLAY_LABEL")))
            hits.removeIf(h->!h.match().equals("EXACT_DISPLAY_LABEL")&&!"SAFETY".equals(text(catalog.knowledgeById.get(h.id()),"topic")));
        Comparator<Hit> order=Comparator.comparingDouble(Hit::score).reversed()
                .thenComparing(h->"MODEL_LISTED".equals(h.applicability())?0:1).thenComparing(Hit::id);
        var safety=hits.stream().filter(h->"SAFETY".equals(text(catalog.knowledgeById.get(h.id()),"topic"))).sorted(order).limit(2).toList();
        var docs=hits.stream().filter(h->topic.equals(text(catalog.knowledgeById.get(h.id()),"topic"))&&!safety.contains(h)).sorted(order).limit(top).toList();
        return new Result(safety,docs,similarities.mode(),similarities.status(),(System.nanoTime()-started)/1_000_000);
    }
    private static boolean exactCode(Map<String,Object> source,String query,String stateError){
        for(String code:strings(source,"errorCodes")){
            String normalized=code.replaceAll("[^A-Za-z0-9]","").toUpperCase(Locale.ROOT);
            if(normalized.isEmpty())continue;
            if(stateError!=null&&normalized.equals(stateError.replaceAll("[^A-Za-z0-9]","").toUpperCase(Locale.ROOT)))return true;
            String spaced=String.join("\\s*",normalized.split(""));
            if(Pattern.compile("(?i)(?<![A-Za-z0-9])"+spaced+"(?![A-Za-z0-9])").matcher(query).find())return true;
        }
        return false;
    }
}
