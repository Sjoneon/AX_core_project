package com.project.backend.rag;

import static java.util.Objects.requireNonNull;
import org.jspecify.annotations.NonNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.stereotype.Service;

import com.project.backend.conversation.ProductCategory;

@Service
public class ManualRetriever {

    private static final Pattern ERROR_CODE = Pattern.compile("(?i)(?<![A-Z0-9])HC-E\\d+(?![A-Z0-9])");
    private static final Pattern URGENT_SYMPTOM = Pattern.compile("누수|물\\s*이?\\s*(?:새|샙|샘)|젖|(?:타는|탄)\\s*냄새|연기");
    private final ManualCatalog catalog;
    private final EmbeddingModel embeddingModel;
    private SimpleVectorStore index;

    public ManualRetriever(ManualCatalog catalog, EmbeddingModel embeddingModel) {
        this.catalog = catalog;
        this.embeddingModel = embeddingModel;
    }

    public Result retrieve(String question, ProductCategory category, List<String> previousQuestions) {
        if (question.contains("에어컨")) {
            return new Result("에어컨은 지원 종료된 품목입니다. 냉장고, 세탁기, 건조기, 식기세척기 상담을 이용해 주세요.", List.of());
        }
        if (Pattern.compile("(?i)\\bTV\\b|텔레비전").matcher(question).find()) {
            return new Result("제공된 가상 매뉴얼에 TV 상담 자료가 없습니다. 자료에 없는 수리 절차는 안내할 수 없어요.", List.of());
        }
        if (category == null) {
            return new Result("어떤 가전에 대한 문의인가요? 냉장고, 세탁기, 건조기, 식기세척기 중 제품 카테고리를 선택해 새 상담을 시작해 주세요.", List.of());
        }
        for (var product : ProductCategory.values()) {
            if (product != category && question.contains(product.getLabel())) {
                return new Result("선택한 제품은 " + category.getLabel() + "인데 문의에는 '" + product.getLabel()
                        + "' 제품이 언급되어 있어요. 어떤 제품에 대한 상담인지 확인해 주세요.", List.of());
            }
        }
        var selected = new LinkedHashMap<String, Document>();
        var codes = errorCodes(question).stream().distinct().toList();
        for (String code : codes) {
            var matches = documentsForCode(code, category);
            if (matches.isEmpty()) {
                return new Result("제공된 " + category.getLabel() + " 가상 매뉴얼에는 " + code
                        + "의 의미가 없습니다. 코드 표기와 제품 증상을 확인해 주세요. 확인 후에도 문제가 계속되면 수리 상담을 요청해 주세요.", List.of());
            }
            matches.forEach(doc -> selected.put(doc.getId(), doc));
        }

        var recentQuestions = previousQuestions.subList(Math.max(0, previousQuestions.size() - 3), previousQuestions.size());
        // Earlier routine symptoms must not dilute a newly reported urgent symptom.
        String searchContext = recentQuestions.isEmpty() || URGENT_SYMPTOM.matcher(question).find() ? "" : "이전 고객 문의:\n"
                + String.join("\n---\n", recentQuestions) + "\n\n";
        var similar = index().similaritySearch(SearchRequest.builder()
                .query(category.getLabel() + "\n" + searchContext + "현재 고객 문의: " + question).topK(2)
                .similarityThreshold(0.30).filterExpression("category == '" + category.name() + "'").build());
        similar.forEach(doc -> selected.putIfAbsent(doc.getId(), doc));
        if (codes.isEmpty()) {
            for (String previous : recentQuestions.reversed()) {
                var historicalCodes = errorCodes(previous);
                if (!historicalCodes.isEmpty()) {
                    String lastCode = historicalCodes.getLast();
                    documentsForCode(lastCode, category).forEach(doc -> selected.putIfAbsent(doc.getId(), doc));
                    break;
                }
            }
        }
        var documents = selected.values().stream().limit(3).toList();
        if (documents.isEmpty()) {
            return new Result("제공된 " + category.getLabel()
                    + " 가상 매뉴얼에서 질문에 맞는 근거를 찾지 못했습니다. 증상이나 오류 코드를 더 자세히 알려주세요.", List.of());
        }
        return new Result(null, documents);
    }

    private List<String> errorCodes(String question) {
        return ERROR_CODE.matcher(question).results()
                .map(match -> match.group().toUpperCase(Locale.ROOT)).toList();
    }

    private List<Document> documentsForCode(String code, ProductCategory category) {
        return catalog.documents().stream()
                .filter(doc -> doc.getMetadata().get("category").equals(category.name()))
                .filter(doc -> ((String) doc.getMetadata().get("errorCodes")).contains("`" + code + "`"))
                .toList();
    }

    // Publish the index only after all embeddings succeed; a failed first attempt
    // can be retried.
    private synchronized SimpleVectorStore index() {
        if (index == null) {
            var documents = catalog.documents();
            var embeddings = embeddingModel.embed(requireNonNull(documents.stream()
                    .map((Document document) -> requireNonNull(document.getText()))
                    .toList()));
            if (embeddings.size() != documents.size())
                throw new IllegalStateException("매뉴얼 임베딩 개수 불일치");
            var byId = new LinkedHashMap<String, float[]>();
            for (int i = 0; i < documents.size(); i++)
                byId.put(documents.get(i).getId(), embeddings.get(i));
            // SimpleVectorStore adds documents individually; reuse the single batch
            // response.
            var batchedModel = new EmbeddingModel() {
                @Override
                public float @NonNull[] embed(@NonNull Document document) {
                    return requireNonNull(byId.get(document.getId()));
                }

                @Override
                public @NonNull EmbeddingResponse call(@NonNull EmbeddingRequest request) {
                    return embeddingModel.call(request);
                }

                @Override
                public int dimensions() {
                    return embeddings.getFirst().length;
                }
            };
            var prepared = SimpleVectorStore.builder(batchedModel).build();
            prepared.add(documents);
            index = prepared;
        }
        return index;
    }

    public record Result(String directReply, List<Document> documents) {
    }
}
