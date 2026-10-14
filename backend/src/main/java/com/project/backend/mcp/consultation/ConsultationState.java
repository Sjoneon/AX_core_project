package com.project.backend.mcp.consultation;
import java.util.List;
public record ConsultationState(String riskStatus,String riskCase,int riskQuestionNo,String errorCode,
        String symptomCode,List<String> doneSteps,int currentStep,boolean closed,DialogueState dialogue,SelfCareState selfCare) {
    public ConsultationState {
        doneSteps=doneSteps==null?List.of():List.copyOf(doneSteps);
        dialogue=dialogue==null?DialogueState.start():dialogue;
        selfCare=selfCare==null?SelfCareState.start():selfCare;
    }
    public ConsultationState(String riskStatus,String riskCase,int riskQuestionNo,String errorCode,
            String symptomCode,List<String> doneSteps,int currentStep,boolean closed,DialogueState dialogue){
        this(riskStatus,riskCase,riskQuestionNo,errorCode,symptomCode,doneSteps,currentStep,closed,dialogue,SelfCareState.start());
    }
    public ConsultationState(String riskStatus,String riskCase,int riskQuestionNo,String errorCode,
            String symptomCode,List<String> doneSteps,int currentStep,boolean closed){
        this(riskStatus,riskCase,riskQuestionNo,errorCode,symptomCode,doneSteps,currentStep,closed,DialogueState.start());
    }
    public static ConsultationState start(){return new ConsultationState("CLEAR",null,0,null,null,List.of(),0,false);}
    public String searchQuery(String latestInput){
        String query=dialogue.symptomText()==null?latestInput:dialogue.symptomText();
        if(errorCode!=null)query=errorCode;
        return query==null?"증상":query.substring(0,Math.min(300,query.length()));
    }
}
