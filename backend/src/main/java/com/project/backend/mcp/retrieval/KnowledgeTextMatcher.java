package com.project.backend.mcp.retrieval;

import java.util.*;
import java.util.regex.Pattern;

/** Retrieval vocabulary only. It never sets consultation symptoms or changes the risk classifier. */
final class KnowledgeTextMatcher {
    private static final Map<String,Pattern> CONCEPTS=new LinkedHashMap<>();
    static {
        concept("VIBRATION","진동|흔들|덜컹|움직|이동|밀려|이탈|요동|춤을\\s*춰|춤(?:을)?\\s*추|위치.{0,12}벗어|자리.{0,8}옮|걸어.{0,4}다니");
        concept("NOISE","소음|시끄|소리.{0,8}(커|크|심)|큰.{0,4}소리");
        concept("DRAIN","배수|물.{0,12}(안|못).{0,5}빠|물.{0,5}남아");
        concept("FILL","급수|물.{0,10}(안|못).{0,5}(들어|받아)");
        concept("FLOOD","침수|물.{0,8}잠[겼긴겨]|세탁실.{0,10}물.{0,5}찼");
        concept("LEAK","누수|물.{0,6}(새요|샌|새어)|물이.{0,5}샙");
        concept("POWER","전원|켜지|안.{0,4}켜|꺼지");
        concept("ODOR","냄새|악취");
        concept("SMOKE","연기|타는|탄.{0,3}냄새");
        concept("FIRE","불꽃|화재|불이.{0,4}났");
        concept("SHOCK","감전|찌릿|스파크");
        concept("DRYING","건조.{0,10}(안|못)|젖어|축축|마르지");
        // "냉장고 안" is the interior; manuals phrase failures as 정지·저하·약해요·되지 않아요.
        concept("COOLING","시원하지|안\\s*시원|차갑지|안\\s*차가|미지근|냉기.{0,6}(약|없|안\\s*나)|온도.{0,6}(안|못)\\s*내려|(냉장|냉동)(실|칸).{0,6}따뜻"
                +"|(냉장|냉동|냉각)(?!고).{0,10}?(안\\s*(돼|되|됨|내려|떨어)|되지\\s*않|못\\s*(해|하)|약[해하함]|저하|정지|부족)");
        concept("FROST","성에|얼음막");
        concept("CLEANING","청소|거름망|필터");
        concept("DOOR","문.{0,8}(안|못).{0,5}(열|닫)|문이.{0,5}(열리|닫히)");
        concept("WARRANTY","보증|무상|유상");
    }
    private static void concept(String name,String regex){CONCEPTS.put(name,Pattern.compile(regex));}
    private static final Set<String> STOP=Set.of("세탁기","건조기","냉장고","김치냉장고","식기세척기","제품","본체","탈수","작동","사용",
            "원래","위치","많이","크게","때","할때","할","확인","알려","주세요","문제","고장","있어요","안돼요","돼요","해요","안","못","내","우리");
    private static String positive(String text){
        return text.replaceAll("(진동|흔들림|소음|냄새|연기|누수)(은|는|이|가)?\\s*없(어요|습니다|고|다|어)?","")
                .replaceAll("흔들리지\\s*않","");
    }
    static Set<String> concepts(String text){
        String positive=positive(text);
        var result=new LinkedHashSet<String>();CONCEPTS.forEach((key,pattern)->{if(pattern.matcher(positive).find())result.add(key);});return result;
    }
    static Set<String> tokens(String text){
        var result=new LinkedHashSet<String>();
        for(String raw:positive(text).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")){
            if(raw.length()<2||STOP.contains(raw)||raw.matches("[a-z]+\\d[a-z0-9]*|[a-z0-9]{30,}"))continue;
            String token=raw.replaceFirst("(에서는|에서|으로|할때|합니다|습니다|어요|아요|해요|나요|인가요|가|이|은|는|을|를|도|요)$","");
            if(token.length()>=2&&!STOP.contains(token))result.add(token);
        }
        return result;
    }
    static double lexical(String query,String primary,String body){
        if(!specificSubject(query,primary))return 0;
        var q=concepts(query);var d=concepts(primary);
        long overlap=q.stream().filter(d::contains).count();
        // A known symptom must occur in the document's subject, not just an unrelated procedure's cautions.
        if(!q.isEmpty()&&overlap==0)return 0;
        double score=overlap*4.0;
        var tokens=tokens(query);int primaryMatches=0;
        for(String token:tokens){
            if(primary.contains(token))primaryMatches++;
            if(primary.contains(token))score+=2;
            else if(body.contains(token))score+=.4;
        }
        if(q.isEmpty()&&(primaryMatches==0||(double)primaryMatches/Math.max(1,tokens.size())<.5))return 0;
        return score;
    }
    static boolean compatible(String query,String primary){
        if(!specificSubject(query,primary))return false;
        var q=concepts(query);var d=concepts(primary);return q.isEmpty()||q.stream().anyMatch(d::contains);
    }
    private static boolean specificSubject(String query,String primary){
        for(String term:List.of("크래프트","정수"))if(query.contains(term)&&!primary.contains(term))return false;
        return true;
    }
    private KnowledgeTextMatcher(){ }
}
