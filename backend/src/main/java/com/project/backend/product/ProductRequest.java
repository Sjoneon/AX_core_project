package com.project.backend.product;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.project.backend.conversation.ProductCategory;

public record ProductRequest(
        @NotNull(message = "품목을 선택해 주세요.") ProductCategory category,
        @Size(max = 40, message = "표시 이름은 40자 이하여야 합니다.") String name,
        @Size(max = 80, message = "모델명은 80자 이하여야 합니다.") String model,
        @Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}", message = "구매일은 YYYY-MM-DD 형식이어야 합니다.") String purchaseDate,
        @Pattern(regexp = "[0-9]{5}", message = "우편번호는 숫자 5자리여야 합니다.") String postcode,
        @Size(max = 160, message = "기본 주소는 160자 이하여야 합니다.") String address,
        @Size(max = 100, message = "상세 주소는 100자 이하여야 합니다.") String addressDetail,
        // Region fields come from the address search result, never from splitting the address text.
        @Size(max = 20, message = "시·도를 확인해 주세요.") String deliverySido,
        @Size(max = 30, message = "시·군·구를 확인해 주세요.") String deliverySigungu
) {
    public ProductRequest {
        name = normalize(name);
        model = normalize(model);
        purchaseDate = normalize(purchaseDate);
        postcode = normalize(postcode);
        address = normalize(address);
        addressDetail = normalize(addressDetail);
        deliverySido = normalize(deliverySido);
        deliverySigungu = normalize(deliverySigungu);
    }
    public ProductRequest(ProductCategory category, String name, String model, String purchaseDate,
            String postcode, String address, String addressDetail) {
        this(category, name, model, purchaseDate, postcode, address, addressDetail, null, null);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
