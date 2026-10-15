package com.project.backend.mcp.consultation;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.project.backend.conversation.ProductCategory;

class RiskRulesTests {
    @ParameterizedTest
    @ValueSource(strings={"폭우로 세탁기가 물에 잠겼어요. 지금은 건조됐는데 켜도 되나요?","냉장고가 침수됐어요","물에 잠긴 식기세척기를 켜도 되나요?"})
    void floodedAppliancesUseExistingElectricalWaterRiskCase(String input){
        var finding=RiskRules.detect(input,ProductCategory.WASHER);
        assertThat(finding.code()).isEqualTo("RC09");assertThat(finding.emergency()).isFalse();
    }
    @ParameterizedTest
    @ValueSource(strings={"탈수할때 세탁기 본체가 많이 흔들리면서 원 위치에서 크게 벗어나요.","세탁기 안의 세탁물이 물에 잠겨 있어요","식기세척기 그릇이 물에 잠겼어요"})
    void vibrationAndContentsInWaterDoNotBecomeApplianceFlooding(String input){
        assertThat(RiskRules.detect(input,ProductCategory.WASHER)).isNull();
    }
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
