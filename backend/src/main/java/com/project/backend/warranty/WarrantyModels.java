package com.project.backend.warranty;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.project.backend.conversation.ProductCategory;

public final class WarrantyModels {
    private WarrantyModels() { }

    public record Request(
            @NotNull ProductCategory category,
            @Size(max = 100) String modelName,
            LocalDate purchaseDate, LocalDate deliveryDate, LocalDate productionDate,
            @Size(max = 50) String coreVariant,
            @Size(max = 50) String productType,
            Boolean proofAvailable, Boolean domesticNewHousehold) { }

    public record Policy(String id, ProductCategory category, String scope, String name,
            String variant, Integer periodMonths, LocalDate producedFrom,
            String requiredProductType, Boolean freePart, Boolean freeLabor, Boolean freeVisit,
            List<String> conditions) { }

    public record Catalog(String version, String sourceUrl, LocalDate verifiedOn, List<Policy> policies) { }

    public record Row(String policyId, String scope, String name, String variant,
            Integer periodMonths, LocalDate expiresOn, String periodStatus,
            String applicability, String partFee, String laborFee, String visitFee,
            List<String> conditions) { }

    public record Result(ProductCategory category, String modelName, LocalDate asOfDate,
            LocalDate basisDate, String basisSource, Row productWarranty,
            List<Row> partWarranties, String headline, List<String> notes,
            String policyVersion, String sourceUrl, LocalDate policyVerifiedOn) { }
}
