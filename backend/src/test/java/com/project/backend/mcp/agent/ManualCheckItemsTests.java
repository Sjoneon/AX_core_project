package com.project.backend.mcp.agent;

import static org.assertj.core.api.Assertions.*;
import static com.project.backend.mcp.tool.ToolDataCatalog.text;
import java.util.*;
import org.junit.jupiter.api.*;
import com.project.backend.data.DataManifest;
import com.project.backend.mcp.tool.ToolDataCatalog;

class ManualCheckItemsTests {
    static ToolDataCatalog catalog;
    @BeforeAll static void load(){catalog=new ToolDataCatalog(DataManifest.classpath());}
    // Reviewed observation items of the packaged data. A new item must be read and added here before it reaches answers.
    static final Set<String> REVIEWED=Set.of("막힌 노즐 구멍","배수 호스 연결 부위","설명서의 사용 가능한 식기 기준","차단기","차단기 전원","플러그 연결",
            "세탁기 위에 건조기가 설치되어야 하는 연결 조건","연결 대상 모델의 블루투스 지원","의류 취급표시와 적합 코스","의류 취급표시의 건조 조건",
            "보관 온도, 문 닫힘, 식품 배치와 주위 설치 환경","설치 바닥과 벽 재질에 따른 진동","정전 여부와 전원 플러그 연결",
            "급수구 거름망과 수압","뜨거운 온수 단독 사용에 따른 거품 넘침","이 모델의 전원은 220V 전용임");
    @Test void everyExtractedItemIsAReviewedObservation(){
        var found=new TreeSet<String>();
        for(var source:catalog.knowledge)if("MANUAL".equals(text(source,"topic")))found.addAll(ManualCheckItems.of(catalog.structuredKnowledgeById.get(text(source,"id"))));
        assertThat(found).isEqualTo(new TreeSet<>(REVIEWED));
    }
    @Test void refrigeratorCoolingFactsKeepChecksAndDropOperatingSteps(){
        assertThat(ManualCheckItems.of(catalog.structuredKnowledgeById.get("B15-MFL71401523-weak_cooling"))).containsExactly("보관 온도, 문 닫힘, 식품 배치와 주위 설치 환경");
        assertThat(ManualCheckItems.of(catalog.structuredKnowledgeById.get("B15-MFL71401523-no_cooling"))).containsExactly("정전 여부와 전원 플러그 연결");
        // "1℃ 낮추는 설명서 안내", 밀폐 보관, 청소 are actions, not observations.
        assertThat(ManualCheckItems.of(catalog.structuredKnowledgeById.get("B16-MFL71401523-topic_46-000"))).isEmpty();
        var service=catalog.structuredKnowledgeById.get("B16-MFL71401523-topic_46-001");
        assertThat(ManualCheckItems.stopSelfCare(service)).isTrue();assertThat(ManualCheckItems.of(service)).isEmpty();
    }
}
