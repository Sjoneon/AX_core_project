package com.project.backend.mcp.consultation;
import java.util.List;
public record ConsultationState(String riskStatus,String riskCase,int riskQuestionNo,String errorCode,
        String symptomCode,List<String> doneSteps,int currentStep,boolean closed) {
    public static ConsultationState start(){return new ConsultationState("CLEAR",null,0,null,null,List.of(),0,false);}
}
