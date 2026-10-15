package com.project.backend.mcp.agent;

import java.util.*;
import java.util.regex.Pattern;
import com.project.backend.mcp.tool.KnowledgeDocument;

/**
 * Observation items a manual lists for a symptom ("X를 확인한다"), never an instruction to operate the product.
 * Only short noun phrases pass; clauses that operate, clean, take apart, restart or contact someone are dropped,
 * as are records whose source says to stop self-care and contact service.
 */
public final class ManualCheckItems {
    private static final Pattern CHECK=Pattern.compile("(.+?)(?:을|를)\\s*확인한다\\.?");
    private static final Pattern EXECUTION=Pattern.compile("분해|교체|제거|설치하|조정|조절|청소|세척|닦|누르|눌러|돌리|분리|뽑|꽂|끄|켜고|리셋|초기화|낮추|높이|변경|이동|옮기|넣|빼|붓|채우|비우|열어|열고|닫아|닫고|당기|밀어|조이|풀어|작동시|실행|교환|구입|요청|문의|연락|보충|준비|분산|시작|중단|해동|녹이|말리|헹구|재가동|차단하|꺼내");
    private static final Pattern CONNECTIVE=Pattern.compile("(고|후|면|며|서|해|여|라|게|어|도록)\\s");
    private static final Pattern POINTER=Pattern.compile("(절차|방법|안내|설명|알림|여부를 판단)$");
    private ManualCheckItems(){ }
    public static final int MAX_STEPS=6;
    // Situations a customer can see for themselves; internal parts, repairs and network setup are not self-checks.
    private static final Pattern NOT_OBSERVABLE=Pattern.compile("분해|교체|수리|부품|센서|PCB|기판|모터|히터|압축기|컴프레서|냉매|퓨즈|배선|누전|합선|서비스|제어부|펌프|공유기|네트워크|와이파이|스마트폰");
    /** "운송용 고정볼트가 남은 경우" style branch conditions of a manual record. */
    public static List<String> conditions(KnowledgeDocument document){
        if(stopSelfCare(document)||!(document.fields().get("branches") instanceof Map<?,?> branches)||!(branches.get("action_branches") instanceof List<?> list))return List.of();
        var result=new LinkedHashSet<String>();
        for(Object raw:list)if(raw instanceof Map<?,?> branch&&branch.get("feature_gate")==null&&branch.get("condition_summary") instanceof String condition){
            String value=condition.strip();
            if(value.endsWith("경우")&&value.length()<=40&&!NOT_OBSERVABLE.matcher(value).find())result.add(value);
        }
        return List.copyOf(result);
    }
    /** Self-check sentences for the matched manual records, at most {@link #MAX_STEPS}. */
    public static List<String> sentences(List<KnowledgeDocument> documents){
        var result=new LinkedHashSet<String>();
        for(var document:documents){
            for(String item:steps(of(document)))result.add(withObjectParticle(item)+" 확인해 주세요.");
            for(String condition:conditions(document))result.add(condition+"에 해당하는지 확인해 주세요.");
        }
        return result.stream().limit(MAX_STEPS).toList();
    }
    /** 을/를 by the final syllable; a non-Hangul ending keeps both forms. */
    static String withObjectParticle(String noun){
        char last=noun.charAt(noun.length()-1);
        if(last<0xAC00||last>0xD7A3)return noun+"을(를)";
        return noun+((last-0xAC00)%28==0?"를":"을");
    }
    /** One step per checked object: "보관 온도, 문 닫힘, 식품 배치와 주위 설치 환경" → four steps; other phrases stay whole. */
    public static List<String> steps(List<String> items){
        var steps=new LinkedHashSet<String>();
        for(String item:items){
            if(!item.contains(",")){steps.add(item.strip());continue;}
            var parts=new ArrayList<>(List.of(item.split(",\\s*")));
            String last=parts.removeLast();
            parts.addAll(List.of(last.split("(?<=[가-힣])(?:와|과)\\s+",2)));
            for(String part:parts)if(!part.isBlank())steps.add(part.strip());
        }
        return List.copyOf(steps);
    }
    public static boolean stopSelfCare(KnowledgeDocument document){
        return document.fields().get("constraints") instanceof Map<?,?> constraints&&constraints.get("original") instanceof Map<?,?> original
                &&Boolean.TRUE.equals(original.get("stop_selfcare_and_contact_service"));
    }
    public static List<String> of(KnowledgeDocument document){
        if(stopSelfCare(document))return List.of();
        var clauses=new ArrayList<String>();
        if(document.fields().get("actionDescriptions") instanceof List<?> actions)
            for(Object action:actions)for(String clause:action.toString().split(" / "))clauses.add(clause.strip());
        if(document.fields().get("branches") instanceof Map<?,?> branches&&branches.get("action_branches") instanceof List<?> list)
            for(Object raw:list)if(raw instanceof Map<?,?> branch&&branch.get("feature_gate")==null&&branch.get("action_summary") instanceof String summary)clauses.add(summary.strip());
        var items=new LinkedHashSet<String>();
        for(String clause:clauses){
            var matcher=CHECK.matcher(clause);if(!matcher.matches())continue;
            String item=matcher.group(1).strip();
            if(item.length()>40||item.contains(".")||EXECUTION.matcher(item).find()||CONNECTIVE.matcher(item).find()||POINTER.matcher(item).find())continue;
            items.add(item);
        }
        return List.copyOf(items);
    }
}
