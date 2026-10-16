package com.project.backend.product;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import jakarta.persistence.*;
import com.project.backend.user.AppUser;
import com.project.backend.conversation.ProductCategory;
import com.project.backend.warranty.WarrantyModels;
import tools.jackson.databind.json.JsonMapper;

@Entity
@Table(name="user_products")
public class UserProduct {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="user_id",nullable=false) private AppUser user;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=30) private ProductCategory category;
    @Column(name="name",length=100) private String displayName;
    @Column(length=100) private String model;
    private LocalDate purchaseDate;
    @Column(columnDefinition="LONGTEXT") private String warrantyInput;
    @Column(nullable=false,length=20) private String deliverySido;
    @Column(nullable=false,length=30) private String deliverySigungu;
    @Version private long revision;
    @Column(length=5) private String postcode;
    @Column(length=160) private String address;
    @Column(length=100) private String addressDetail;
    // Nullable for products registered before profile information was added.
    private Instant createdAt;
    private Instant updatedAt;
    protected UserProduct() { }
    public UserProduct(AppUser user) {
        this.user = user;
        this.deliverySido = "";
        this.deliverySigungu = "";
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }
    public UserProduct(AppUser user, String displayName, WarrantyModels.Request warranty, String sido, String sigungu) {
        this(user); this.category=warranty.category(); this.displayName=displayName;
        this.model=warranty.modelName(); this.purchaseDate=warranty.purchaseDate();
        this.warrantyInput=JSON.writeValueAsString(warranty);
        this.deliverySido=sido; this.deliverySigungu=sigungu;
    }
    public void update(ProductRequest request, LocalDate purchaseDate) {
        update(request, purchaseDate, null, null);
    }
    /**
     * A searched region replaces the stored one. Replacing a stored address by typing clears the region, so it never
     * describes a different address; a region registered without any address is kept when an address is first added.
     */
    public void update(ProductRequest request, LocalDate purchaseDate, String sido, String sigungu) {
        boolean addressReplaced = address != null && !Objects.equals(address, request.address());
        if (sido != null) { deliverySido = sido; deliverySigungu = sigungu == null ? "" : sigungu; }
        else if (addressReplaced) { deliverySido = ""; deliverySigungu = ""; }
        var previous = warrantyInput == null ? null : warranty();
        boolean sameCategory = previous != null && previous.category() == request.category();
        boolean sameModel = sameCategory && Objects.equals(previous.modelName(), request.model());
        boolean samePurchase = sameModel && Objects.equals(previous.purchaseDate(), purchaseDate);
        this.category = request.category();
        this.displayName = request.name();
        this.model = request.model();
        this.purchaseDate = purchaseDate;
        this.warrantyInput = JSON.writeValueAsString(new WarrantyModels.Request(category, request.model(), purchaseDate,
                samePurchase ? previous.deliveryDate() : null,
                sameModel ? previous.productionDate() : null,
                sameCategory ? previous.coreVariant() : null,
                sameCategory ? previous.productType() : null,
                samePurchase ? previous.proofAvailable() : null,
                sameModel ? previous.domesticNewHousehold() : null));
        this.postcode = request.postcode();
        this.address = request.address();
        this.addressDetail = request.addressDetail();
        this.updatedAt = Instant.now();
    }
    public ProductResponse response() {
        var input = warranty();
        return new ProductResponse(id, category, displayName, input.modelName(), input.purchaseDate(), postcode,
                address, addressDetail, deliverySido, deliverySigungu, createdAt, updatedAt);
    }
    public Long getId(){return id;}
    public ProductCategory getCategory(){return category;}
    public String getDisplayName(){return displayName;}
    public String getDeliverySido(){return deliverySido;}
    public String getDeliverySigungu(){return deliverySigungu;}
    public long getRevision(){return revision;}
    public WarrantyModels.Request warranty(){
        var saved=warrantyInput==null?null:JSON.readValue(warrantyInput, WarrantyModels.Request.class);
        boolean sameCategory=saved!=null&&saved.category()==category;
        boolean sameModel=sameCategory&&Objects.equals(saved.modelName(),model);
        boolean samePurchase=sameModel&&Objects.equals(saved.purchaseDate(),purchaseDate);
        // Original columns remain authoritative when an older release writes after rollback.
        return new WarrantyModels.Request(category,model,purchaseDate,
                samePurchase?saved.deliveryDate():null,sameModel?saved.productionDate():null,
                sameCategory?saved.coreVariant():null,sameCategory?saved.productType():null,
                samePurchase?saved.proofAvailable():null,sameModel?saved.domesticNewHousehold():null);
    }
}
