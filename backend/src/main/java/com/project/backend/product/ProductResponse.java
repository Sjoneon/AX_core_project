package com.project.backend.product;

import java.time.Instant;
import java.time.LocalDate;

import com.project.backend.conversation.ProductCategory;

public record ProductResponse(Long id, ProductCategory category, String name, String model,
        LocalDate purchaseDate, String postcode, String address, String addressDetail,
        String deliverySido, String deliverySigungu, Instant createdAt, Instant updatedAt) {
}
