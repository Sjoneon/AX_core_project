package com.project.backend.mcp.tool;

import static com.project.backend.mcp.tool.ToolDataCatalog.*;
import java.util.*;
import com.project.backend.conversation.ProductCategory;

/**
 * Reference repair-cost range for a symptom or displayed error, from the reviewed symptom-parts links and the v3 cost
 * table (weekday totals including the visit fee). Candidate groups are alternatives, never summed.
 *
 * The base range covers the candidates whose upper bound stays within {@link #SEPARATE_GAP} of the cheaper ones;
 * a candidate that raises the upper bound by at least that much (e.g. the compressor) is reported separately,
 * together with the overall range that includes it.
 *
 * Observation answers (e.g. no running sound) add a narrowed range over the parts their evidence links to. Every part
 * that any answer supports stays in it, so conflicting answers widen it rather than exclude a supported part.
 */
public final class SymptomRepairEstimate {
    public static final int SEPARATE_GAP=100_000;
    private SymptomRepairEstimate(){ }

    /** Links for the displayed error first; the symptom's links when the error has none. */
    public static List<Map<String,Object>> links(ToolDataCatalog catalog,ProductCategory category,String symptomCode,String errorCode){
        var forError=errorCode==null?List.<Map<String,Object>>of():matching(catalog,category,"ERROR",errorCode);
        return forError.isEmpty()&&symptomCode!=null?matching(catalog,category,"SYMPTOM",symptomCode):forError;
    }

    public static Map<String,Object> of(ToolDataCatalog catalog,ProductCategory category,String symptomCode,String errorCode){
        return of(catalog,category,symptomCode,errorCode,Map.of());
    }

    public static Map<String,Object> of(ToolDataCatalog catalog,ProductCategory category,String symptomCode,String errorCode,Map<String,String> observations){
        var links=links(catalog,category,symptomCode,errorCode);
        if(links.isEmpty())return map("status","NO_DATA","basis",errorCode!=null?"ERROR":"SYMPTOM","candidates",List.of());
        var result=range(catalog,links);
        result.put("basis",links.getFirst().get("kind"));result.put("code",links.getFirst().get("code"));
        // Answers describe the symptom; a displayed error already decides the candidates.
        if(!observations.isEmpty()&&"SYMPTOM".equals(links.getFirst().get("kind"))){
            var observed=catalog.symptomParts.stream().filter(l->"OBSERVATION".equals(l.get("kind"))&&category.name().equals(l.get("category"))
                    &&symptomCode.equals(l.get("code"))&&Objects.equals(observations.get(text(l,"question")),l.get("answer"))).toList();
            if(observed.isEmpty())result.put("narrowed",map("status","NO_MATCH"));
            else{
                var narrowed=range(catalog,observed);result.put("narrowed",narrowed);
                // The whole range also covers parts that only an answer links to (e.g. the defrost heater).
                @SuppressWarnings("unchecked") var all=(Map<String,Object>)result.get("overall");
                var some=(Map<?,?>)narrowed.get("overall");
                all.put("min",Math.min((int)all.get("min"),(int)some.get("min")));all.put("max",Math.max((int)all.get("max"),(int)some.get("max")));
            }
        }
        return result;
    }

    /**
     * The symptom or error range, or every part of the category when neither links to a part (e.g. smoke reported as a
     * safety risk). The category range is labelled as such: the failed part is decided at the inspection.
     */
    public static Map<String,Object> orCategory(ToolDataCatalog catalog,ProductCategory category,String symptomCode,String errorCode){
        var linked=of(catalog,category,symptomCode,errorCode);
        if(!"NO_DATA".equals(linked.get("status")))return linked;
        var links=catalog.parts.stream().filter(p->category.name().equals(p.get("category"))).map(p->map("partId",p.get("id"))).toList();
        if(links.isEmpty())return linked;
        var result=range(catalog,links);
        result.put("basis","CATEGORY");result.put("category",category.name());
        return result;
    }

    /** Base, separate and overall ranges of the linked parts; links to the same part are merged with their evidence. */
    private static Map<String,Object> range(ToolDataCatalog catalog,List<Map<String,Object>> links){
        var byPart=new LinkedHashMap<String,Map<String,Object>>();
        for(var link:links){
            var part=catalog.parts.stream().filter(p->text(p,"id").equals(text(link,"partId"))).findFirst().orElseThrow();
            var candidate=byPart.computeIfAbsent(text(part,"id"),id->map("id",part.get("id"),"name",part.get("name"),"min",whole(part,"empiricalMin"),"max",whole(part,"empiricalMax"),
                    "median",whole(part,"empiricalMedian"),"confidence_grade",part.get("confidenceGrade"),"calibration_cases",part.get("calibrationCases"),
                    "is_core",Boolean.TRUE.equals(part.get("isCore")),"repair_cases",0,"official_facts",0,"guides",0,"evidence",new ArrayList<>()));
            for(String count:List.of("repair_cases","official_facts","guides"))
                candidate.put(count,(int)candidate.get(count)+((Number)link.getOrDefault(switch(count){case "repair_cases"->"repairCases";case "official_facts"->"officialFacts";default->"guides";},0)).intValue());
            @SuppressWarnings("unchecked") var evidence=(List<Object>)candidate.get("evidence");
            if(link.get("evidence") instanceof List<?> items)evidence.addAll(items);
        }
        var candidates=new ArrayList<>(byPart.values());
        candidates.sort(Comparator.comparingInt((Map<String,Object> c)->(int)c.get("max")).thenComparing(c->text(c,"id")));
        var base=new ArrayList<Map<String,Object>>();var separate=new ArrayList<Map<String,Object>>();int baseMax=0;
        for(var candidate:candidates){
            int max=(int)candidate.get("max");
            if(base.isEmpty()||separate.isEmpty()&&max-baseMax<SEPARATE_GAP){base.add(candidate);baseMax=Math.max(baseMax,max);}
            else separate.add(candidate);
        }
        int baseMin=base.stream().mapToInt(c->(int)c.get("min")).min().orElseThrow();
        int overallMax=candidates.stream().mapToInt(c->(int)c.get("max")).max().orElseThrow();
        return map("status","OK","base",map("min",baseMin,"max",baseMax,"parts",base.stream().map(c->c.get("name")).toList()),
                "separate",separate.stream().map(c->map("id",c.get("id"),"name",c.get("name"),"min",c.get("min"),"max",c.get("max"),"is_core",c.get("is_core"))).toList(),
                "overall",map("min",baseMin,"max",overallMax),"candidates",candidates);
    }

    private static List<Map<String,Object>> matching(ToolDataCatalog catalog,ProductCategory category,String kind,String code){
        String wanted=code.replaceAll("\\s+","").toUpperCase(Locale.ROOT);
        return catalog.symptomParts.stream().filter(l->category.name().equals(l.get("category"))&&kind.equals(l.get("kind"))&&wanted.equals(text(l,"code"))).toList();
    }
    private static int whole(Map<String,Object> part,String field){
        if(!(part.get(field) instanceof Number n))throw new IllegalStateException("수리비 자료에 값이 없습니다: "+field);
        return n.intValue();
    }
}
