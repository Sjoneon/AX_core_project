package com.project.backend.warranty;

import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.project.backend.data.DataManifest;
import tools.jackson.databind.json.JsonMapper;

@Component
public class WarrantyCatalog {
    private final WarrantyModels.Catalog data;

    public WarrantyCatalog() {
        this(DataManifest.classpath());
    }

    // The policy file is read only after its manifest hash matches; a mismatch stops startup.
    @Autowired
    public WarrantyCatalog(DataManifest manifest) {
        data = JsonMapper.builder().build().readValue(manifest.read("warranty/policies.json"), WarrantyModels.Catalog.class);
        validate(data);
    }

    public WarrantyCatalog(WarrantyModels.Catalog data) {
        validate(data);
        this.data = data;
    }

    private static void validate(WarrantyModels.Catalog catalog) {
        Objects.requireNonNull(catalog);
        if (catalog.version() == null || catalog.sourceUrl() == null || catalog.verifiedOn() == null
                || catalog.policies() == null || catalog.policies().isEmpty()) {
            throw new IllegalArgumentException("보증 정책의 버전·출처·검토일이 필요합니다.");
        }
        var ids = new java.util.HashSet<String>();
        for (var policy : catalog.policies()) {
            if (policy.id() == null || !ids.add(policy.id()) || policy.category() == null
                    || policy.name() == null || policy.conditions() == null
                    || (!"PRODUCT".equals(policy.scope()) && !"CORE_PART".equals(policy.scope()))
                    || (policy.periodMonths() != null && policy.periodMonths() <= 0)) {
                throw new IllegalArgumentException("유효하지 않은 보증 정책입니다.");
            }
        }
    }

    public WarrantyModels.Catalog data() { return data; }
}
