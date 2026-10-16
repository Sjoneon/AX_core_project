package com.project.backend.rag;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.ai.document.Document;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import com.project.backend.conversation.ProductCategory;

@Component
public class ManualCatalog {

    private final List<Document> documents;

    public ManualCatalog() throws IOException {
        var loaded = new ArrayList<Document>();
        var ids = new HashSet<String>();
        var resources = new PathMatchingResourcePatternResolver().getResources("classpath*:rag/manuals/*.md");
        for (var resource : resources) {
            String markdown = resource.getContentAsString(requireNonNull(StandardCharsets.UTF_8));
            String[] sections = markdown.split("(?m)^## (?=HC-)");
            for (int i = 1; i < sections.length; i++) {
                String section = sections[i].strip();
                String id = field(section, "문서 ID");
                String category = field(section, "카테고리");
                if (!ProductCategory.valueOf(category).isSupported()) continue;
                if (!ids.add(id)) throw new IllegalStateException("중복 매뉴얼 ID: " + id);
                String title = section.lines().findFirst().orElseThrow().split("\\|", 2)[1].strip();
                loaded.add(new Document(requireNonNull(id), section, requireNonNull(Map.of(
                        "title", title, "category", category, "version", field(section, "문서 버전"),
                        "file", "rag-test-data/manuals/" + resource.getFilename(),
                        "errorCodes", line(section, "오류 코드")))));
            }
        }
        if (loaded.isEmpty()) throw new IllegalStateException("RAG 매뉴얼이 없습니다.");
        documents = List.copyOf(loaded);
    }

    public List<Document> documents() {
        return documents;
    }

    private static String field(String section, String name) {
        return line(section, name).replace("`", "").strip();
    }

    private static String line(String section, String name) {
        var matcher = Pattern.compile("(?m)^- " + Pattern.quote(name) + ": (.+)$").matcher(section);
        if (!matcher.find()) throw new IllegalStateException("매뉴얼 필드 누락: " + name);
        return matcher.group(1);
    }
}
