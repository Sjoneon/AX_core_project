package com.project.backend.mcp.retrieval;

import static com.project.backend.mcp.tool.ToolDataCatalog.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import com.project.backend.data.DataManifest;
import com.project.backend.mcp.tool.ToolDataCatalog;

class KnowledgeRetrievalTests {
    static ToolDataCatalog catalog;
    static KnowledgeRetriever retriever;
    @BeforeAll static void load(){catalog=new ToolDataCatalog(DataManifest.classpath());retriever=KnowledgeRetriever.lexical(catalog);}
    @Test void vibrationParaphrasesSelectTheModelFactWithoutFloodOrFilterGuidance(){
        for(String query:List.of("탈수할때 세탁기 본체가 많이 흔들리면서 원 위치에서 크게 벗어나요.","진동이 심해요","탈수 중 원래 자리에서 움직여요")){
            var result=retriever.retrieve(query,"WASHER","FX24ENER","MANUAL",null,5);
            assertThat(result.documents()).extracting(KnowledgeRetriever.Hit::id).contains("B17-MFL71792818-topic_66-009")
                    .doesNotContain("B16-MFL71792818-topic_65-008","T4-016","T4-036");
            assertThat(result.safety()).isEmpty();
            assertThat(result.documents()).allSatisfy(hit->assertThat(catalog.structuredKnowledgeById.get(hit.id()).fields().get("symptoms").toString())
                    .doesNotContain("침수","급수","배수","거름망"));
        }
    }
    @Test void displayedSensorFaultNeedsTheActualLabel(){
        var result=retriever.retrieve("진동 센서 이상이라고 표시돼요","WASHER","FX24ENER","MANUAL",null,3);
        assertThat(result.documents().getFirst()).extracting(KnowledgeRetriever.Hit::id,KnowledgeRetriever.Hit::match)
                .containsExactly("B16-MFL71792818-topic_65-008","EXACT_DISPLAY_LABEL");
    }
    @Test void safetyIsFilteredByTheSameExactModelRule(){
        var right=retriever.retrieve("세탁기 밑면이 물에 잠겼어요","WASHER","F24WDWPR","MANUAL",null,3);
        assertThat(right.safety()).extracting(KnowledgeRetriever.Hit::id).contains("T4-016").doesNotContain("T4-036");
        var wrong=retriever.retrieve("세탁기가 침수됐어요","WASHER","FX24ENER","MANUAL",null,3);
        assertThat(wrong.safety()).isEmpty();assertThat(wrong.documents()).isEmpty();
    }
    @Test void categoryModelAndMissingInformationDoNotBecomeUniversalApplicability(){
        for(String model:Arrays.asList(null,"UNKNOWN999")){
            var result=retriever.retrieve("세탁기가 흔들려요","WASHER",model,"MANUAL",null,3);
            assertThat(result.documents()).isEmpty();assertThat(result.safety()).isEmpty();
        }
        assertThat(retriever.retrieve("본체가 흔들려요","DRYER","FX24ENER","MANUAL",null,3).documents()).isEmpty();
        assertThat(retriever.eligible("T5-001","WASHER","FX24ENER")).isTrue();
        assertThat(retriever.verifiedCommon("T5-001")).isTrue();
        assertThat(retriever.verifiedCommon("T1-001")).isFalse();
    }
    @Test void exactCodesAndSpacedLabelsUseLiteralBoundaries(){
        for(String query:List.of("OE","O E 표시가 떠요")){
            var result=retriever.retrieve(query,"WASHER","F24WDWPR","MANUAL",null,3);
            assertThat(result.documents()).anySatisfy(hit->assertThat(hit).extracting(KnowledgeRetriever.Hit::id,KnowledgeRetriever.Hit::match).containsExactly("T2-003","EXACT_ERROR_CODE"));
        }
        assertThat(retriever.retrieve("SOMEOTHER","WASHER","F24WDWPR","MANUAL",null,3).documents()).isEmpty();
    }
    @Test void categoryWordsAndUnknownSubjectsDoNotPadTheResultCount(){
        for(String query:List.of("세탁기","홀로그램 레이저 출력","세탁기 연기는 없어요","진동 없어요")){
            var result=retriever.retrieve(query,"WASHER","FX24ENER","MANUAL",null,5);
            assertThat(result.documents()).isEmpty();assertThat(result.safety()).isEmpty();
        }
    }
    @Test void coolingComplaintsSelectCoolingFactsNotInteriorFacts(){
        for(String query:List.of("냉장고가 시원하지 않아요.","냉장이 안 돼요","냉장·냉동이 잘되지 않아요","냉동이 약해요")){
            var result=retriever.retrieve(query,"REFRIGERATOR","S836MEE022","MANUAL",null,3);
            assertThat(result.documents()).extracting(KnowledgeRetriever.Hit::id).as(query).contains("B15-MFL71401523-no_cooling")
                    .doesNotContain("B16-MFL71401523-topic_46-000","B16-MFL71401523-topic_46-001");
        }
        // "냉장고 안" is the interior, not a cooling failure.
        var odor=retriever.retrieve("냉장고 안에서 냄새가 나요","REFRIGERATOR","S836MEE022","MANUAL",null,3);
        assertThat(odor.documents()).extracting(KnowledgeRetriever.Hit::id).contains("B16-MFL71401523-topic_46-000")
                .doesNotContain("B15-MFL71401523-no_cooling","B15-MFL71401523-weak_cooling");
        assertThat(retriever.retrieve("냉장고가 안 켜져요","REFRIGERATOR","S836MEE022","MANUAL",null,3).documents())
                .extracting(KnowledgeRetriever.Hit::id).doesNotContain("B15-MFL71401523-no_cooling","B15-MFL71401523-weak_cooling");
    }
    @Test void negatedVibrationDoesNotOverrideTheDrainSymptom(){
        var result=retriever.retrieve("진동은 없고 물이 안 빠져요","WASHER","F24WDWPR","MANUAL",null,3);
        assertThat(result.documents()).extracting(KnowledgeRetriever.Hit::id).contains("T3-001").doesNotContain("T2-010","T3-137");
    }
}
