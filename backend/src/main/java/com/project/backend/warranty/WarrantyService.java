package com.project.backend.warranty;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WarrantyService {
    private final WarrantyCatalog catalog;
    private final Clock clock;

    @Autowired
    public WarrantyService(WarrantyCatalog catalog) {
        this(catalog, Clock.system(ZoneId.of("Asia/Seoul")));
    }

    public WarrantyService(WarrantyCatalog catalog, Clock clock) {
        this.catalog = catalog;
        this.clock = clock;
    }

    public WarrantyModels.Catalog policies() { return catalog.data(); }

    public WarrantyModels.Result check(WarrantyModels.Request request) {
        if (request == null || request.category() == null) throw badRequest("제품 품목을 선택해 주세요.");
        if (!request.category().isSupported()) throw badRequest("에어컨은 지원 종료된 품목입니다.");
        LocalDate today = LocalDate.now(clock);
        for (var date : new LocalDate[]{request.purchaseDate(), request.deliveryDate(), request.productionDate()}) {
            if (date != null && date.isAfter(today)) throw badRequest("미래 날짜는 사용할 수 없습니다.");
        }
        if (request.deliveryDate() != null && request.purchaseDate() == null) {
            throw badRequest("배송일을 입력하려면 구매일도 입력해 주세요.");
        }
        if (request.deliveryDate() != null && request.deliveryDate().isBefore(request.purchaseDate())) {
            throw badRequest("배송일은 구매일보다 빠를 수 없습니다.");
        }
        LocalDate basis = request.purchaseDate();
        String basisSource = basis == null ? "NEEDS_DATE" : "PURCHASE";
        var selected = catalog.data().policies().stream()
                .filter(policy -> policy.category() == request.category()).toList();
        String variant = normalize(request.coreVariant());
        if (variant != null && selected.stream().noneMatch(policy -> variant.equals(policy.variant()))) {
            throw badRequest("해당 품목에 없는 주요 부품 유형입니다.");
        }
        String productType = normalize(request.productType());
        if (productType != null && !List.of("GENERAL", "UNKNOWN").contains(productType)) {
            throw badRequest("제품 유형을 확인해 주세요.");
        }
        var generalPolicies = selected.stream().filter(p -> "PRODUCT".equals(p.scope())).toList();
        WarrantyModels.Row product = generalPolicies.size() == 1
                ? row(generalPolicies.getFirst(), request, basis, today, productType, true)
                : unknownProduct();
        var parts = selected.stream().filter(p -> "CORE_PART".equals(p.scope()))
                .filter(p -> variant == null || variant.equals(p.variant()))
                .map(p -> row(p, request, basis, today, productType, variant != null)).toList();
        var notes = new ArrayList<String>();
        notes.add("제품 전체 보증은 일반적인 고장에 적용됩니다. 주요 부품의 별도 보증은 명시된 부품에만 적용됩니다.");
        notes.add("제품 전체 보증이 끝나도 해당 주요 부품 보증은 남아 있을 수 있습니다. 다른 부품까지 기간이 연장되지는 않습니다.");
        notes.add("기간 계산과 실제 무상 수리 승인은 다릅니다. 정상 사용 여부·고장 부품·구입 증빙·모델 적용 조건은 점검 시 확인합니다.");
        if (basis != null) notes.add("등록된 구매일을 보증 시작일로 계산합니다. 배송일이나 증빙 확인 여부로 시작일을 바꾸지 않습니다.");
        notes.add("주요 부품 보증의 부품비·기술료·출장비는 별도로 확인합니다. 확인되지 않은 출장비를 0원으로 계산하지 않습니다.");
        if (basis == null) notes.add("구매일을 입력하면 만료일을 계산할 수 있습니다.");
        if (!Boolean.TRUE.equals(request.proofAvailable())) notes.add("구입 증빙은 실제 무상 적용을 확인할 때 필요합니다. 구매일 기준 기간 계산과는 별개입니다.");
        if (!Boolean.TRUE.equals(request.domesticNewHousehold())) notes.add("국내 신품의 가정용 사용 여부가 확인되지 않았습니다. 영업용·중고·해외 구매·계약 제품은 별도 조건을 확인해야 합니다.");
        if (variant == null) notes.add("주요 부품 유형을 모르면 가능한 정책을 보여 드립니다. 모든 정책이 이 제품에 적용되는 것은 아닙니다.");
        String headline = headline(product, parts);
        return new WarrantyModels.Result(request.category(), request.modelName(), today, basis, basisSource,
                product, parts, headline, List.copyOf(notes), catalog.data().version(),
                catalog.data().sourceUrl(), catalog.data().verifiedOn());
    }

    private WarrantyModels.Row row(WarrantyModels.Policy policy, WarrantyModels.Request request,
            LocalDate basis, LocalDate today, String productType, boolean variantKnown) {
        boolean policyKnown = variantKnown;
        var conditions = new ArrayList<>(policy.conditions());
        if (policy.requiredProductType() != null && !Objects.equals(policy.requiredProductType(), productType)) policyKnown = false;
        if (policy.producedFrom() != null && (request.productionDate() == null
                || request.productionDate().isBefore(policy.producedFrom()))) policyKnown = false;
        // Purchase date starts the period. Evidence/use conditions qualify fees, not the date calculation.
        LocalDate expiry = policyKnown && basis != null && policy.periodMonths() != null ? basis.plusMonths(policy.periodMonths()) : null;
        String status = expiry == null ? "UNKNOWN" : today.isAfter(expiry) ? "EXPIRED" : "IN_WARRANTY";
        boolean applies = policyKnown && Boolean.TRUE.equals(request.domesticNewHousehold())
                && Boolean.TRUE.equals(request.proofAvailable());
        String feeStatus = applies ? status : "UNKNOWN";
        return new WarrantyModels.Row(policy.id(), policy.scope(), policy.name(), policy.variant(),
                policy.periodMonths(), expiry, status, applies ? "USER_DECLARED_CONDITIONAL" : "NEEDS_CONFIRMATION",
                fee(feeStatus, policy.freePart()), fee(feeStatus, policy.freeLabor()), fee(feeStatus, policy.freeVisit()),
                List.copyOf(conditions));
    }

    private String fee(String status, Boolean waived) {
        if ("UNKNOWN".equals(status) || waived == null) return "CONFIRM_REQUIRED";
        if ("EXPIRED".equals(status)) return "PAID";
        return waived ? "CONDITIONAL_FREE" : "PAID";
    }

    private WarrantyModels.Row unknownProduct() {
        return new WarrantyModels.Row("UNKNOWN", "PRODUCT", "제품 전체 보증", null, null, null,
                "UNKNOWN", "NEEDS_CONFIRMATION", "CONFIRM_REQUIRED", "CONFIRM_REQUIRED", "CONFIRM_REQUIRED",
                List.of("적용 가능한 제품 전체 보증 정책을 확인해 주세요."));
    }

    private String headline(WarrantyModels.Row product, List<WarrantyModels.Row> parts) {
        boolean partActive = parts.stream().anyMatch(row -> "IN_WARRANTY".equals(row.periodStatus()));
        if ("EXPIRED".equals(product.periodStatus()) && partActive) {
            return "제품 전체 보증은 만료됐지만 주요 부품의 별도 보증은 남아 있습니다. 해당 부품 고장인지 확인이 필요합니다.";
        }
        if ("IN_WARRANTY".equals(product.periodStatus())) return "등록 구매일 기준 제품 전체 보증기간 안입니다. 실제 무상 적용과 주요 부품 조건은 별도로 확인합니다.";
        if ("EXPIRED".equals(product.periodStatus())) return "제품 전체 보증은 만료됐습니다. 주요 부품별 별도 보증 여부를 확인하세요.";
        return "제품 전체 보증과 주요 부품 보증을 나눠 안내합니다. 적용 조건·증빙이 확인되면 만료일을 계산할 수 있습니다.";
    }

    public String explain(WarrantyModels.Result result) {
        var lines = new ArrayList<String>();
        lines.add(result.headline());
        lines.add("제품 전체 보증: " + describe(result.productWarranty()));
        for (var part : result.partWarranties()) lines.add("주요 부품 보증 — " + part.name() + ": " + describe(part));
        lines.addAll(result.notes());
        lines.add("정책 근거: " + result.sourceUrl() + " (확인일 " + result.policyVerifiedOn() + ")");
        return String.join("\n", lines);
    }

    /** Short chat answer: periods only. Fee statuses without amounts are left to the inspection, not listed. */
    public String summarize(WarrantyModels.Result result) {
        var lines=new ArrayList<String>();lines.add(result.headline());
        lines.add("제품 전체 보증: "+period(result.productWarranty()));
        for(var part:result.partWarranties())lines.add("주요 부품 보증 — "+part.name()+": "+period(part));
        lines.add("기간 계산과 실제 무상 수리 승인은 다릅니다. 고장 부품·증빙·모델 적용 조건은 점검 때 확인합니다.");
        lines.add("[LG 공식 보증 정책]("+result.sourceUrl()+") · 정책 확인일 "+result.policyVerifiedOn());
        return String.join("\n",lines);
    }

    private String describe(WarrantyModels.Row row) {
        return period(row) + " · 부품비 " + feeLabel(row.partFee()) + " / 기술료 " + feeLabel(row.laborFee())
                + " / 출장비 " + feeLabel(row.visitFee());
    }

    private String period(WarrantyModels.Row row) {
        String duration = row.periodMonths() == null ? "기간 추가 확인"
                : row.periodMonths() % 12 == 0 ? row.periodMonths() / 12 + "년(" + row.periodMonths() + "개월)"
                : row.periodMonths() + "개월";
        String label = switch (row.periodStatus()) {
            case "IN_WARRANTY" -> "보증기간 안";
            case "EXPIRED" -> "만료";
            default -> "적용·만료일 추가 확인";
        };
        return duration + " · " + label + (row.expiresOn() == null ? "" : " · 만료일 " + row.expiresOn());
    }

    private String feeLabel(String value) {
        return switch (value) {
            case "CONDITIONAL_FREE" -> "조건 충족 시 무상";
            case "PAID" -> "유상";
            default -> "추가 확인 필요";
        };
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
