package com.project.backend.mcp.consultation;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.project.backend.data.DataManifest;
import com.project.backend.conversation.ProductCategory;
import com.project.backend.mcp.tool.*;

class RepairInquiryTests {
    static final ToolDataCatalog catalog=new ToolDataCatalog(DataManifest.classpath());
    @Test void errorCodesComeFromTheActualCategoryWithoutCuttingProductSuffixes(){
        assertThat(ErrorCodeParser.find(catalog,ProductCategory.REFRIGERATOR,"F dH 표시가 나요")).isEqualTo("FDH");
        assertThat(ErrorCodeParser.find(catalog,ProductCategory.WASHER,"OE.")).isEqualTo("OE");
        assertThat(ErrorCodeParser.find(catalog,ProductCategory.WASHER,"F24WDWPR.UE")).isNull();
        assertThat(ErrorCodeParser.find(catalog,ProductCategory.WASHER,"FdH")).isNull();
    }
    @Test void conflictingVisitInputsRemainUnknownAndExplicitPartInputsAreKept(){
        var d=new DialogueState("COST",null,List.of(),null,null,0,Map.of(),"TEST_ONLY",null);
        var state=new ConsultationState("CLEAR",null,0,null,null,List.of(),0,false,d);
        var s=new ConsultationService.Snapshot(1L,ProductCategory.WASHER,2L,"메인 PCB 비용",state,3);
        var next=RepairInquiryRouter.route(catalog,s,null,"메인 PCB 비용, 평일이나 야간 7월 또는 8월",new DialogueRouter.Transition(state,null)).state();
        assertThat(next.dialogue().answers()).containsEntry("REPAIR_PART_REQUEST","W04").doesNotContainKeys("VISIT_MONTH","VISIT_TIME_SLOT");
        assertThat(next.dialogue().pendingQuestion()).isNull();
        assertThat(RepairCandidatePolicy.requestedParts(catalog,s,"메인 PCB가 아니라고 합니다")).isEmpty();
    }
}
