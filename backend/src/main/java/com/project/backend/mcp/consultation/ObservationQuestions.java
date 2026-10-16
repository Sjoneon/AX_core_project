package com.project.backend.mcp.consultation;

import static java.util.Objects.requireNonNull;

import java.util.*;
import com.project.backend.conversation.ProductCategory;

/**
 * Questions a customer answers by looking or listening, without tools or disassembly, to narrow the repair-cost parts.
 * Finding answers link to parts in the observation evidence (backend/scripts/build_symptom_parts.py mirrors them);
 * neutral answers (normal, unknown) narrow nothing.
 */
public final class ObservationQuestions {
    public record Option(String value,String label){ }
    public record Question(String id,String label,String text,String note,List<Option> options){ }
    /** Prefix of the stored answers in the dialogue answers, e.g. OBS_SOUND=NO. */
    public static final String PREFIX="OBS_";
    private static final Option UNKNOWN=new Option("UNKNOWN","잘 모르겠어요");
    private static final Map<String,List<Question>> QUESTIONS=Map.of("REFRIGERATOR/COOLING",List.of(
        new Question("DISPLAY","표시창","표시창(온도가 보이는 화면)은 어떤가요?",null,
            List.of(new Option("NORMAL","평소처럼 켜져 있어요"),new Option("FLASHING","깜빡여요"),new Option("OFF","꺼져 있어요"),new Option("CODE","오류 코드가 보여요"),requireNonNull(UNKNOWN))),
        new Question("SOUND","작동 소리","냉장고 가까이에서 2~3분 들어 보면 '웅' 하는 작동 소리가 나나요?","조용한 제품은 소리가 작을 수 있어요.",
            List.of(new Option("YES","소리가 나요"),new Option("NO","소리가 안 나요"),requireNonNull(UNKNOWN))),
        new Question("FROST","냉동실 성에","냉동실 문을 열면 안쪽 벽에 하얀 성에가 두껍게 끼어 있나요?","성에를 직접 녹이거나 떼어 내지 마세요.",
            List.of(new Option("BACK_WALL","안쪽 벽에 두껍게 있어요"),new Option("DOOR","문 주변에 있어요"),new Option("NONE","없어요"),requireNonNull(UNKNOWN)))));

    public static List<Question> of(ProductCategory category,String symptomCode){
        return category==null||symptomCode==null?List.of():QUESTIONS.getOrDefault(category.name()+"/"+symptomCode,List.of());
    }
    /** Valid answers keyed by question id; an unknown question or option is rejected, a missing answer is left out. */
    public static Map<String,String> validate(ProductCategory category,String symptomCode,Object raw){
        var answers=new LinkedHashMap<String,String>();if(raw==null)return answers;
        if(!(raw instanceof Map<?,?> given))throw new IllegalArgumentException("observations");
        var questions=of(category,symptomCode);
        for(var entry:given.entrySet()){
            var question=questions.stream().filter(q->q.id().equals(entry.getKey())).findFirst().orElseThrow(()->new IllegalArgumentException("observation question"));
            if(question.options().stream().noneMatch(o->o.value().equals(entry.getValue())))throw new IllegalArgumentException("observation answer");
            answers.put(question.id(),(String)entry.getValue());
        }
        return answers;
    }
    /** Stored answers of the dialogue (prefix removed). */
    public static Map<String,String> stored(Map<String,String> dialogueAnswers){
        var answers=new LinkedHashMap<String,String>();
        dialogueAnswers.forEach((key,value)->{if(key.startsWith(PREFIX))answers.put(key.substring(PREFIX.length()),value);});
        return answers;
    }
    /** "표시창: 꺼져 있어요" lines in question order; unknown answers are left out. */
    public static List<String> lines(ProductCategory category,String symptomCode,Map<String,String> answers){
        var lines=new ArrayList<String>();
        for(var question:of(category,symptomCode)){
            String value=answers.get(question.id());if(value==null||UNKNOWN.value().equals(value))continue;
            question.options().stream().filter(o->o.value().equals(value)).findFirst().ifPresent(o->lines.add(question.label()+": "+o.label()));
        }
        return lines;
    }
    private ObservationQuestions(){ }
}
