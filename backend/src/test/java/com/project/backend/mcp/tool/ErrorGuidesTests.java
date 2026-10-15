package com.project.backend.mcp.tool;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import com.project.backend.conversation.ProductCategory;
import com.project.backend.data.DataManifest;
import com.project.backend.mcp.consultation.ErrorCodeParser;

class ErrorGuidesTests {
    static ToolDataCatalog catalog;
    @BeforeAll static void load(){catalog=new ToolDataCatalog(DataManifest.classpath());}
    @SuppressWarnings("unchecked") static List<String> list(Map<String,Object> guide,String key){return (List<String>)guide.get(key);}

    @Test void aManualTableReachesEveryModelItCoversIncludingColorVariants(){
        // FX24ENER is a color variant of FX24KNTR; their manual shows "배수 안됨" instead of OE.
        var drain=ErrorGuides.guide(catalog,ProductCategory.WASHER,"FX24ENER","OE");
        assertThat(drain).containsEntry("shown","배수 안됨").containsEntry("code","OE");
        assertThat(list(drain,"steps")).hasSize(6).first().asString().startsWith("배수 호스 끝이 지면으로부터 1 m 이상의 높이에");
        assertThat((String)drain.get("source_url")).isEqualTo("https://gscs-manual.lge.com/DFZ/MFL71792818/ko-kr/main.html#topic_65");
        // F21VDSKR shares the F24WDWPR manual although it is not a color variant.
        var level=ErrorGuides.guide(catalog,ProductCategory.WASHER,"F21VDSKR","PE");
        assertThat(list(level,"steps")).isEmpty();
        assertThat(list(level,"contact")).containsExactly("전원 플러그를 뺀 후 LG전자 서비스 센터(1544-7777)에 문의하세요.");
        assertThat((String)level.get("cause")).contains("물 조절 센서에 이상");
        assertThat(ErrorGuides.guide(catalog,ProductCategory.WASHER,"UNKNOWN-MODEL","OE")).isNull();
    }
    @Test void labelsAndSevenSegmentSpellingsShareOneCanonicalCode(){
        assertThat(ErrorGuides.canonical(catalog,ProductCategory.WASHER,"배수 안됨")).isEqualTo("OE");
        assertThat(ErrorGuides.canonical(catalog,ProductCategory.WASHER,"dE2")).isEqualTo("DE");
        assertThat(ErrorGuides.canonical(catalog,ProductCategory.DRYER,"DE4")).isEqualTo("DE2");
        assertThat(ErrorGuides.canonical(catalog,ProductCategory.DRYER,"컴프레서 이상")).isEqualTo("LE2");
        assertThat(ErrorGuides.canonical(catalog,ProductCategory.DISHWASHER,"0E")).isEqualTo("OE");
        // A dryer showing "컴프레서 이상" and one showing LE2 get the same guidance.
        assertThat(ErrorGuides.guide(catalog,ProductCategory.DRYER,"RD25TS","LE2")).containsEntry("shown","컴프레서 이상");
        assertThat(ErrorGuides.guide(catalog,ProductCategory.DRYER,"RG20KN","LE2")).containsEntry("shown","LEz, AE");
    }
    @Test void codesSharedByTwoRowsOfOneManualCombineTheirStepsOnce(){
        var level=ErrorGuides.guide(catalog,ProductCategory.DISHWASHER,"DUE6BGL1E","AE");
        assertThat(list(level,"steps")).containsExactly("제품이 수평으로 설치되어 있지 않나요? 해당 설치 전문점에 재설치를 요청하세요.",
                "일반 주방용 액체 세제를 사용했나요? 주방용 액체 세제는 식기세척기에 적합하지 않습니다. 식기세척기 전용 세제를 사용하세요. 세척 후 거품이 남으면 린스(헹굼/건조 보조제) 넣기의 조치 방법을 참조하여 거품을 제거하세요.");
        assertThat(list(level,"contact")).containsExactly("LG전자 서비스 센터(1544-7777)에 문의하세요.");
    }
    @Test void refrigeratorGuidanceIsCategoryWideAndKeepsTheOfficialCause(){
        var fan=ErrorGuides.guide(catalog,ProductCategory.REFRIGERATOR,"S836MEE022","ERFF");
        assertThat(fan).containsEntry("meaning","냉동실 팬 이상").containsEntry("cause","팬 주변 결빙 또는 팬 모터 이상 가능");
        assertThat(list(fan,"steps")).hasSize(3).last().asString().contains("정상 냉각까지 약24~48시간");
        var internal=ErrorGuides.guide(catalog,ProductCategory.REFRIGERATOR,"S836MEE022","ERDH");
        assertThat(list(internal,"steps")).hasSize(2).first().asString().startsWith("냉장고의 전원 플러그를 벽면 콘센트에서 뽑으세요. 5분 정도");
        assertThat(ErrorGuides.guide(catalog,ProductCategory.REFRIGERATOR,"Z330MEEF11","ERCO")).containsEntry("meaning","내부 시스템 이상");
    }
    @Test void displayMessagesNeedADisplaySentenceAndCodesBecomeCanonical(){
        assertThat(ErrorCodeParser.find(catalog,ProductCategory.WASHER,"세탁기에 배수 안됨이라고 떠요")).isEqualTo("OE");
        assertThat(ErrorCodeParser.find(catalog,ProductCategory.WASHER,"dE2 표시가 나와요")).isEqualTo("DE");
        assertThat(ErrorCodeParser.find(catalog,ProductCategory.DRYER,"필터 장착했어요")).isNull();
        assertThat(ErrorCodeParser.find(catalog,ProductCategory.DRYER,"필터 장착 표시가 떠요")).isEqualTo("1F");
        assertThat(ErrorCodeParser.find(catalog,ProductCategory.REFRIGERATOR,"Er dH가 표시돼요")).isEqualTo("ERDH");
        assertThat(ErrorCodeParser.find(catalog,ProductCategory.REFRIGERATOR,"ER FF 떠요")).isEqualTo("ERFF");
    }
    @Test void errorLinksReachTheCostTableThroughTheCanonicalCode(){
        assertThat(SymptomRepairEstimate.of(catalog,ProductCategory.WASHER,null,"FE")).containsEntry("basis","ERROR");
        assertThat(((Map<?,?>)SymptomRepairEstimate.of(catalog,ProductCategory.REFRIGERATOR,null,"ERDH").get("base")).get("parts")).isEqualTo(List.of("제상 센서","제상 히터"));
        assertThat(((Map<?,?>)SymptomRepairEstimate.of(catalog,ProductCategory.DRYER,null,"드럼모터이상").get("base")).get("parts")).isEqualTo(List.of("드럼 모터"));
        // No official cause names a part for Er CO, so no amount is invented.
        assertThat(SymptomRepairEstimate.of(catalog,ProductCategory.REFRIGERATOR,null,"ERCO")).containsEntry("status","NO_DATA");
    }
}
