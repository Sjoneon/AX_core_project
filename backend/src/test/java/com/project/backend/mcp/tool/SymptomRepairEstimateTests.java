package com.project.backend.mcp.tool;

import static org.assertj.core.api.Assertions.*;
import static com.project.backend.mcp.tool.ToolDataCatalog.map;
import java.util.*;
import org.junit.jupiter.api.*;
import com.project.backend.conversation.ProductCategory;
import com.project.backend.data.DataManifest;

class SymptomRepairEstimateTests {
    static ToolDataCatalog catalog;
    @BeforeAll static void load(){catalog=new ToolDataCatalog(DataManifest.classpath());}
    @SuppressWarnings("unchecked") static Map<String,Object> section(Map<String,Object> estimate,String key){return (Map<String,Object>)estimate.get(key);}

    @Test void coolingShowsTheCommonRangeAndTheCompressorSeparately(){
        var estimate=SymptomRepairEstimate.of(catalog,ProductCategory.REFRIGERATOR,"COOLING",null);
        assertThat(estimate).containsEntry("status","OK").containsEntry("basis","SYMPTOM");
        assertThat(section(estimate,"base")).containsEntry("min",48195).containsEntry("max",184042)
                .containsEntry("parts",List.of("제상 센서","응축기(기계실) 팬모터","메인 PCB"));
        assertThat(estimate.get("separate")).isEqualTo(List.of(map("id","R01","name","컴프레서","min",231395,"max",427509,"is_core",true)));
        assertThat(section(estimate,"overall")).containsEntry("min",48195).containsEntry("max",427509);
    }
    @Test void aDisplayedErrorTakesPrecedenceOverTheSymptom(){
        var estimate=SymptomRepairEstimate.of(catalog,ProductCategory.REFRIGERATOR,"COOLING","ER FF");
        assertThat(estimate).containsEntry("basis","ERROR").containsEntry("code","ERFF");
        assertThat(section(estimate,"base")).containsEntry("parts",List.of("증발기(냉동실) 팬모터"));
        assertThat((List<?>)estimate.get("separate")).isEmpty();
        // An error without links falls back to the symptom.
        assertThat(SymptomRepairEstimate.of(catalog,ProductCategory.REFRIGERATOR,"COOLING","XX9")).containsEntry("basis","SYMPTOM");
    }
    @Test void closeRangesStayInOneBaseRangeAndUnknownSymptomsHaveNoAmount(){
        var defrost=SymptomRepairEstimate.of(catalog,ProductCategory.REFRIGERATOR,null,"FDH");
        assertThat(section(defrost,"base")).containsEntry("min",48195).containsEntry("max",184042);
        assertThat((List<?>)defrost.get("separate")).isEmpty();
        var drain=SymptomRepairEstimate.of(catalog,ProductCategory.WASHER,"DRAIN",null);
        assertThat(section(drain,"base")).containsEntry("min",88373).containsEntry("max",163272).containsEntry("parts",List.of("배수펌프"));
        assertThat(SymptomRepairEstimate.of(catalog,ProductCategory.REFRIGERATOR,"ODOR",null)).containsEntry("status","NO_DATA");
        assertThat(SymptomRepairEstimate.of(catalog,ProductCategory.WASHER,null,null)).containsEntry("status","NO_DATA");
    }
    @Test void withoutAPartLinkTheWholeCategoryRangeIsLabelledAsSuch(){
        var smoke=SymptomRepairEstimate.orCategory(catalog,ProductCategory.DRYER,null,null);
        assertThat(smoke).containsEntry("status","OK").containsEntry("basis","CATEGORY").containsEntry("category","DRYER");
        assertThat(section(smoke,"overall")).containsEntry("min",43440).containsEntry("max",283363);
        assertThat((List<?>)smoke.get("candidates")).hasSize(6);
        // A linked symptom keeps its own range.
        assertThat(SymptomRepairEstimate.orCategory(catalog,ProductCategory.DRYER,"DRYING",null)).containsEntry("basis","SYMPTOM");
    }
    @Test void everyLinkKeepsItsEvidenceType(){
        assertThat(catalog.symptomParts).hasSize(71).allSatisfy(link->assertThat((List<?>)link.get("evidence")).isNotEmpty());
        assertThat(catalog.symptomParts).filteredOn(link->"OBSERVATION".equals(link.get("kind"))).hasSize(9);
        var drain=SymptomRepairEstimate.links(catalog,ProductCategory.WASHER,"DRAIN",null);
        assertThat(drain).singleElement().satisfies(link->assertThat(link).containsEntry("guides",1).containsEntry("repairCases",0));
    }
    static Map<String,Object> cooling(Map<String,String> observations){
        return SymptomRepairEstimate.of(catalog,ProductCategory.REFRIGERATOR,"COOLING",null,observations);
    }
    @Test void answersNarrowTheRangeToTheirLinkedPartsAndKeepTheWholeRange(){
        // A silent compressor points to the board or the compressor: the cheap parts drop out.
        var silent=section(cooling(Map.of("DISPLAY","NORMAL","SOUND","NO","FROST","NONE")),"narrowed");
        assertThat(section(silent,"base")).containsEntry("min",99615).containsEntry("max",184042).containsEntry("parts",List.of("메인 PCB"));
        assertThat(silent.get("separate")).isEqualTo(List.of(map("id","R01","name","컴프레서","min",231395,"max",427509,"is_core",true)));
        // Thick frost on the back wall points to the defrost parts and the board; the compressor drops out.
        var frost=cooling(Map.of("FROST","BACK_WALL"));
        assertThat(section(section(frost,"narrowed"),"base")).containsEntry("min",48195).containsEntry("max",184042)
                .containsEntry("parts",List.of("제상 센서","제상 히터","메인 PCB"));
        assertThat((List<?>)section(frost,"narrowed").get("separate")).isEmpty();
        assertThat(section(frost,"overall")).containsEntry("min",48195).containsEntry("max",427509);
        assertThat(section(section(cooling(Map.of("DISPLAY","OFF")),"narrowed"),"base")).containsEntry("parts",List.of("메인 PCB"));
        assertThat(section(section(cooling(Map.of("FROST","DOOR")),"narrowed"),"base")).containsEntry("min",85158).containsEntry("max",157332).containsEntry("parts",List.of("도어 가스켓"));
        // Answers that disagree keep every part either one supports.
        assertThat(section(section(cooling(Map.of("DISPLAY","OFF","FROST","DOOR")),"narrowed"),"base")).containsEntry("parts",List.of("도어 가스켓","메인 PCB"));
    }
    @Test void neutralAnswersNarrowNothingAndADisplayedErrorIgnoresThem(){
        assertThat(section(cooling(Map.of("DISPLAY","NORMAL","SOUND","YES","FROST","UNKNOWN")),"narrowed")).containsEntry("status","NO_MATCH");
        assertThat(cooling(Map.of())).doesNotContainKey("narrowed");
        assertThat(SymptomRepairEstimate.of(catalog,ProductCategory.REFRIGERATOR,"COOLING","ER FF",Map.of("SOUND","NO"))).doesNotContainKey("narrowed");
    }
    @Test void everyObservationLinkIsAFindingOfAnAskedQuestion(){
        for(var link:catalog.symptomParts.stream().filter(l->"OBSERVATION".equals(l.get("kind"))).toList()){
            var question=com.project.backend.mcp.consultation.ObservationQuestions.of(ProductCategory.valueOf((String)link.get("category")),(String)link.get("code")).stream()
                    .filter(q->q.id().equals(link.get("question"))).findFirst().orElseThrow();
            assertThat(question.options()).extracting(o->o.value()).contains((String)link.get("answer"));
            assertThat((String)link.get("answer")).isNotIn("NORMAL","YES","NONE","UNKNOWN","CODE");
        }
    }
}
