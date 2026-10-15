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
    @Test void everyLinkKeepsItsEvidenceType(){
        assertThat(catalog.symptomParts).hasSize(45).allSatisfy(link->assertThat((List<?>)link.get("evidence")).isNotEmpty());
        var drain=SymptomRepairEstimate.links(catalog,ProductCategory.WASHER,"DRAIN",null);
        assertThat(drain).singleElement().satisfies(link->assertThat(link).containsEntry("guides",1).containsEntry("repairCases",0));
    }
}
