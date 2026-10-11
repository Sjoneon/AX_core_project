package com.project.backend.rag;

import org.springframework.ai.document.Document;

import com.project.backend.conversation.ProductCategory;

public record RagSource(String documentId, String title, ProductCategory category,
        String version, String file, String content) {

    public static RagSource from(Document document) {
        var metadata = document.getMetadata();
        return new RagSource(document.getId(), (String) metadata.get("title"),
                ProductCategory.valueOf((String) metadata.get("category")),
                (String) metadata.get("version"), (String) metadata.get("file"), document.getText());
    }
}
