package com.project.backend.mcp.consultation;

import java.util.*;

/** Persisted interpretation and factual questions; absent in older saved contexts. */
public record DialogueState(String intent,String mentionedCategory,List<MessageInterpreter.Candidate> symptomCandidates,
        String symptomText,Question pendingQuestion,int symptomQuestionCount,Map<String,String> answers,
        String interpretationSource,String selfCareReply) {
    public record Question(String id,String text,List<String> options) { }
    public DialogueState {
        intent=intent==null?"OTHER":intent;
        symptomCandidates=symptomCandidates==null?List.of():List.copyOf(symptomCandidates);
        answers=answers==null?Map.of():Map.copyOf(answers);
        interpretationSource=interpretationSource==null?"NONE":interpretationSource;
    }
    public static DialogueState start(){return new DialogueState("OTHER",null,List.of(),null,null,0,Map.of(),"NONE",null);}
}
