package com.project.backend.product;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.project.backend.conversation.ProductCategory;
import com.project.backend.user.AppUser;

@Entity
@Table(name = "user_products")
public class UserProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "enum('DISHWASHER','DRYER','REFRIGERATOR','WASHER')")
    private ProductCategory category;

    @Column(length = 40)
    private String name;

    @Column(length = 80)
    private String model;

    private LocalDate purchaseDate;

    @Column(length = 5)
    private String postcode;

    @Column(length = 160)
    private String address;

    @Column(length = 100)
    private String addressDetail;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected UserProduct() {
    }

    public UserProduct(AppUser user) {
        this.user = user;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void update(ProductRequest request, LocalDate purchaseDate) {
        this.category = request.category();
        this.name = request.name();
        this.model = request.model();
        this.purchaseDate = purchaseDate;
        this.postcode = request.postcode();
        this.address = request.address();
        this.addressDetail = request.addressDetail();
        this.updatedAt = Instant.now();
    }

    public ProductResponse response() {
        return new ProductResponse(id, category, name, model, purchaseDate, postcode,
                address, addressDetail, createdAt, updatedAt);
    }
}
