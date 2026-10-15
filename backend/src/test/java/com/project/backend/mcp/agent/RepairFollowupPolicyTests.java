package com.project.backend.mcp.agent;

import static org.assertj.core.api.Assertions.*;
import static com.project.backend.mcp.tool.ToolDataCatalog.map;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.project.backend.conversation.ProductCategory;
import com.project.backend.mcp.consultation.*;
import com.project.backend.mcp.tool.*;

class RepairFollowupPolicyTests {
    ConsultationService.Snapshot state(String intent){
        var d=new DialogueState(intent,null,List.of(),"TEST_ONLY symptom",null,0,Map.of(),"TEST_ONLY",null);
        return new ConsultationService.Snapshot(1L,ProductCategory.WASHER,2L,"TEST_ONLY",new ConsultationState("CLEAR",null,0,"OE",null,List.of(),0,false,d),3);
    }
    ToolEnvelope result(String status,Map<String,Object> data){return ToolEnvelope.policy(status,data,map("context_version",3),List.of());}
    @Test void costCallsCandidatesBeforeEstimateAndNeverInventsACandidateWhenEvidenceIsAbsent(){
        var outputs=new LinkedHashMap<String,ToolEnvelope>();var calls=new ArrayList<String>();
        RepairFollowupPolicy.complete(state("COST"),outputs,(name,args)->{
            calls.add(name);
            if(name.equals("get_repair_candidates"))return result("OK",map("candidate_set_id","TEST_ONLY_BOUND_SET","supported_candidates",List.of()));
            if(name.equals("estimate_repair_cost")){
                assertThat(args).containsEntry("candidate_set_id","TEST_ONLY_BOUND_SET").containsEntry("candidate_ids",List.of()).containsEntry("confidence",0.0);
                return result("NO_DATA",map("alternatives",List.of()));
            }
            return result("OK",Map.of());
        },Map.of());
        assertThat(calls).containsExactly("get_repair_candidates","estimate_repair_cost","check_warranty","find_service_centers","prepare_service_handoff");
    }
    @Test void anExhaustedBudgetRecordsPendingFollowupInsteadOfClaimingItSucceeded(){
        var outputs=new LinkedHashMap<String,ToolEnvelope>();var actual=new ArrayList<String>();
        RepairFollowupPolicy.complete(state("COST"),outputs,(name,args)->{
            if(!actual.isEmpty())return null;actual.add(name);
            return result("OK",map("candidate_set_id","TEST_ONLY_SET","supported_candidates",List.of()));
        },map("apply_url","https://example.invalid/TEST_ONLY"));
        assertThat(actual).containsExactly("get_repair_candidates");assertThat(outputs).doesNotContainKeys("estimate_repair_cost","prepare_service_handoff");
        assertThat(outputs.get("followup").status()).isEqualTo("PARTIAL");
        assertThat(outputs.get("followup").data().get("pending_tools").toString()).contains("estimate_repair_cost","prepare_service_handoff");
    }
    @Test void missingApprovalExplainsFirstAndConnectsServiceOnlyWhenTheSameSymptomContinues(){
        var calls=new ArrayList<String>();
        java.util.function.Supplier<Map<String,ToolEnvelope>> unapproved=()->new LinkedHashMap<>(Map.of("get_diagnostic_plan",result("NOT_VERIFIED",map("skip_reason","NOT_VERIFIED"))));
        RepairFollowupPolicy.complete(state("SYMPTOM"),unapproved.get(),(name,args)->{calls.add(name);return result("OK",Map.of());},Map.of(),false);
        assertThat(calls).isEmpty();
        // An open observation question is answered before the service connection, even on a later turn.
        var question=new DialogueState("SYMPTOM",null,List.of(),"TEST_ONLY symptom",new DialogueState.Question("COOLING_STATE","TEST_ONLY question",List.of()),0,Map.of(),"TEST_ONLY",null);
        var asking=new ConsultationService.Snapshot(1L,ProductCategory.REFRIGERATOR,2L,"TEST_ONLY",new ConsultationState("CLEAR",null,0,null,"COOLING",List.of(),0,false,question),3);
        RepairFollowupPolicy.complete(asking,unapproved.get(),(name,args)->{calls.add(name);return result("OK",Map.of());},Map.of(),true);
        assertThat(calls).isEmpty();
        RepairFollowupPolicy.complete(state("SYMPTOM"),unapproved.get(),(name,args)->{calls.add(name);return result("OK",Map.of());},Map.of(),true);
        assertThat(calls).containsExactly("check_warranty","find_service_centers","prepare_service_handoff");
    }
    @Test void missingApprovalKeepsItsOwnHandoffReasonAndNeverTriggersAnAutomaticPartPrice(){
        var outputs=new LinkedHashMap<String,ToolEnvelope>();outputs.put("get_diagnostic_plan",result("NOT_VERIFIED",map("skip_reason","NOT_VERIFIED")));
        var calls=new ArrayList<String>();
        RepairFollowupPolicy.complete(state("SYMPTOM"),outputs,(name,args)->{
            calls.add(name);if(name.equals("prepare_service_handoff"))assertThat(args).containsEntry("reason","NOT_VERIFIED");return result("OK",Map.of());
        },Map.of());
        assertThat(calls).containsExactly("check_warranty","find_service_centers","prepare_service_handoff");
    }
}
