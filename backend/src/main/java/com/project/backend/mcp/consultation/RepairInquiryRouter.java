package com.project.backend.mcp.consultation;

import java.util.*;
import java.util.regex.Pattern;
import com.project.backend.mcp.tool.*;

/** One factual follow-up for an uncertain cost inquiry; remember explicit visit/part inputs. */
public final class RepairInquiryRouter {
    private RepairInquiryRouter(){ }
    public static DialogueRouter.Transition route(ToolDataCatalog catalog,ConsultationService.Snapshot s,ConsultationService.Snapshot previous,String input,DialogueRouter.Transition transition){
        var state=transition.state();var d=state.dialogue();
        if(Set.of("OUT_OF_SCOPE","CATEGORY_MISMATCH").contains(d.intent())||state.closed())return transition;
        var answers=new LinkedHashMap<>(d.answers());var question=d.pendingQuestion();String intent=d.intent();String reply=transition.reply();
        boolean answering=previous!=null&&Objects.equals(previous.productId(),s.productId())&&previous.state().dialogue().pendingQuestion()!=null&&previous.state().dialogue().pendingQuestion().id().equals("REPAIR_DETAIL");
        if(answering&&!Set.of("WARRANTY","SERVICE").contains(intent)){
            intent="COST";question=null;reply=null;answers.put("REPAIR_DETAIL_ASKED","YES");answers.put("REPAIR_DETAIL_REPLY",input.substring(0,Math.min(120,input.length())));
        }
        if("COST".equals(intent)){
            if(RepairCandidatePolicy.deniedRequest(input))answers.remove("REPAIR_PART_REQUEST");
            var parts=RepairCandidatePolicy.requestedParts(catalog,s,input);
            if(!parts.isEmpty())answers.put("REPAIR_PART_REQUEST",String.join(",",parts));
            boolean night=input.matches("(?s).*(야간|휴일|주말).*"),weekday=input.contains("평일");
            if(night&&weekday)answers.remove("VISIT_TIME_SLOT");
            else if(night)answers.put("VISIT_TIME_SLOT","NIGHT_HOLIDAY");else if(weekday)answers.put("VISIT_TIME_SLOT","WEEKDAY");
            var matcher=Pattern.compile("(?<!\\d)(1[0-2]|[1-9])\\s*월").matcher(input);var months=new HashSet<String>();while(matcher.find())months.add(matcher.group(1));
            if(months.size()==1)answers.put("VISIT_MONTH",months.iterator().next());else if(months.size()>1)answers.remove("VISIT_MONTH");
            if(state.errorCode()==null&&!answers.containsKey("REPAIR_PART_REQUEST")&&!answers.containsKey("REPAIR_DETAIL_ASKED")){
                question=new DialogueState.Question("REPAIR_DETAIL","표시창에 오류 코드가 있나요? 있으면 그대로 알려 주세요. 표시가 없으면 현재 불편한 증상을 설명해 주세요.",List.of("표시가 없어요","잘 모르겠어요"));
                answers.put("REPAIR_DETAIL_ASKED","YES");reply=null;
            }
        }
        var nextDialogue=new DialogueState(intent,d.mentionedCategory(),d.symptomCandidates(),d.symptomText(),question,d.symptomQuestionCount(),answers,d.interpretationSource(),d.selfCareReply());
        var next=new ConsultationState(state.riskStatus(),state.riskCase(),state.riskQuestionNo(),state.errorCode(),state.symptomCode(),state.doneSteps(),state.currentStep(),state.closed(),nextDialogue,state.selfCare());
        return new DialogueRouter.Transition(next,reply);
    }
}
