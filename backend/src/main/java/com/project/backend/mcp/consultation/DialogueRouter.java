package com.project.backend.mcp.consultation;

import java.util.*;
import com.project.backend.conversation.ProductCategory;

/** Server-owned questions, transitions and exact checks on reported completed actions. */
public final class DialogueRouter {
    public static final double CONFIDENCE_THRESHOLD=0.7;
    public record Transition(ConsultationState state,String reply) { }
    public static Transition route(ProductCategory category,ConsultationState previous,String input,
            MessageInterpreter.Interpretation interpretation,List<Map<String,Object>> approvedSteps,String displayedStep) {
        var old=previous.dialogue();var answers=new LinkedHashMap<>(old.answers());
        String symptom=previous.symptomCode(),symptomText=old.symptomText();
        var pending=old.pendingQuestion();int questions=old.symptomQuestionCount();String reply=null;
        String intent=interpretation.intent();boolean checkSolved=false;
        boolean supportedMention=Arrays.stream(ProductCategory.values()).anyMatch(c->c.isSupported()&&c.name().equals(interpretation.mentionedCategory())&&input.contains(c.getLabel()));
        boolean outside="OUT_OF_SCOPE".equals(intent)&&!supportedMention||Set.of("UNSUPPORTED","AIR_CONDITIONER").contains(Objects.toString(interpretation.mentionedCategory(),""));
        boolean mismatch=interpretation.mentionedCategory()!=null&&!outside&&!category.name().equals(interpretation.mentionedCategory());
        if(outside||mismatch){
            var dialogue=new DialogueState(outside?"OUT_OF_SCOPE":"CATEGORY_MISMATCH",interpretation.mentionedCategory(),List.of(),symptomText,null,questions,answers,interpretation.source(),null);
            return new Transition(copy(previous,symptom,previous.doneSteps(),previous.currentStep(),outside,dialogue),outside?
                "지금은 세탁기·건조기·냉장고(김치냉장고 포함)·식기세척기만 안내할 수 있어요. 공식 제조사 상담을 이용해 주세요. 다른 제품은 새 상담에서 시작해 주세요.":
                "현재 "+category.getLabel()+" 상담인데 다른 품목을 말씀하셨어요. 해당 품목을 선택해 새 상담을 시작하거나, 현재 제품의 증상을 알려 주세요.");
        }
        var noFault=java.util.regex.Pattern.compile("(지금|현재).{0,30}(고장|이상|문제)(은|는|이|가)?\\s*없(?:어요|습니다|지만|고|는데|다)?").matcher(input);
        boolean explicitAbsence=noFault.find();
        String afterAbsence=explicitAbsence?input.substring(noFault.end()):"";
        boolean laterSymptom=!interpretation.symptomCandidates().isEmpty()&&afterAbsence.matches("(?s).*(흔들|진동.{0,5}심|물이?.{0,5}안|전원.{0,5}안|문이?.{0,5}안|냄새.{0,5}나|연기.{0,5}나|차갑지|시원하지|미지근).*");
        if(!Set.of("COST","WARRANTY","SERVICE").contains(intent)&&explicitAbsence&&!laterSymptom){
            var dialogue=new DialogueState("OTHER",interpretation.mentionedCategory(),List.of(),null,null,questions,answers,interpretation.source(),null);
            return new Transition(copy(previous,null,List.of(),0,false,dialogue),"현재 불편한 증상이 없다면 추가 확인은 필요하지 않아요. 증상이 발생하면 알려 주세요.");
        }
        boolean inquiry=Set.of("COST","WARRANTY","SERVICE","PRODUCT_REPLY").contains(intent);
        // Unknown on the first symptom question ends clarification, rather than creating a loop.
        boolean unknown=input.strip().matches(".*(잘 모르|모르겠|몰라|알 수 없).*" );
        if(pending!=null&&pending.id().equals("SYMPTOM")&&!inquiry&&(unknown||interpretation.symptomCandidates().isEmpty())){
            answers.put("SYMPTOM",input.strip());pending=null;intent="UNCLASSIFIED";
            reply="증상을 특정하지 못했어요. 확인된 조치나 수리비를 임의로 안내할 수 없으므로 공식 점검을 받아 주세요.";
        }else{
            var candidates=interpretation.symptomCandidates();
            boolean confident=!candidates.isEmpty()&&candidates.getFirst().confidence()>=CONFIDENCE_THRESHOLD
                    &&(candidates.size()==1||candidates.getFirst().confidence()-candidates.get(1).confidence()>=0.15);
            if(confident){
                String code=candidates.getFirst().code();
                if(!Objects.equals(code,symptom)){answers.clear();symptomText=input;pending=null;questions=0;}
                else if(symptomText==null)symptomText=input;
                symptom=code;
                if(old.pendingQuestion()!=null&&old.pendingQuestion().id().equals("SYMPTOM"))answers.put("SYMPTOM",SymptomCatalog.label(category,code));
                if(pending!=null&&pending.id().equals("SYMPTOM"))pending=null;
            }
            if(pending!=null&&pending.id().equals(SELF_CHECK)){
                var items=checkItems(answers);int index=Integer.parseInt(answers.getOrDefault(CHECK_INDEX,"0"));
                // The step answer is read first: an interpreter may label "아니요, 다음 단계" as a product reply.
                switch(checkResult(input)){
                    case "SOLVED"->{pending=null;answers.put(CHECK_DONE,"SOLVED");checkSolved=true;}
                    case "NOT_SOLVED"->{
                        index++;answers.put(CHECK_INDEX,String.valueOf(index));
                        if(index<items.size()){pending=stepQuestion(items,index);reply=stepReply(items,index,null);}
                        else{pending=null;answers.put(CHECK_DONE,"NOT_RESOLVED");}
                    }
                    // A service/cost request leaves the self-check; anything else repeats the current step.
                    default->{if(inquiry)pending=null;else reply=stepReply(items,index,null);}
                }
            }
            if(pending!=null&&pending.id().equals(COOLING_STATE)){
                // The answer is read first; a service/cost request ends the question instead of repeating it.
                String state=coolingState(input,true);
                if(state!=null||unknown){answers.put(COOLING_STATE,state==null?"UNKNOWN":state);pending=null;}
                else if(inquiry)pending=null;
                else if(answers.containsKey("COOLING_STATE_REASK")){answers.put(COOLING_STATE,"UNKNOWN");pending=null;}
                else{answers.put("COOLING_STATE_REASK","ASKED");reply=pending.text();}
            }
            if(pending!=null&&pending.id().equals("VIBRATION_WHEN")&&!inquiry){
                String timing=timing(input);
                if(timing!=null){answers.put("VIBRATION_WHEN",timing);pending=null;}
                else if(unknown||answers.containsKey("VIBRATION_WHEN_REASK")){answers.put("VIBRATION_WHEN","UNKNOWN");pending=null;}
                else{answers.put("VIBRATION_WHEN_REASK","ASKED");reply=pending.text();}
            }
            if(symptom==null&&previous.errorCode()==null&&!inquiry&&pending==null&&reply==null){
                if(questions>=1){intent="UNCLASSIFIED";reply="증상을 특정하지 못했어요. 공식 점검을 통해 제품 상태를 확인해 주세요.";}
                else{
                    List<String> options=candidates.isEmpty()?SymptomCatalog.forCategory(category).stream().map(SymptomCatalog.Symptom::label).toList():candidates.stream().map(c->SymptomCatalog.label(category,c.code())).toList();
                    var choices=new ArrayList<>(options);choices.add("잘 모르겠어요");
                    pending=new DialogueState.Question("SYMPTOM","어떤 증상인가요? 아래에서 고르거나 증상을 말씀해 주세요.",List.copyOf(choices));questions++;
                    reply=pending.text();
                }
            }
            if(category==ProductCategory.WASHER&&"VIBRATION".equals(symptom)&&!answers.containsKey("VIBRATION_WHEN")&&pending==null&&!inquiry&&reply==null){
                String timing=timing(input);
                if(timing!=null)answers.put("VIBRATION_WHEN",timing);
                else{pending=new DialogueState.Question("VIBRATION_WHEN","세탁기가 언제 심하게 흔들리나요?",List.of("탈수할 때","세탁할 때","항상","잘 모르겠어요"));reply=pending.text();}
            }
            // Manuals separate a full stop from weak cooling. With too little information, ask only this, with choices.
            if(category==ProductCategory.REFRIGERATOR&&"COOLING".equals(symptom)&&!answers.containsKey(COOLING_STATE)&&!answers.containsKey(CHECK_ITEMS)&&pending==null&&!inquiry&&reply==null){
                String state=coolingState(input,false);
                if(state!=null)answers.put(COOLING_STATE,state);
                else{pending=new DialogueState.Question(COOLING_STATE,"냉장실과 냉동실이 모두 전혀 차갑지 않나요, 아니면 차갑긴 하지만 약한가요?",List.of(COOLING_OPTIONS.get("NONE"),COOLING_OPTIONS.get("WEAK"),"잘 모르겠어요"));reply=pending.text();}
            }
        }
        boolean symptomChanged=!Objects.equals(previous.symptomCode(),symptom);
        var done=new ArrayList<String>(symptomChanged?List.of():previous.doneSteps());
        int current=symptomChanged?0:previous.currentStep();boolean closed=previous.closed();
        String selfCare=null;
        // Generative step IDs alone are insufficient: require an exact reported action or the displayed step.
        for(var step:approvedSteps){
            String code=Objects.toString(step.get("step_code"),"");
            boolean applicable=previous.errorCode()!=null&&Objects.equals(step.get("error_code"),previous.errorCode())||symptom!=null&&Objects.equals(step.get("symptom_code"),symptom);
            if(!applicable)continue;
            String shortReply=input.strip().replaceAll("[.!]","");
            boolean referring=code.equals(displayedStep)&&pending==null&&!inquiry&&shortReply.matches(
                    "(그건|그거|그 단계는|안내한 조치는)?\\s*(이미\\s*)?(확인했어요|해\\s*봤어요|했어요|완료했어요)"
                    +"(\\s*(그래도|여전히|아직)?\\s*(안\\s*돼요|안\\s*되네요|해결되지\\s*않았어요))?|"
                    +"(그래도|여전히|아직)?\\s*(안\\s*돼요|안\\s*되네요|해결되지\\s*않았어요)|"
                    +"(이제\\s*)?(해결됐어요|해결되었어요|돼요|정상\\s*작동해요)");
            String text=Objects.toString(step.getOrDefault("text",step.get("instruction")),"");
            boolean notPerformed=input.matches("(?s).*((안|못)\\s*(했|해 ?봤|확인|완료)|하지\\s*않|아직.{0,8}(안|못)).*");
            boolean literal=(!code.isBlank()&&input.contains(code)||!text.isBlank()&&normalize(input).contains(normalize(text)))&&input.matches("(?s).*(이미|했|해 ?봤|완료|확인했).*")&&!notPerformed;
            boolean failed=referring&&input.matches("(?s).*(그래도|여전히|아직|안 ?돼|안 ?되|해결되지).*" );
            boolean solved=referring&&!failed&&input.matches("(?s).*(해결됐|해결되었|이제.{0,5}(돼|되)|정상.{0,3}(작동|동작)).*");
            if(literal&&interpretation.doneSteps().contains(code)||referring&&(failed||solved||input.contains("이미"))){
                if(!done.contains(code)){done.add(code);current++;}
                selfCare=solved?"RESOLVED":"NOT_RESOLVED";closed=solved;
            }
        }
        if(checkSolved){closed=true;selfCare="RESOLVED";}
        if(closed){pending=null;reply="해결되었습니다. 새로운 증상은 새 상담에서 시작해 주세요.";}
        var dialogue=new DialogueState(intent,interpretation.mentionedCategory(),interpretation.symptomCandidates(),symptomText,pending,questions,answers,interpretation.source(),selfCare);
        return new Transition(copy(previous,symptom,done,current,closed,dialogue),reply);
    }
    private static ConsultationState copy(ConsultationState state,String symptom,List<String> done,int current,boolean closed,DialogueState dialogue){
        boolean same=Objects.equals(symptom,state.symptomCode());
        var selfCare=same?state.selfCare():SelfCareState.start();
        if(same)for(String code:done)if(!state.doneSteps().contains(code))selfCare=selfCare.result(code,closed);
        return new ConsultationState(state.riskStatus(),state.riskCase(),state.riskQuestionNo(),state.errorCode(),symptom,same?done:List.of(),same?current:0,closed,dialogue,selfCare);
    }
    private static String normalize(String text){return text.replaceAll("[\\s\\p{Punct}]","");}
    // Manual self-check steps (observation items), one per turn: "해결됐나요?" then the next step or the summary.
    public static final String SELF_CHECK="SELF_CHECK",CHECK_ITEMS="CHECK_ITEMS",CHECK_INDEX="CHECK_INDEX",CHECK_DONE="CHECK_DONE";
    private static final List<String> NEXT_OPTIONS=List.of("해결됐어요","아니요, 다음 단계"),LAST_OPTIONS=List.of("해결됐어요","아니요, 해결되지 않았어요");
    public static List<String> checkItems(Map<String,String> answers){
        String value=answers.get(CHECK_ITEMS);return value==null||value.isBlank()?List.of():List.of(value.split("\n"));
    }
    public static DialogueState.Question stepQuestion(List<String> items,int index){
        return new DialogueState.Question(SELF_CHECK,"확인한 뒤 해결됐나요?",index+1<items.size()?NEXT_OPTIONS:LAST_OPTIONS);
    }
    public static String stepReply(List<String> items,int index,String intro){
        var lines=new ArrayList<String>();if(intro!=null)lines.add(intro);
        lines.add((index+1)+"/"+items.size()+"단계: "+items.get(index));
        lines.add(stepQuestion(items,index).text());
        return String.join("\n\n",lines);
    }
    static String checkResult(String input){
        String text=input.strip();
        if(text.matches("(?s).*(아니|안\\s*돼|안\\s*되|여전히|그대로|해결되지|다음\\s*단계|못\\s*했).*"))return "NOT_SOLVED";
        if(text.matches("(?s).*(해결\\s*됐|해결되었|이제\\s*(돼|되)|정상).*"))return "SOLVED";
        return "UNKNOWN";
    }
    public static final String COOLING_STATE="COOLING_STATE";
    private static final Map<String,String> COOLING_OPTIONS=Map.of("NONE","모두 전혀 차갑지 않아요","WEAK","차갑긴 하지만 약해요");
    private static final Map<String,String> COOLING_LABELS=Map.of("NONE","냉장·냉동 모두 전혀 차갑지 않음","WEAK","차갑긴 하지만 냉각이 약함");
    /** Customer-facing label of a stored cooling observation; null when absent or unknown. */
    public static String coolingLabel(String state){return state==null?null:COOLING_LABELS.get(state);}
    /** A first message needs an explicit marker; an answer to the question may use plainer words. */
    static String coolingState(String input,boolean answering){
        String text=input.strip();
        if(text.matches("(?s).*(약해|약하|약함|덜\\s*(시원|차가)|미지근).*"))return "WEAK";
        if(text.matches("(?s).*(전혀|아예|하나도|완전히).*"))return "NONE";
        if(answering&&text.matches("(?s).*(안\\s*차가|차갑지\\s*않|안\\s*돼|안\\s*되|둘\\s*다|모두).*"))return "NONE";
        return null;
    }
    private static String timing(String input){
        if(input.contains("탈수"))return "SPIN";
        if(input.matches("(?s).*(세탁할|세탁 중|세탁중|세탁 때).*"))return "WASH";
        if(input.matches("(?s).*(항상|계속|내내).*"))return "ALWAYS";
        return null;
    }
    private DialogueRouter(){ }
}
