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
        var links=links(catalog,category,symptomCode,errorCode);
        var candidates=new ArrayList<Map<String,Object>>();
        for(var link:links){
            var part=catalog.parts.stream().filter(p->text(p,"id").equals(text(link,"partId"))).findFirst().orElseThrow();
            candidates.add(map("id",part.get("id"),"name",part.get("name"),"min",whole(part,"empiricalMin"),"max",whole(part,"empiricalMax"),
                    "median",whole(part,"empiricalMedian"),"confidence_grade",part.get("confidenceGrade"),"calibration_cases",part.get("calibrationCases"),
                    "is_core",Boolean.TRUE.equals(part.get("isCore")),"repair_cases",link.get("repairCases"),"official_facts",link.get("officialFacts"),
                    "guides",link.get("guides"),"evidence",link.get("evidence")));
        }
        if(candidates.isEmpty())return map("status","NO_DATA","basis",errorCode!=null?"ERROR":"SYMPTOM","candidates",List.of());
        candidates.sort(Comparator.comparingInt((Map<String,Object> c)->(int)c.get("max")).thenComparing(c->text(c,"id")));
        var base=new ArrayList<Map<String,Object>>();var separate=new ArrayList<Map<String,Object>>();int baseMax=0;
        for(var candidate:candidates){
            int max=(int)candidate.get("max");
            if(base.isEmpty()||separate.isEmpty()&&max-baseMax<SEPARATE_GAP){base.add(candidate);baseMax=Math.max(baseMax,max);}
            else separate.add(candidate);
        }
        int baseMin=base.stream().mapToInt(c->(int)c.get("min")).min().orElseThrow();
        int overallMax=candidates.stream().mapToInt(c->(int)c.get("max")).max().orElseThrow();
        return map("status","OK","basis",links.getFirst().get("kind"),"code",links.getFirst().get("code"),
                "base",map("min",baseMin,"max",baseMax,"parts",base.stream().map(c->c.get("name")).toList()),
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
