package com.project.backend.mcp.consultation;

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
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public ConsultationService(ConversationRepository conversations,AppUserRepository users,UserProductService products){this.conversations=conversations;this.users=users;this.products=products;}
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
        if(selected!=null&&products.owned(email,selected).getCategory()!=conversation.getCategory())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"제품 품목이 다릅니다.");
        var safety=RiskRules.detect(input,conversation.getCategory());
        String error=old.errorCode();var matcher=Pattern.compile("(?i)(?<![A-Z0-9])(OE|UE|IE|FE|LE|PE|DE|CE|TE|SE|HC-E\\d+)(?![A-Z0-9])").matcher(input);
        if(matcher.find())error=matcher.group().toUpperCase(Locale.ROOT);
        var next=new ConsultationState(safety==null?old.riskStatus():safety.emergency()?"CONFIRMED":"SUSPECT",
                safety==null?old.riskCase():safety.code(),safety==null?old.riskQuestionNo():0,error,old.symptomCode(),old.doneSteps(),old.currentStep(),false);
        if(!Objects.equals(selected,conversation.getProductId())||!Objects.equals(error,old.errorCode()))next=new ConsultationState(next.riskStatus(),next.riskCase(),next.riskQuestionNo(),error,null,List.of(),0,false);
        conversation.setConsultation(selected,input,JSON.writeValueAsString(next));return snapshot(conversation);
    }
    @Transactional public Snapshot confirmProduct(String email,Long id,Long productId){
        var c=owned(email,id);var p=products.owned(email,productId);if(p.getCategory()!=c.getCategory())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"제품 품목이 다릅니다.");
        var previous=state(c);if(previous.closed())throw new ResponseStatusException(HttpStatus.CONFLICT,"CLOSED");
        var s=new ConsultationState(previous.riskStatus(),previous.riskCase(),previous.riskQuestionNo(),previous.errorCode(),null,List.of(),0,false);
        c.setConsultation(productId,c.getLatestInput(),JSON.writeValueAsString(s));return snapshot(c);
    }
    @Transactional public Snapshot riskAnswer(String email,Long id,String answer){
        var c=owned(email,id);var s=state(c);if(!"SUSPECT".equals(s.riskStatus()))throw new ResponseStatusException(HttpStatus.CONFLICT,"위험 확인 질문이 없습니다.");
        if(answer==null||!List.of("YES","NO","UNSURE").contains(answer))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"위험 응답을 확인해 주세요.");
        int next=s.riskQuestionNo()+1;String status="NO".equals(answer)?next>=RiskRules.questions(s.riskCase()).size()?"DISMISSED":"SUSPECT":"CONFIRMED";
        var changed=new ConsultationState(status,s.riskCase(),next,s.errorCode(),s.symptomCode(),s.doneSteps(),s.currentStep(),s.closed());
        c.setConsultation(c.getProductId(),c.getLatestInput(),JSON.writeValueAsString(changed));return snapshot(c);
    }
    @Transactional public void discardEmpty(String email,Long id){var c=owned(email,id);conversations.delete(c);}
    @Transactional public Snapshot stepAnswer(String email,Long id,long version,String code,boolean solved){
        var c=owned(email,id);if(c.getContextRevision()!=version)throw new ResponseStatusException(HttpStatus.CONFLICT,"CONTEXT_STALE");
        var s=state(c);if(s.closed()||List.of("SUSPECT","CONFIRMED").contains(s.riskStatus()))throw new ResponseStatusException(HttpStatus.CONFLICT,"STEP_BLOCKED");
        var done=new ArrayList<>(s.doneSteps());if(done.contains(code))throw new ResponseStatusException(HttpStatus.CONFLICT,"STEP_ALREADY_DONE");done.add(code);
        var changed=new ConsultationState(s.riskStatus(),s.riskCase(),s.riskQuestionNo(),s.errorCode(),s.symptomCode(),done,s.currentStep()+1,solved);
        c.setConsultation(c.getProductId(),c.getLatestInput(),JSON.writeValueAsString(changed));return snapshot(c);
    }
    @Transactional public Snapshot manualWarranty(String email,Long id,com.project.backend.warranty.WarrantyModels.Request input){
        var c=owned(email,id);if(input.category()!=c.getCategory())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"보증 품목이 다릅니다.");
        c.setManualWarranty(JSON.writeValueAsString(input));return snapshot(c);
    }
    @Transactional(readOnly=true) public com.project.backend.warranty.WarrantyModels.Request manualWarranty(String email,Long id){var c=owned(email,id);return c.getManualWarranty()==null?null:JSON.readValue(c.getManualWarranty(),com.project.backend.warranty.WarrantyModels.Request.class);}
}
