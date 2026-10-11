package com.project.backend.conversation;

public enum ProductCategory {
    AIR_CONDITIONER("에어컨"),
    REFRIGERATOR("냉장고"),
    WASHER("세탁기"),
    DRYER("건조기"),
    DISHWASHER("식기세척기");

    private final String label;

    ProductCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
