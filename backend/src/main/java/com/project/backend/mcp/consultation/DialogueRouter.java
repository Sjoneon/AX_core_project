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
        String intent=interpretation.intent();
        boolean supportedMention=Arrays.stream(ProductCategory.values()).anyMatch(c->c.isSupported()&&c.name().equals(interpretation.mentionedCategory())&&input.contains(c.getLabel()));
        boolean outside="OUT_OF_SCOPE".equals(intent)&&!supportedMention||Set.of("UNSUPPORTED","AIR_CONDITIONER").contains(Objects.toString(interpretation.mentionedCategory(),""));
        boolean mismatch=interpretation.mentionedCategory()!=null&&!outside&&!category.name().equals(interpretation.mentionedCategory());
        if(outside||mismatch){
            var dialogue=new DialogueState(outside?"OUT_OF_SCOPE":"CATEGORY_MISMATCH",interpretation.mentionedCategory(),List.of(),symptomText,null,questions,answers,interpretation.source(),null);
            return new Transition(copy(previous,symptom,previous.doneSteps(),previous.currentStep(),outside,dialogue),outside?
                "지금은 세탁기·건조기·냉장고(김치냉장고 포함)·식기세척기만 안내할 수 있어요. 공식 제조사 상담을 이용해 주세요. 다른 제품은 새 상담에서 시작해 주세요.":
                "현재 "+category.getLabel()+" 상담인데 다른 품목을 말씀하셨어요. 해당 품목을 선택해 새 상담을 시작하거나, 현재 제품의 증상을 알려 주세요.");
        }
        if(!Set.of("COST","WARRANTY","SERVICE").contains(intent)&&input.matches("(?s).*(지금|현재).{0,8}(고장|이상|문제)(은|는|이|가)?\\s*없.*")){
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
                    "(그건|그거|그 단계는|안내한 조치는)?\\s*(이미\\s*)?(확인했어요|해\\s*봤어요|했어요|완료했어요)|"
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
        if(closed){pending=null;reply="해결되었습니다. 새로운 증상은 새 상담에서 시작해 주세요.";}
        var dialogue=new DialogueState(intent,interpretation.mentionedCategory(),interpretation.symptomCandidates(),symptomText,pending,questions,answers,interpretation.source(),selfCare);
        return new Transition(copy(previous,symptom,done,current,closed,dialogue),reply);
    }
    private static ConsultationState copy(ConsultationState state,String symptom,List<String> done,int current,boolean closed,DialogueState dialogue){
        return new ConsultationState(state.riskStatus(),state.riskCase(),state.riskQuestionNo(),state.errorCode(),symptom,done,current,closed,dialogue);
    }
    private static String normalize(String text){return text.replaceAll("[\\s\\p{Punct}]","");}
    private static String timing(String input){
        if(input.contains("탈수"))return "SPIN";
        if(input.matches("(?s).*(세탁할|세탁 중|세탁중|세탁 때).*"))return "WASH";
        if(input.matches("(?s).*(항상|계속|내내).*"))return "ALWAYS";
        return null;
    }
    private DialogueRouter(){ }
}
