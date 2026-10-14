package com.project.backend.conversation;

import java.time.Instant;

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
import jakarta.persistence.Version;

import com.project.backend.user.AppUser;

@Entity
@Table(name = "chat_conversations")
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(nullable = false, length = 100)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ProductCategory category;

    @Column(name="product_id") private Long productId;
    @Column(name="latest_input",columnDefinition="LONGTEXT") private String latestInput;
    @Column(name="consultation_context",columnDefinition="LONGTEXT") private String consultationContext;
    @Column(name="context_revision",nullable=false) private long contextRevision;
    @Version private long revision;
    @Column(name="manual_warranty",columnDefinition="LONGTEXT") private String manualWarranty;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Conversation() {
    }

    public Conversation(AppUser user, String message, ProductCategory category) {
        this.user = user;
        this.category = category;
        String normalized = message.strip().replaceAll("\\s+", " ");
        this.title = normalized.substring(0, normalized.offsetByCodePoints(
                0, Math.min(50, normalized.codePointCount(0, normalized.length()))));
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void touch() {
        updatedAt = Instant.now();
    }

    public Long getProductId(){return productId;}
    public String getLatestInput(){return latestInput;}
    public String getConsultationContext(){return consultationContext;}
    public long getContextRevision(){return contextRevision;}
    public String getManualWarranty(){return manualWarranty;}
    public void setManualWarranty(String value){this.manualWarranty=value;this.contextRevision++;touch();}
    public void setConsultation(Long productId,String latestInput,String context){
        this.productId=productId;this.latestInput=latestInput;this.consultationContext=context;this.contextRevision++;touch();
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public ProductCategory getCategory() {
        return category;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
