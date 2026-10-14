package com.project.backend.mcp.tool.handler;

import static com.project.backend.mcp.tool.ToolDataCatalog.*;
import static com.project.backend.mcp.tool.ToolSupport.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.project.backend.mcp.consultation.ConsultationService;
import com.project.backend.mcp.tool.*;
import com.project.backend.warranty.*;

/**
 * Reference repair-cost ranges from the v3 cost table: weekday median and 80% range plus the table's visit-fee surcharge
 * for the time slot and month. Never a quote, and never a free-repair or fee-waiver decision.
 */
@Component
public class EstimateRepairCostTool implements HomeCareTool {
    public static final String NOTICE="※ 위 금액은 참고용 예상 범위이며 견적이 아닙니다. 실제 수리비는 엔지니어 점검 결과에 따라 달라질 수 있습니다.";
    private final ToolSupport support;
    private final ToolDataCatalog catalog;
    private final WarrantyService warranty;
    private final GetRepairCandidatesTool candidates;
    public EstimateRepairCostTool(ToolSupport support,ToolDataCatalog catalog,WarrantyService warranty,GetRepairCandidatesTool candidates){this.support=support;this.catalog=catalog;this.warranty=warranty;this.candidates=candidates;}
    public String name(){return "estimate_repair_cost";}
    public Set<String> arguments(){return Set.of("conversation_id","candidate_set_id","candidate_ids","confidence","time_slot","month","product_category");}
    public ToolEnvelope execute(String email,ConsultationService.Snapshot s,Map<String,Object> args){
        var choices=candidates.allowedParts(s);
        Integer visit=choices.isEmpty()?null:((Number)choices.getFirst().get("baseVisit")).intValue();
        var basis=Basis.of("UNDETERMINED",List.of(PARTS),"수리비는 견적이 아니라 참고용 예상 범위입니다.",
                visit==null?"평일 출장비 기준 값을 확인하지 못했습니다. 0원이나 면제로 해석하지 않습니다.":"평일 출장비 "+String.format(Locale.ROOT,"%,d",visit)+"원 기준 추정치이며 야간·휴일·6~8월은 수리비 표의 출장비 가산만 더합니다.",
                validationCaveat(),"보증·무상 여부와 출장비·기술료 면제는 판정하지 않습니다.","원인 후보는 서로 대안이며 비용을 합산하지 않습니다.");
        if(List.of("SUSPECT","CONFIRMED").contains(s.state().riskStatus()))return support.result(s,"BLOCKED_BY_SAFETY",null,List.of(),basis,"위험 확인을 먼저 마쳐 주세요.");
        ToolSupport.category(s,args);
        String slot=timeSlot(args.get("time_slot"));Integer month=month(args.get("month"));
        var product=support.product(email,s);
        if(!candidates.candidateSet(s,product).equals(args.get("candidate_set_id")))throw new ResponseStatusException(HttpStatus.CONFLICT,"CANDIDATE_SET_STALE");
        List<String> ids=strings(args,"candidate_ids");if(ids.size()>2||new HashSet<>(ids).size()!=ids.size())bad("원인 부품은 서로 다른 후보 최대 2개입니다.");
        for(String id:ids)if(choices.stream().noneMatch(p->id.equals(text(p,"id"))))bad("CANDIDATE_NOT_ALLOWED");
        double confidence=args.get("confidence") instanceof Number n?n.doubleValue():0;
        if(confidence<0||confidence>1||!Double.isFinite(confidence))bad("확신도는 0~1입니다.");
        var coverage=product==null?null:warranty.check(product.warranty());
        var alternatives=new ArrayList<Map<String,Object>>();
        var selected=candidates.execute(email,s,Map.of());
        var supported=(List<Map<String,Object>>)selected.data().get("supported_candidates");var insufficient=new ArrayList<String>();
        if(!ids.isEmpty()&&confidence>=0.5)for(String id:ids){
            var p=choices.stream().filter(x->id.equals(text(x,"id"))).findFirst().orElseThrow();
            var origin=supported.stream().filter(c->id.equals(text(c,"id"))).findFirst();
            if(origin.isEmpty()){insufficient.add("부품 "+p.get("name")+": 현재 증상과의 연결 근거 또는 명시적 부품 비용 문의가 없습니다.");continue;}
            if(!(p.get("calibrationCases") instanceof Number count)||count.intValue()<RepairCandidatePolicy.MINIMUM_CASES){
                insufficient.add("부품 "+p.get("name")+": 국내 같은 부품 사례가 "+RepairCandidatePolicy.MINIMUM_CASES+"건 미만입니다.");continue;
            }
            int median=whole(p,"empiricalMedian"),low=whole(p,"empiricalMin"),high=whole(p,"empiricalMax"),extra=surcharge(p,slot,month);
            alternatives.add(map("candidate_id",id,"part_name",p.get("name"),"cost_source",origin.get().get("basis"),"candidate_basis",origin.get().get("description"),
                    "reference_range",amount(low,high,median),
                    "surcharge",map("time_slot",slot,"month",month,"amount",extra,"status",month==null?"MONTH_UNKNOWN":"APPLIED"),
                    "expected_range",amount(low+extra,high+extra,median+extra),
                    "base_visit_fee",p.get("baseVisit"),"confidence_grade",p.get("confidenceGrade"),"confidence",confidenceOf(text(p,"confidenceGrade")),
                    "calibration_cases",p.get("calibrationCases"),"warranty_note",warrantyNote(coverage,p),"evidence_ids",origin.get().get("evidence_ids")));
        }
        if(alternatives.isEmpty()&&(ids.isEmpty()||confidence>=0.5))reviewRange(email,s).ifPresent(alternatives::add);
        var warnings=new ArrayList<String>();
        if(month==null)warnings.add("방문 월을 알 수 없어 6~8월 가산은 반영하지 않았습니다. 6~8월에는 출장비 가산이 붙을 수 있습니다.");
        if(!ids.isEmpty()&&confidence<0.5)warnings.add("원인 부품 확신도가 낮아 부품별 비용을 계산하지 않았습니다.");
        if(alternatives.isEmpty()&&insufficient.isEmpty())insufficient.add(confidence<0.5&&!ids.isEmpty()?"후보 확신도가 기준 0.5 미만입니다.":"근거가 있는 부품 후보 또는 검증된 REFERENCE 사례 3건이 부족합니다.");
        var grades=alternatives.stream().map(a->Objects.toString(a.get("confidence"),"UNDETERMINED")).toList();
        String overall=grades.isEmpty()?"UNDETERMINED":grades.contains("UNDETERMINED")?"UNDETERMINED":grades.contains("LOW")?"LOW":grades.contains("MEDIUM")?"MEDIUM":"HIGH";
        var data=map("currency","KRW","time_slot",slot,"month",month,"alternatives",alternatives,
                "basis",map("visit_fee_weekday",visit,"description",catalog.costSource.getOrDefault("basis","평일 출장비 포함 유상 수리 예상 총액")),
                "validation",catalog.costSource.getOrDefault("validation",Map.of()),
                "warranty_status",coverage==null?"NOT_REGISTERED":coverage.productWarranty().periodStatus(),
                "next_action","VISIT_DIAGNOSIS","model_version",catalog.version,"notice",NOTICE);
        data.put("missing_information",insufficient);data.put("candidate_set_id",args.get("candidate_set_id"));
        return support.result(s,alternatives.isEmpty()?"NO_DATA":"PARTIAL",data,selected.evidence(),
                new Basis(overall,basis.files(),basis.caveats(),List.of()),warnings.toArray(String[]::new));
    }
    /** Visit-fee surcharge columns of the cost table. A summer night/holiday value replaces, not adds to, the night value. */
    static int surcharge(Map<String,Object> part,String slot,Integer month){
        boolean night=slot.equals("NIGHT_HOLIDAY"),summer=month!=null&&month>=6&&month<=8;
        String field=summer?night?"summerNightHolidaySurcharge":"summerWeekdaySurcharge":night?"nightHolidaySurcharge":null;
        return field==null?0:whole(part,field);
    }
    static String timeSlot(Object value){
        if(value==null)return "WEEKDAY";
        return switch(value.toString()){case "WEEKDAY","평일"->"WEEKDAY";case "NIGHT_HOLIDAY","야간·휴일"->"NIGHT_HOLIDAY";default->{bad("INVALID_ARGUMENT: 방문 시간대는 평일 또는 야간·휴일입니다.");yield null;}};
    }
    static Integer month(Object value){
        if(value==null)return null;
        if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<1||((Number)value).longValue()>12)bad("INVALID_ARGUMENT: 방문 월은 1~12입니다.");
        return ((Number)value).intValue();
    }
    private static int whole(Map<String,Object> part,String field){
        if(!(part.get(field) instanceof Number n))throw new IllegalStateException("수리비 자료에 값이 없습니다: "+field);
        return n.intValue();
    }
    private static Map<String,Object> amount(Object min,Object max,Object median){return map("min",min,"max",max,"median",median,"status","CONDITIONAL");}
    private String validationCaveat(){
        if(!(catalog.costSource.get("validation") instanceof Map<?,?> v))return "추정 모델 검증 결과가 없어 새 사례의 오차를 판단할 수 없습니다.";
        return "국내 유상 사례 "+v.get("cases")+"건 검증에서 중앙 절대오차 "+v.get("median_absolute_error_percent")+"%, 80% 범위 적중 "+v.get("range_hit_percent")+"%였으며 새 사례에서는 오차가 더 클 수 있습니다.";
    }
    /** A sentence about coverage, never a free-repair amount: personal eligibility is decided after inspection. */
    private static String warrantyNote(WarrantyModels.Result coverage,Map<String,Object> part){
        if(coverage==null)return "등록 제품이 없어 보증을 확인하지 않았습니다. 보증기간 안이라면 실제 비용이 달라질 수 있습니다.";
        String status=coverage.productWarranty().periodStatus();
        if("IN_WARRANTY".equals(status))return "제품 보증기간 안이면 무상 수리 대상일 수 있습니다. 실제 무상 여부는 점검 후 정해집니다.";
        if(Boolean.TRUE.equals(part.get("isCore")))return "주요 부품은 별도 보증이 남아 있을 수 있습니다. 실제 무상 여부는 점검 후 정해집니다.";
        return "EXPIRED".equals(status)?"제품 보증기간이 지났다면 유상 수리 기준 참고값입니다.":"보증 적용 여부를 확인하지 못했습니다. 점검 시 함께 확인해 주세요.";
    }
    /** Review cases are used only when at least three verified paid cases include the visit fee. */
    private Optional<Map<String,Object>> reviewRange(String email,ConsultationService.Snapshot s){
        var refs=candidates.execute(email,s,Map.of());if(refs.data()==null)return Optional.empty();
        var cases=((List<Map<String,Object>>)refs.data().get("reference_cases")).stream().filter(c->Boolean.TRUE.equals(c.get("visit_fee_included")))
                .filter(c->c.get("charged_total") instanceof Number n&&n.doubleValue()==n.intValue()&&n.intValue()>0)
                .filter(c->text(c,"source_url").startsWith("https://")&&validDate(text(c,"repair_date")))
                .collect(java.util.stream.Collectors.toMap(c->text(c,"id"),c->c,(a,b)->a,LinkedHashMap::new)).values().stream().toList();
        if(cases.size()<3)return Optional.empty();
        var totals=cases.stream().map(x->((Number)x.get("charged_total")).intValue()).sorted().toList();
        var range=amount(totals.getFirst(),totals.getLast(),totals.get(totals.size()/2));
        return Optional.of(map("candidate_id",null,"cost_source","REVIEW_RANGE","reference_range",range,"expected_range",range,
                "confidence","LOW","reference_cases",cases));
    }
    private static boolean validDate(String value){try{java.time.LocalDate.parse(value);return true;}catch(RuntimeException invalid){return false;}}
}
