package com.project.backend.product;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserProductServiceTests {
    @ParameterizedTest
    @ValueSource(strings = {"전남광주통합특별시", "전남광주", " 전남광주통합특별시 "})
    void normalizesJeonnamGwangju(String input) {
        assertThat(UserProductService.normalizeSido(input)).isEqualTo("전남광주");
    }

    @ParameterizedTest
    @CsvSource({
            "서울특별시,서울", "부산광역시,부산", "대구광역시,대구", "인천광역시,인천",
            "광주광역시,광주", "대전광역시,대전", "울산광역시,울산", "세종특별자치시,세종",
            "경기도,경기", "강원도,강원", "강원특별자치도,강원", "충청북도,충북", "충청남도,충남",
            "전라북도,전북", "전북특별자치도,전북", "전라남도,전남", "경상북도,경북", "경상남도,경남",
            "제주도,제주", "제주특별자치도,제주"
    })
    void preservesExistingRegions(String input, String expected) {
        assertThat(UserProductService.normalizeSido(input)).isEqualTo(expected);
        assertThat(UserProductService.normalizeSido(expected)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "화성", "가상통합특별시", "전남광주통합특별시 북구"})
    void rejectsUnknownRegions(String input) {
        assertThatThrownBy(() -> UserProductService.normalizeSido(input))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason()).isEqualTo("시·도를 확인해 주세요.");
                });
    }
}
