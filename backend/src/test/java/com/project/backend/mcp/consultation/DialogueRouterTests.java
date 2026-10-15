package com.project.backend.mcp.consultation;

import static org.assertj.core.api.Assertions.*;
import static com.project.backend.mcp.tool.ToolDataCatalog.map;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.project.backend.conversation.ProductCategory;

class DialogueRouterTests {
    @Test void explicitNoCurrentFaultIsAcknowledgedInsteadOfAskingForASymptom(){
        String message="설명서에서 타는 냄새라는 말을 읽었을 뿐 지금 제품에 냄새나 고장은 없어요.";
        var result=route(ConsultationState.start(),message,interpretation("OTHER",null,0));
        assertThat(result.reply()).contains("추가 확인은 필요하지");assertThat(result.state().dialogue().pendingQuestion()).isNull();
        var actual=route(ConsultationState.start(),"현재 고장은 없지만 탈수할 때 본체가 흔들려요",interpretation("SYMPTOM","VIBRATION",.95));
        assertThat(actual.state().symptomCode()).isEqualTo("VIBRATION");
    }
    @Test void combinedAlreadyDoneAndUnresolvedReplyAdvancesOnlyTheDisplayedApprovedStep(){
        var state=new ConsultationState("CLEAR",null,0,"UE",null,List.of(),0,false);
        var step=map("step_code","TEST-UE-1","text","TEST_ONLY approved instruction","error_code","UE");
        var parsed=new MessageInterpreter.Interpretation("SELF_CARE_REPLY",null,List.of(),List.of(),"NOT_RESOLVED","AI");
        var result=DialogueRouter.route(ProductCategory.WASHER,state,"그건 이미 해 봤어요. 그래도 안 돼요.",parsed,List.of(step),"TEST-UE-1");
        assertThat(result.state().doneSteps()).containsExactly("TEST-UE-1");
        assertThat(result.state().selfCare().results()).containsEntry("TEST-UE-1","NOT_RESOLVED");
        assertThat(result.state().closed()).isFalse();
        assertThat(DialogueRouter.route(ProductCategory.WASHER,state,"다른 필터는 이미 해 봤어요. 그래도 안 돼요.",parsed,List.of(step),"TEST-UE-1").state().doneSteps()).isEmpty();
        assertThat(DialogueRouter.route(ProductCategory.WASHER,state,"그건 이미 해 봤어요. 그래도 안 돼요.",parsed,List.of(step),null).state().doneSteps()).isEmpty();
    }
    MessageInterpreter.Interpretation interpretation(String intent,String code,double confidence){
        return new MessageInterpreter.Interpretation(intent,null,code==null?List.of():List.of(new MessageInterpreter.Candidate(code,confidence)),List.of(),null,"AI");
    }
    DialogueRouter.Transition route(ConsultationState state,String input,MessageInterpreter.Interpretation parsed){
        return DialogueRouter.route(ProductCategory.WASHER,state,input,parsed,List.of(),null);
    }
    @Test void vibrationWithoutErrorPersistsAndAsksWhenItOccurs(){
        var first=route(ConsultationState.start(),"본체가 원 위치에서 크게 벗어나요",interpretation("SYMPTOM","VIBRATION",.95));
        assertThat(first.state().symptomCode()).isEqualTo("VIBRATION");assertThat(first.state().riskStatus()).isEqualTo("CLEAR");
        assertThat(first.state().dialogue().pendingQuestion().id()).isEqualTo("VIBRATION_WHEN");
        var second=route(first.state(),"탈수할 때만요",interpretation("SYMPTOM",null,0));
        assertThat(second.reply()).isNull();assertThat(second.state().dialogue().answers()).containsEntry("VIBRATION_WHEN","SPIN");
        assertThat(second.state().searchQuery("탈수할 때만요")).contains("원 위치에서 크게 벗어나요");
        var third=route(second.state(),"그래도 안 돼요",interpretation("SELF_CARE_REPLY",null,0));
        assertThat(third.state().dialogue().pendingQuestion()).isNull();assertThat(third.state().doneSteps()).isEmpty();
    }
    @Test void timingInInitialMessageNeedsNoRepeatedQuestion(){
        var result=route(ConsultationState.start(),"탈수할때 많이 흔들려요",interpretation("SYMPTOM","VIBRATION",.9));
        assertThat(result.reply()).isNull();assertThat(result.state().dialogue().answers()).containsEntry("VIBRATION_WHEN","SPIN");
    }
    @Test void lowConfidenceOffersAtMostThreeCandidatesAndUnknownEndsSymptomQuestions(){
        var parsed=new MessageInterpreter.Interpretation("SYMPTOM",null,List.of(new MessageInterpreter.Candidate("DRAIN",.5),new MessageInterpreter.Candidate("FILL",.4)),List.of(),null,"AI");
        var first=route(ConsultationState.start(),"물이 이상해요",parsed);
        assertThat(first.state().symptomCode()).isNull();assertThat(first.state().dialogue().pendingQuestion().options()).hasSize(3);
        var second=route(first.state(),"잘 모르겠어요",interpretation("OTHER",null,0));
        assertThat(second.state().dialogue().intent()).isEqualTo("UNCLASSIFIED");assertThat(second.state().dialogue().pendingQuestion()).isNull();
        var third=route(second.state(),"모르겠어요",interpretation("OTHER",null,0));assertThat(third.state().dialogue().pendingQuestion()).isNull();
    }
    @Test void inquiryWithoutSymptomDoesNotForceSymptomSelection(){
        for(String intent:List.of("COST","WARRANTY","SERVICE","PRODUCT_REPLY")){
            var result=route(ConsultationState.start(),"문의",interpretation(intent,null,0));assertThat(result.reply()).isNull();
            assertThat(result.state().dialogue().intent()).isEqualTo(intent);
        }
    }
    @Test void categoryMismatchNeverChangesSelectedCategoryAndUnsupportedCloses(){
        var mismatch=new MessageInterpreter.Interpretation("SYMPTOM","DRYER",List.of(),List.of(),null,"AI");
        assertThat(route(ConsultationState.start(),"건조기",mismatch).state().dialogue().intent()).isEqualTo("CATEGORY_MISMATCH");
        var outside=new MessageInterpreter.Interpretation("OUT_OF_SCOPE","UNSUPPORTED",List.of(),List.of(),null,"AI");
        assertThat(route(ConsultationState.start(),"TV 화면",outside).state().closed()).isTrue();
        var mistakenOutside=new MessageInterpreter.Interpretation("OUT_OF_SCOPE","DRYER",List.of(),List.of(),null,"AI");
        var result=route(ConsultationState.start(),"건조기 문제",mistakenOutside);
        assertThat(result.state().closed()).isFalse();assertThat(result.state().dialogue().intent()).isEqualTo("CATEGORY_MISMATCH");
        var copiedCategory=new MessageInterpreter.Interpretation("OUT_OF_SCOPE","WASHER",List.of(),List.of(),null,"AI");
        assertThat(route(ConsultationState.start(),"TV 화면이 안 나와요",copiedCategory).state().closed()).isTrue();
    }
    @Test void explicitAbsenceOfCurrentFaultCannotBecomeAPositiveSymptom(){
        var result=route(ConsultationState.start(),"설명서에 흔들림이 나온다고 하지만 지금 고장은 없어요",interpretation("SYMPTOM","VIBRATION",.9));
        assertThat(result.state().symptomCode()).isNull();assertThat(result.state().dialogue().pendingQuestion()).isNull();assertThat(result.reply()).contains("현재 불편한 증상");
    }
    List<Map<String,Object>> steps(){return List.of(map("step_code","S1","text","TEST_ONLY 필터 확인","symptom_code","DRAIN","error_code",null));}
    ConsultationState drain(){return new ConsultationState("CLEAR",null,0,null,"DRAIN",List.of(),0,false);}
    @ParameterizedTest @ValueSource(strings={"그래도 안 돼요","그건 이미 확인했어요"})
    void currentDisplayedApprovedStepCanBeCompletedByAReply(String input){
        var result=DialogueRouter.route(ProductCategory.WASHER,drain(),input,interpretation("SELF_CARE_REPLY",null,0),steps(),"S1");
        assertThat(result.state().doneSteps()).containsExactly("S1");assertThat(result.state().currentStep()).isEqualTo(1);assertThat(result.state().closed()).isFalse();
    }
    @Test void resolvedReplyOnlyClosesWhenAnApprovedStepWasDisplayed(){
        var result=DialogueRouter.route(ProductCategory.WASHER,drain(),"이제 해결됐어요",interpretation("SELF_CARE_REPLY",null,0),steps(),"S1");
        assertThat(result.state().closed()).isTrue();assertThat(result.state().dialogue().selfCareReply()).isEqualTo("RESOLVED");
        assertThat(route(drain(),"이제 해결됐어요",interpretation("SELF_CARE_REPLY",null,0)).state().closed()).isFalse();
    }
    @Test void hallucinatedOrSimilarCompletedActionsCannotSkipSteps(){
        var parsed=new MessageInterpreter.Interpretation("SELF_CARE_REPLY",null,List.of(),List.of("S1"),"RESOLVED","AI");
        var result=DialogueRouter.route(ProductCategory.WASHER,drain(),"다른 필터는 이미 확인했어요",parsed,steps(),null);
        assertThat(result.state().doneSteps()).isEmpty();assertThat(result.state().closed()).isFalse();
        result=DialogueRouter.route(ProductCategory.WASHER,drain(),"다른 필터는 이미 확인했어요",parsed,steps(),"S1");
        assertThat(result.state().doneSteps()).isEmpty();
        result=DialogueRouter.route(ProductCategory.WASHER,drain(),"S1은 아직 안 했어요",parsed,steps(),"S1");
        assertThat(result.state().doneSteps()).isEmpty();
        result=DialogueRouter.route(ProductCategory.WASHER,drain(),"TEST_ONLY 필터 확인은 이미 했어요",parsed,steps(),null);
        assertThat(result.state().doneSteps()).containsExactly("S1");assertThat(result.state().closed()).isFalse();
    }
    @Test void factualQuestionReplyNeverCompletesAnAction(){
        var first=route(ConsultationState.start(),"흔들려요",interpretation("SYMPTOM","VIBRATION",.9));
        var parsed=new MessageInterpreter.Interpretation("SELF_CARE_REPLY",null,List.of(),List.of("S1"),"RESOLVED","AI");
        var result=DialogueRouter.route(ProductCategory.WASHER,first.state(),"그건 이미 확인했어요",parsed,steps(),"S1");
        assertThat(result.state().doneSteps()).isEmpty();assertThat(result.state().closed()).isFalse();
    }
}
