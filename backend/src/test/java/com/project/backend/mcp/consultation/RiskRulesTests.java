package com.project.backend.mcp.consultation;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.project.backend.conversation.ProductCategory;

class RiskRulesTests {
    @ParameterizedTest
    @EnumSource(ProductCategory.class)
    void gasAndRefrigerantWarningsWorkAcrossAllSupportedCategories(ProductCategory category){
        assertThat(RiskRules.detect("냉매 누출과 가스 냄새",category).code()).isEqualTo("RC11");
    }
    @ParameterizedTest
    @ValueSource(strings={"기름 묻은 수건을 건조기에 넣어도 되나요","휘발유가 묻은 작업복 말려도 돼요?","시너에 젖은 걸레 빨래"})
    void flammableLaundryIsASafetySuspectForDryers(String input){
        var finding=RiskRules.detect(input,ProductCategory.DRYER);
        assertThat(finding.code()).isEqualTo("RC14");assertThat(finding.emergency()).isFalse();
        assertThat(RiskRules.questions("RC14")).hasSize(2);
    }
    @Test void flammableRuleIsLimitedToLaundryCategoriesAndBurningSmellStaysFirst(){
        assertThat(RiskRules.detect("기름 묻은 행주",ProductCategory.REFRIGERATOR)).isNull();
        assertThat(RiskRules.detect("건조기에서 타는 냄새가 나요",ProductCategory.DRYER).code()).isEqualTo("RC04");
        assertThat(RiskRules.serviceLine("1544-7777")).contains("1544-7777");
    }
}
