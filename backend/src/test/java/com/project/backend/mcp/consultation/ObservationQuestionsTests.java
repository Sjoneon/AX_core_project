package com.project.backend.mcp.consultation;

import static java.util.Objects.requireNonNull;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.project.backend.conversation.ProductCategory;

class ObservationQuestionsTests {
    @Test void onlyAskedQuestionsAndTheirOptionsAreAccepted(){
        assertThat(ObservationQuestions.of(ProductCategory.REFRIGERATOR,"COOLING")).extracting((ObservationQuestions.Question element)->requireNonNull(element).id()).containsExactly("DISPLAY","SOUND","FROST");
        assertThat(ObservationQuestions.of(ProductCategory.WASHER,"VIBRATION")).isEmpty();
        assertThat(ObservationQuestions.validate(ProductCategory.REFRIGERATOR,"COOLING",Map.of("SOUND","NO"))).containsExactly(Map.entry("SOUND","NO"));
        assertThat(ObservationQuestions.validate(ProductCategory.REFRIGERATOR,"COOLING",null)).isEmpty();
        assertThatThrownBy(()->ObservationQuestions.validate(ProductCategory.REFRIGERATOR,"COOLING",Map.of("SOUND","MAYBE"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ObservationQuestions.validate(ProductCategory.REFRIGERATOR,"COOLING",Map.of("SMELL","YES"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ObservationQuestions.validate(ProductCategory.WASHER,"VIBRATION",Map.of("SOUND","NO"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ObservationQuestions.validate(ProductCategory.REFRIGERATOR,"COOLING",List.of("SOUND"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void storedAnswersReadBackAsCustomerLinesWithoutUnknowns(){
        var stored=ObservationQuestions.stored(Map.of("OBS_SOUND","NO","OBS_FROST","UNKNOWN","CHECK_DONE","NOT_RESOLVED"));
        assertThat(stored).containsOnly(Map.entry("SOUND","NO"),Map.entry("FROST","UNKNOWN"));
        assertThat(ObservationQuestions.lines(ProductCategory.REFRIGERATOR,"COOLING",Map.of("FROST","BACK_WALL","SOUND","NO","DISPLAY","UNKNOWN")))
                .containsExactly("작동 소리: 소리가 안 나요","냉동실 성에: 안쪽 벽에 두껍게 있어요");
    }
}
