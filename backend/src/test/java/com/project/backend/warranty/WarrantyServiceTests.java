package com.project.backend.warranty;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import com.project.backend.conversation.ProductCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WarrantyServiceTests {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-13T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    private WarrantyService exampleService() {
        // The user's 2-year/4-year example is deliberately a test fixture, not an LG policy.
        var product = new WarrantyModels.Policy("EXAMPLE_PRODUCT", ProductCategory.WASHER, "PRODUCT", "제품 전체 보증", null, 24, null, null, true, true, null, List.of("시험용 예시"));
        var part = new WarrantyModels.Policy("EXAMPLE_MOTOR", ProductCategory.WASHER, "CORE_PART", "예시 주요 모터", "EXAMPLE_MOTOR", 48, null, null, true, false, null, List.of("시험용 예시"));
        var catalog = new WarrantyCatalog(new WarrantyModels.Catalog("TEST_ONLY", "https://example.invalid/test-policy", LocalDate.of(2026, 10, 13), List.of(product, part)));
        return new WarrantyService(catalog, CLOCK);
    }

    private WarrantyModels.Request example(LocalDate purchase) {
        return new WarrantyModels.Request(ProductCategory.WASHER, null, purchase, null, null, "EXAMPLE_MOTOR", "GENERAL", true, true);
    }

    @Test void productExpiresWhileCorePartRemainsCovered() {
        var result = exampleService().check(example(LocalDate.of(2023, 10, 13)));
        assertThat(result.productWarranty().periodStatus()).isEqualTo("EXPIRED");
        var part = result.partWarranties().getFirst();
        assertThat(part.periodStatus()).isEqualTo("IN_WARRANTY");
        assertThat(part.expiresOn()).isEqualTo(LocalDate.of(2027, 10, 13));
        assertThat(part.partFee()).isEqualTo("CONDITIONAL_FREE");
        assertThat(part.laborFee()).isEqualTo("PAID");
        assertThat(part.visitFee()).isEqualTo("CONFIRM_REQUIRED");
        assertThat(result.headline()).contains("전체 보증은 만료", "별도 보증은 남아");
        assertThat(exampleService().explain(result)).contains("제품 전체 보증: 2년(24개월) · 만료",
                "주요 부품 보증 — 예시 주요 모터: 4년(48개월) · 보증기간 안", "출장비 추가 확인 필요");
    }

    @Test void expiryDateIsInclusive() {
        assertThat(exampleService().check(example(LocalDate.of(2024, 10, 13))).productWarranty().periodStatus()).isEqualTo("IN_WARRANTY");
        assertThat(exampleService().check(example(LocalDate.of(2024, 10, 12))).productWarranty().periodStatus()).isEqualTo("EXPIRED");
    }

    @Test void chatSummaryStatesPeriodsAndTheOfficialPolicyWithoutFeeStatuses(){
        var service=exampleService();var result=service.check(example(LocalDate.of(2023,10,13)));
        assertThat(service.summarize(result)).contains("전체 보증은 만료","2025-10-13","2027-10-13",
                "실제 무상 수리 승인은 다릅니다",result.sourceUrl());
        assertThat(service.summarize(result)).doesNotContain("0원","부품비","기술료","출장비");
    }

    @Test void addsCalendarMonthsAcrossLeapDay() {
        assertThat(exampleService().check(example(LocalDate.of(2024, 2, 29))).productWarranty().expiresOn()).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test void purchaseDateStartsWarrantyEvenWhenDeliveryDiffers() {
        var request = new WarrantyModels.Request(ProductCategory.WASHER, null, LocalDate.of(2024, 10, 1), LocalDate.of(2024, 10, 20), null, "EXAMPLE_MOTOR", null, true, true);
        var result = exampleService().check(request);
        assertThat(result.basisSource()).isEqualTo("PURCHASE");
        assertThat(result.basisDate()).isEqualTo(LocalDate.of(2024, 10, 1));
        assertThat(result.productWarranty().periodStatus()).isEqualTo("EXPIRED");
        assertThat(result.productWarranty().expiresOn()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test void missingDateNeverProducesExpiryOrZeroFees() {
        var result = exampleService().check(example(null));
        assertThat(result.productWarranty().expiresOn()).isNull();
        assertThat(result.productWarranty().periodStatus()).isEqualTo("UNKNOWN");
        assertThat(result.productWarranty().partFee()).isEqualTo("CONFIRM_REQUIRED");
    }

    @Test void missingProofKeepsPurchaseBasedPeriodsButDoesNotConfirmFreeRepair() {
        var result = exampleService().check(new WarrantyModels.Request(ProductCategory.WASHER, null, LocalDate.of(2026, 1, 1), null, null, "EXAMPLE_MOTOR", null, false, true));
        assertThat(result.productWarranty().periodStatus()).isEqualTo("IN_WARRANTY");
        assertThat(result.partWarranties().getFirst().periodStatus()).isEqualTo("IN_WARRANTY");
        assertThat(result.productWarranty().partFee()).isEqualTo("CONFIRM_REQUIRED");
        assertThat(result.partWarranties().getFirst().partFee()).isEqualTo("CONFIRM_REQUIRED");
        assertThat(result.notes()).noneMatch(note -> note.contains("실제 만료일은 추가 확인"));
    }

    @Test void registeredRefrigeratorPurchaseCalculatesExpiryWithoutProofOrHouseholdFlags() {
        var service = new WarrantyService(new WarrantyCatalog(), Clock.fixed(Instant.parse("2026-10-15T03:00:00Z"), ZoneId.of("Asia/Seoul")));
        var result = service.check(new WarrantyModels.Request(ProductCategory.REFRIGERATOR, "S836MEE022",
                LocalDate.of(2026, 5, 5), null, null, null, null, null, null));
        assertThat(result.basisSource()).isEqualTo("PURCHASE");
        assertThat(result.productWarranty().expiresOn()).isEqualTo(LocalDate.of(2027, 5, 5));
        assertThat(result.productWarranty().periodStatus()).isEqualTo("IN_WARRANTY");
        assertThat(result.productWarranty().applicability()).isEqualTo("NEEDS_CONFIRMATION");
        assertThat(result.productWarranty().partFee()).isEqualTo("CONFIRM_REQUIRED");
        assertThat(result.productWarranty().laborFee()).isEqualTo("CONFIRM_REQUIRED");
        assertThat(result.productWarranty().visitFee()).isEqualTo("CONFIRM_REQUIRED");
        assertThat(result.partWarranties()).allMatch(row -> row.expiresOn() == null && "UNKNOWN".equals(row.periodStatus()));
        assertThat(service.summarize(result)).contains("구매일 기준", "2027-05-05", "보증기간 안");
    }

    @Test void unknownUseConditionDoesNotApplyHouseholdPolicy() {
        var result = exampleService().check(new WarrantyModels.Request(ProductCategory.WASHER, null, LocalDate.of(2026, 1, 1), null, null, "EXAMPLE_MOTOR", null, true, false));
        assertThat(result.productWarranty().applicability()).isEqualTo("NEEDS_CONFIRMATION");
    }

    @Test void invalidDatesAndUnknownPartAreRejected() {
        assertThatThrownBy(() -> exampleService().check(example(LocalDate.of(2027, 1, 1)))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> exampleService().check(new WarrantyModels.Request(ProductCategory.WASHER, null, LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 1), null, "EXAMPLE_MOTOR", null, true, true))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> exampleService().check(new WarrantyModels.Request(ProductCategory.WASHER, null, null, null, null, "INVENTED", null, true, true))).isInstanceOf(ResponseStatusException.class);
    }

    @Test void missingPolicyPeriodIsUnknown() {
        var p = new WarrantyModels.Policy("UNVERIFIED", ProductCategory.WASHER, "PRODUCT", "제품 전체 보증", null, null, null, null, null, null, null, List.of());
        var catalog = new WarrantyCatalog(new WarrantyModels.Catalog("TEST", "https://example.invalid", LocalDate.of(2026, 10, 13), List.of(p)));
        var request = new WarrantyModels.Request(ProductCategory.WASHER, null, LocalDate.of(2025, 1, 1), null, null, null, null, true, true);
        assertThat(new WarrantyService(catalog, CLOCK).check(request).productWarranty().periodStatus()).isEqualTo("UNKNOWN");
    }

    @ParameterizedTest @EnumSource(value = ProductCategory.class, names = "AIR_CONDITIONER", mode = EnumSource.Mode.EXCLUDE)
    void everyRequestedCategoryHasSeparateProductAndPartPolicies(ProductCategory category) throws Exception {
        var service = new WarrantyService(new WarrantyCatalog(), CLOCK);
        var result = service.check(new WarrantyModels.Request(category, null, null, null, null, null, "GENERAL", false, false));
        assertThat(result.productWarranty().scope()).isEqualTo("PRODUCT");
        assertThat(result.partWarranties()).isNotEmpty().allMatch(p -> "CORE_PART".equals(p.scope()));
        assertThat(result.partWarranties()).allMatch(p -> "UNKNOWN".equals(p.periodStatus()));
        assertThat(result.productWarranty().periodMonths()).isEqualTo(12);
    }

    @Test void ddMotorNeedsKnownProductionCutoff() throws Exception {
        var service = new WarrantyService(new WarrantyCatalog(), CLOCK);
        var unknown = service.check(new WarrantyModels.Request(ProductCategory.WASHER, null, LocalDate.of(2025, 1, 1), null, null, "DD_MOTOR", null, true, true));
        assertThat(unknown.partWarranties().getFirst().periodStatus()).isEqualTo("UNKNOWN");
        var confirmed = service.check(new WarrantyModels.Request(ProductCategory.WASHER, null, LocalDate.of(2025, 1, 1), null, LocalDate.of(2024, 12, 1), "DD_MOTOR", null, true, true));
        assertThat(confirmed.partWarranties().getFirst().periodStatus()).isEqualTo("IN_WARRANTY");
    }

    @Test void unsupportedProductTypeIsRejected() throws Exception {
        var service = new WarrantyService(new WarrantyCatalog(), CLOCK);
        assertThatThrownBy(() -> service.check(new WarrantyModels.Request(ProductCategory.WASHER, null,
                LocalDate.of(2025, 1, 1), null, null, "STANDARD_MOTOR", "COMMERCIAL", true, true)))
                .isInstanceOf(ResponseStatusException.class);
    }
}
