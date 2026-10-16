package com.project.backend.mcp.consultation;

import static java.util.Objects.requireNonNull;

import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import com.project.backend.conversation.*;
import com.project.backend.user.AppUserRepository;
import com.project.backend.product.*;

@Service
public class ConsultationService {
    public record Snapshot(Long id,ProductCategory category,Long productId,String input,ConsultationState state,long version){ }
    private final ConversationRepository conversations;
    private final AppUserRepository users;
    private final UserProductService products;
    private final com.project.backend.mcp.tool.ToolDataCatalog catalog;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public ConsultationService(ConversationRepository conversations,AppUserRepository users,UserProductService products,com.project.backend.mcp.tool.ToolDataCatalog catalog){this.conversations=conversations;this.users=users;this.products=products;this.catalog=catalog;}
    @Transactional(readOnly=true) public Snapshot snapshot(String email,Long id){return snapshot(owned(email,id));}
    private Snapshot snapshot(Conversation conversation){return new Snapshot(conversation.getId(),conversation.getCategory(),conversation.getProductId(),conversation.getLatestInput(),state(conversation),conversation.getContextRevision());}
    public static ConsultationState state(Conversation c){return c.getConsultationContext()==null?ConsultationState.start():JSON.readValue(c.getConsultationContext(),ConsultationState.class);}
    private Conversation owned(String email,Long id){return conversations.findByIdAndUserEmail(id,email).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"상담을 찾을 수 없습니다."));}
    @Transactional public Snapshot begin(String email,Long id,ProductCategory category,String input,Long productId){
        if(category!=null&&!category.isSupported())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"에어컨 상담은 지원 종료되었습니다.");
        Conversation conversation;
        if(id==null){var user=users.findByEmail(email).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED));conversation=conversations.save(new Conversation(user,input,category));}
        else{conversation=owned(email,id);if(category!=null&&category!=conversation.getCategory())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"상담 품목이 다릅니다.");}
        if(conversation.getCategory()!=null&&!conversation.getCategory().isSupported())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"에어컨 상담은 지원 종료되었습니다.");
        var old=state(conversation);if(old.closed())throw new ResponseStatusException(HttpStatus.CONFLICT,"CLOSED");
        Long selected=productId!=null?productId:conversation.getProductId();
        // 같은 품목의 등록 제품이 하나면 자동 연결, 여러 개면 입력한 모델명·표시 이름으로 구분
        if(selected==null)selected=automaticProduct(email,conversation.getCategory(),input);
        if(selected!=null&&products.owned(email,selected).getCategory()!=conversation.getCategory())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"제품 품목이 다릅니다.");
        var safety=RiskRules.detect(input,conversation.getCategory());
        // 이미 확정한 위험은 새로운 의심 문장으로 해제하지 않음
        if("CONFIRMED".equals(old.riskStatus())&&safety!=null&&!safety.emergency())safety=null;
        String error=old.errorCode();String detected=ErrorCodeParser.find(catalog,conversation.getCategory(),input);
        if(detected!=null)error=detected;
        var next=new ConsultationState(safety==null?old.riskStatus():safety.emergency()?"CONFIRMED":"SUSPECT",
                safety==null?old.riskCase():safety.code(),safety==null?old.riskQuestionNo():0,error,old.symptomCode(),old.doneSteps(),old.currentStep(),false,old.dialogue(),safety==null?old.selfCare():SelfCareState.start());
        if(!Objects.equals(selected,conversation.getProductId())||!Objects.equals(error,old.errorCode()))next=new ConsultationState(next.riskStatus(),next.riskCase(),next.riskQuestionNo(),error,null,List.of(),0,false);
        conversation.setConsultation(selected,input,JSON.writeValueAsString(next));return snapshot(conversation);
    }
    private Long automaticProduct(String email,ProductCategory category,String input){
        var candidates=products.list(email).stream().filter(p->p.warranty().category()==category).toList();
        var codes=new HashSet<String>();
        var tokens=Pattern.compile("(?i)(?<![A-Z0-9./-])[A-Z0-9][A-Z0-9./-]{0,39}(?![A-Z0-9./-])").matcher(input);
        while(tokens.find()){
            String code=tokens.group().toUpperCase(Locale.ROOT).replaceAll("[./-]+$","");
            if(code.matches(".*[A-Z].*")&&code.matches(".*[0-9].*"))codes.add(code);
        }
        // 고객이 다른 모델을 입력하면 보유 제품으로 대체하지 않음 (SKU 접미사 보존)
        if(!codes.isEmpty()){
            if(codes.size()!=1)return null;
            var matched=candidates.stream().filter(p->codes.contains(normalizedModel(p.warranty().modelName()))).toList();
            return matched.size()==1?matched.getFirst().id():null;
        }
        // A nickname that is only a category name ("냉장고") is in almost every message, so it tells no product apart;
        // "냉장고 2" wins over a nickname it contains.
        var named=candidates.stream().filter(p->distinctive(p.displayName())&&input.contains(p.displayName().strip())).toList();
        var longest=named.stream().filter(p->named.stream().noneMatch(o->o.displayName().strip().length()>p.displayName().strip().length()&&o.displayName().contains(p.displayName().strip()))).toList();
        if(!longest.isEmpty())return longest.size()==1?longest.getFirst().id():null;
        return candidates.size()==1?candidates.getFirst().id():null;
    }
    private static String normalizedModel(String input){return input==null?"":input.strip().toUpperCase(Locale.ROOT);}
    private static boolean distinctive(String name){
        if(name==null||name.strip().length()<2)return false;
        String compact=name.replaceAll("\\s+","");
        return Arrays.stream(ProductCategory.values()).noneMatch(category->category.getLabel().equals(compact));
    }
    /** Registered products the customer must pick from: none selected yet and two or more of this category, oldest first. */
    @Transactional(readOnly=true) public List<UserProductService.View> productChoices(String email,Snapshot snapshot){
        if(snapshot.productId()!=null)return List.of();
        var candidates=products.list(email).stream().filter(p->p.warranty().category()==snapshot.category()).sorted(Comparator.comparing((UserProductService.View element)->requireNonNull(element).id())).toList();
        return candidates.size()>1?candidates:List.of();
    }
    @Transactional(readOnly=true) public String selectedModel(String email,Snapshot snapshot){
        return snapshot.productId()==null?null:products.owned(email,snapshot.productId()).warranty().modelName();
    }
    @Transactional public Snapshot confirmProduct(String email,Long id,Long productId){
        var c=owned(email,id);var p=products.owned(email,productId);if(p.getCategory()!=c.getCategory())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"제품 품목이 다릅니다.");
        var previous=state(c);if(previous.closed())throw new ResponseStatusException(HttpStatus.CONFLICT,"CLOSED");
        var s=new ConsultationState(previous.riskStatus(),previous.riskCase(),previous.riskQuestionNo(),previous.errorCode(),null,List.of(),0,false);
        c.setConsultation(productId,c.getLatestInput(),JSON.writeValueAsString(s));return snapshot(c);
    }
    @Transactional public Snapshot riskAnswer(String email,Long id,String answer){
        return riskAnswer(email,id,answer,null);
    }
    @Transactional public Snapshot riskAnswer(String email,Long id,String answer,Long expectedVersion){
        var c=owned(email,id);var s=state(c);if(!"SUSPECT".equals(s.riskStatus()))throw new ResponseStatusException(HttpStatus.CONFLICT,"위험 확인 질문이 없습니다.");
        if(expectedVersion!=null&&c.getContextRevision()!=expectedVersion)throw new ResponseStatusException(HttpStatus.CONFLICT,"위험 확인 질문이 변경됐습니다. 최신 질문에 답해 주세요.");
        if(answer==null||!List.of("YES","NO","UNSURE").contains(answer))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"위험 응답을 확인해 주세요.");
        int next=s.riskQuestionNo()+1;String status="NO".equals(answer)?next>=RiskRules.questions(s.riskCase()).size()?"DISMISSED":"SUSPECT":"CONFIRMED";
        var changed=new ConsultationState(status,s.riskCase(),next,s.errorCode(),s.symptomCode(),s.doneSteps(),s.currentStep(),s.closed(),s.dialogue(),SelfCareState.start());
        c.setConsultation(c.getProductId(),c.getLatestInput(),JSON.writeValueAsString(changed));return snapshot(c);
    }
    @Transactional public Snapshot clearDetectedRisk(String email,Long id,long expectedVersion){
        var c=owned(email,id);var s=state(c);
        if(c.getContextRevision()!=expectedVersion||!"SUSPECT".equals(s.riskStatus()))throw new ResponseStatusException(HttpStatus.CONFLICT,"상담 상태가 변경됐습니다.");
        var changed=new ConsultationState("CLEAR",null,0,s.errorCode(),s.symptomCode(),s.doneSteps(),s.currentStep(),s.closed(),s.dialogue(),s.selfCare());
        c.setConsultation(c.getProductId(),c.getLatestInput(),JSON.writeValueAsString(changed));return snapshot(c);
    }
    @Transactional public void discardEmpty(String email,Long id){var c=owned(email,id);conversations.delete(c);}
    @Transactional public Snapshot stepAnswer(String email,Long id,long version,String code,boolean solved){
        var c=owned(email,id);if(c.getContextRevision()!=version)throw new ResponseStatusException(HttpStatus.CONFLICT,"CONTEXT_STALE");
        var s=state(c);if(s.closed()||List.of("SUSPECT","CONFIRMED").contains(s.riskStatus()))throw new ResponseStatusException(HttpStatus.CONFLICT,"STEP_BLOCKED");
        var done=new ArrayList<>(s.doneSteps());if(done.contains(code))throw new ResponseStatusException(HttpStatus.CONFLICT,"STEP_ALREADY_DONE");done.add(code);
        var changed=new ConsultationState(s.riskStatus(),s.riskCase(),s.riskQuestionNo(),s.errorCode(),s.symptomCode(),done,s.currentStep()+1,solved,s.dialogue(),s.selfCare().result(code,solved));
        c.setConsultation(c.getProductId(),c.getLatestInput(),JSON.writeValueAsString(changed));return snapshot(c);
    }
    @Transactional public Snapshot conditionAnswer(String email,Long id,long version,String scope,String question,String answer){
        var c=owned(email,id);var s=state(c);
        if(c.getContextRevision()!=version)throw new ResponseStatusException(HttpStatus.CONFLICT,"CONTEXT_STALE");
        if(s.closed()||List.of("SUSPECT","CONFIRMED").contains(s.riskStatus()))throw new ResponseStatusException(HttpStatus.CONFLICT,"STEP_BLOCKED");
        if(!List.of("YES","NO","UNSURE").contains(answer))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"조건 응답을 확인해 주세요.");
        // A new plan revision cannot reuse completed steps from the previous approval.
        boolean same=Objects.equals(scope,s.selfCare().scope());
        var changed=new ConsultationState(s.riskStatus(),s.riskCase(),s.riskQuestionNo(),s.errorCode(),s.symptomCode(),same?s.doneSteps():List.of(),same?s.currentStep():0,false,s.dialogue(),s.selfCare().answer(scope,question,answer));
        c.setConsultation(c.getProductId(),c.getLatestInput(),JSON.writeValueAsString(changed));return snapshot(c);
    }
    @Transactional public Snapshot manualWarranty(String email,Long id,com.project.backend.warranty.WarrantyModels.Request input){
        var c=owned(email,id);if(input.category()!=c.getCategory())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"보증 품목이 다릅니다.");
        c.setManualWarranty(JSON.writeValueAsString(input));return snapshot(c);
    }
    @Transactional(readOnly=true) public com.project.backend.warranty.WarrantyModels.Request manualWarranty(String email,Long id){var c=owned(email,id);return c.getManualWarranty()==null?null:JSON.readValue(c.getManualWarranty(),com.project.backend.warranty.WarrantyModels.Request.class);}
    @Transactional public Snapshot applyDialogue(String email,Long id,long expectedVersion,ConsultationState next){
        var c=owned(email,id);var old=state(c);
        if(c.getContextRevision()!=expectedVersion)throw new ResponseStatusException(HttpStatus.CONFLICT,"CONTEXT_STALE");
        if(old.closed()||List.of("SUSPECT","CONFIRMED").contains(old.riskStatus()))throw new ResponseStatusException(HttpStatus.CONFLICT,"DIALOGUE_BLOCKED");
        if(!Objects.equals(old.riskStatus(),next.riskStatus())||!Objects.equals(old.riskCase(),next.riskCase())||old.riskQuestionNo()!=next.riskQuestionNo()||!Objects.equals(old.errorCode(),next.errorCode()))throw new IllegalArgumentException("Interpretation cannot change safety or error state");
        if(next.symptomCode()!=null&&!SymptomCatalog.allows(c.getCategory(),next.symptomCode()))throw new IllegalArgumentException("Invalid symptom");
        c.setConsultation(c.getProductId(),c.getLatestInput(),JSON.writeValueAsString(next));return snapshot(c);
    }
}
