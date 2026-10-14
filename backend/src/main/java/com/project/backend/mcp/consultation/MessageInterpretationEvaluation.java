package com.project.backend.mcp.consultation;

import static com.project.backend.mcp.tool.ToolDataCatalog.map;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.*;
import com.project.backend.conversation.ProductCategory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Explicit evaluation using synthetic messages and the configured model, without DB or application restart. */
public final class MessageInterpretationEvaluation {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public static void main(String[] args)throws Exception{
        Path root=Path.of("..").toAbsolutePath().normalize();var dotenv=Dotenv.configure().ignoreIfMissing().load();
        String key=dotenv.get("OPENAI_API_KEY"),modelName=dotenv.get("OPENAI_MODEL");
        if(key==null||key.isBlank()||modelName==null||modelName.isBlank())throw new IllegalStateException("Existing OPENAI_API_KEY and OPENAI_MODEL are required");
        var options=OpenAiChatOptions.builder().model(modelName).apiKey(key).timeout(Duration.ofSeconds(8)).maxRetries(0).build();
        var client=ChatClient.create(OpenAiChatModel.builder().options(options).build());
        var rawOutput=new java.util.concurrent.atomic.AtomicReference<String>();
        var interpreter=new MessageInterpreter(input->{String output=client.prompt().system(MessageInterpreter.SYSTEM).user(input).call().content();rawOutput.set(output);return output;},Duration.ofSeconds(8));
        Map<String,Object> specification=JSON.readValue(Files.readString(root.resolve("docs/mcp/symptom_evaluation.json")),new TypeReference<>(){});
        var rows=new ArrayList<Map<String,Object>>();
        for(var raw:(List<?>)specification.get("cases")){
            var test=(Map<String,Object>)raw;var category=ProductCategory.valueOf(test.get("category").toString());String message=test.get("message").toString();
            var context=test.get("context") instanceof Map<?,?> value?value:Map.of();
            String priorSymptom=Objects.toString(context.get("symptom"),null),priorText=Objects.toString(context.get("symptomText"),null);
            var question="VIBRATION_WHEN".equals(context.get("question"))?new DialogueState.Question("VIBRATION_WHEN","세탁기가 언제 심하게 흔들리나요?",List.of("탈수할 때","세탁할 때","항상","잘 모르겠어요")):null;
            var dialogue=new DialogueState("SYMPTOM",null,List.of(),priorText,question,0,Map.of(),"AI",null);
            var previous=new ConsultationState("CLEAR",null,0,null,priorSymptom,List.of(),0,false,dialogue);
            String displayed=Objects.toString(context.get("displayedStep"),null);
            List<Map<String,Object>> approved=Boolean.TRUE.equals(context.get("approvedStep"))?List.of(map("step_code","TEST-1","text","TEST_ONLY 필터 확인","symptom_code","DRAIN","error_code",null)):List.of();
            List<Map<String,Object>> history=priorText==null?List.of():List.of(map("role","user","content",priorText),map("role","assistant","content",question==null?"TEST_ONLY 필터 확인":question.text(),"displayedStep",displayed));
            rawOutput.set(null);long started=System.nanoTime();var result=interpreter.interpret(category,message,dialogue,history,approved);
            var state=DialogueRouter.route(category,previous,message,result,approved,displayed).state();
            String expected=Objects.toString(test.get("symptom"),null),intent=Objects.toString(test.get("intent"),null);
            boolean passed="AI".equals(result.source())&&Objects.equals(expected,state.symptomCode())&&(intent==null||intent.equals(state.dialogue().intent()));
            if(test.containsKey("question"))passed&=Boolean.TRUE.equals(test.get("question"))==(state.dialogue().pendingQuestion()!=null);
            if(test.containsKey("doneSteps"))passed&=Objects.equals(test.get("doneSteps"),state.doneSteps());
            if(test.containsKey("closed"))passed&=Boolean.TRUE.equals(test.get("closed"))==state.closed();
            if(test.containsKey("timing"))passed&=Objects.equals(test.get("timing"),state.dialogue().answers().get("VIBRATION_WHEN"));
            rows.add(map("id",test.get("id"),"category",category,"message",message,"expected_symptom",expected,"expected_intent",intent,
                    "interpretation",result,"symptom",state.symptomCode(),"intent",state.dialogue().intent(),"question",state.dialogue().pendingQuestion(),"answers",state.dialogue().answers(),
                    "done_steps",state.doneSteps(),"closed",state.closed(),"elapsed_ms",(System.nanoTime()-started)/1_000_000,"passed",passed));
            // Synthetic evaluation only: retain malformed responses for diagnosis; production never stores raw model output.
            if(!"AI".equals(result.source()))rows.getLast().put("invalid_model_output",rawOutput.get());
            System.out.println("Interpretation evaluation "+rows.size()+"/"+((List<?>)specification.get("cases")).size()+": "+test.get("id")+" "+(passed?"PASS":"FAIL"));
        }
        long passed=rows.stream().filter(row->Boolean.TRUE.equals(row.get("passed"))).count();
        var report=map("evaluated_date","2026-10-14","model",modelName,"real_model",true,"threshold",DialogueRouter.CONFIDENCE_THRESHOLD,"total",rows.size(),"passed",passed,"cases",rows);
        Files.writeString(root.resolve("docs/benchmarks/symptom_interpretation_20261014.json"),JSON.writeValueAsString(report)+"\n");
        System.out.println("Real message interpretation: "+passed+"/"+rows.size());
        if(passed!=rows.size())throw new IllegalStateException("Check the message interpretation evaluation report");
    }
    private MessageInterpretationEvaluation(){ }
}
