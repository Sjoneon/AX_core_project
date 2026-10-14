package com.project.backend.warranty;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import com.project.backend.conversation.ProductCategory;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.LoggerFactory;

@Component
public class WarrantyTools {
    private final WarrantyService service;
    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(WarrantyTools.class);

    public WarrantyTools(WarrantyService service) { this.service = service; }

    @Tool(name = "check_warranty", returnDirect = true, description = "제품 전체 보증과 주요 부품별 별도 보증을 구분해 조회·계산합니다. 보증·무상 문의에 사용하세요. 날짜와 적용 조건은 사용자가 확인한 서버 컨텍스트만 사용합니다. 제품 전체 보증 만료를 모든 부품 보증 만료로 설명하지 않습니다.")
    public String checkWarranty(ToolContext context) {
        var category = (ProductCategory) context.getContext().get("category");
        var request = (WarrantyModels.Request) context.getContext().get("warrantyRequest");
        if (request == null) request = new WarrantyModels.Request(category, null, null, null, null, null, null, false, false);
        if (request.category() != category) throw new IllegalArgumentException("상담 품목과 보증 조회 품목이 다릅니다.");
        var result = service.check(request);
        LOGGER.info("tool=check_warranty caller={} category={} policyVersion={}",
                context.getContext().getOrDefault("caller", "AGENT"), category, result.policyVersion());
        @SuppressWarnings("unchecked")
        var capture = (AtomicReference<WarrantyModels.Result>) context.getContext().get("warrantyResult");
        if (capture != null) capture.set(result);
        return service.explain(result);
    }
}
