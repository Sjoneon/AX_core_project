package com.project.backend.mcp.tool;

import static com.project.backend.mcp.tool.ToolDataCatalog.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import com.project.backend.data.DataManifest;

class KnowledgeDocumentTests {
    static ToolDataCatalog catalog;
    @BeforeAll static void load(){catalog=new ToolDataCatalog(DataManifest.classpath());}

    @Test void everySourceHasAProjectionAndNoPublicViewContainsTheWrapper(){
        assertThat(catalog.structuredKnowledgeById.keySet()).containsExactlyInAnyOrderElementsOf(catalog.knowledgeById.keySet());
        for(var source:catalog.knowledge){
            var document=catalog.structuredKnowledgeById.get(text(source,"id"));
            assertThat(document.searchText()).doesNotContain(text(source,"original_sha256"),"current_QA_review","source_topic_sha256");
            assertThat(document.view().toString()).doesNotContain("original_record_sha256","current_QA_review","source_topic_sha256","DOM_selector");
            assertThat(document.displayText()).doesNotStartWith("{");
            assertThat(document.view()).containsEntry("is_action_approved",false);
        }
    }
    @Test void originalProcedureAndSupplementKeepSeparateStepsAndSafetyGates(){
        var original=catalog.structuredKnowledgeById.get("B17-MFL71995305-topic_39-procedure");
        var supplement=catalog.structuredKnowledgeById.get("B18-MFL71995305-inlet-reassembly-supplement");
        assertThat((List<?>)original.fields().get("procedureSteps")).hasSize(2);
        assertThat((List<?>)supplement.fields().get("procedureSteps")).hasSize(5);
        assertThat(original.preconditions()).contains("급수 밸브 차단 확인");
        assertThat(original.view().get("requirements").toString()).contains("requires_original_diagram_confirmation=true","incomplete_source_steps_for_standalone_execution=true");
        assertThat(original.view().get("relationships").toString()).contains("B18-MFL71995305-inlet-reassembly-supplement");
    }
    @Test void plainCallersCannotAccidentallyPublishAnEmbeddedJsonWrapper(){
        var error=assertThatThrownBy(()->KnowledgeDocument.plain(map("id","X","text"," {\"record\":{}}")));
        error.isInstanceOf(IllegalStateException.class);error.hasMessageContaining("원본 JSON");
    }
}
