package com.project.backend.mcp.agent;

import java.util.*;
import com.project.backend.mcp.tool.ToolEnvelope;

/** Reviewed step text and questions are restored verbatim, without asking a model to infer actions. */
public final class DiagnosticGuidance {
    private DiagnosticGuidance(){ }
    public static String format(ToolEnvelope result){
        var data=result.data()==null?Map.<String,Object>of():result.data();
        String skipped=Objects.toString(data.get("skip_reason"),"");
        if(data.get("question") instanceof Map<?,?> question)
            return question.get("text")+"\n예·아니요·모르겠어요로 답해 주세요. 확인 전에는 조치를 안내하지 않습니다.";
        if("RESOLVED".equals(skipped))return "해결되었습니다. 새로운 증상은 새 상담에서 시작해 주세요.";
        if("ALL_DONE".equals(skipped)||"NO_SELF_CARE".equals(skipped)||skipped.startsWith("PRECONDITION_")){
            String label=switch(skipped){case "ALL_DONE"->"검토된 단계를 모두 마쳤지만 해결되지 않았습니다.";case "NO_SELF_CARE"->"검토 결과 이 조건에 제공할 자가조치가 없습니다.";case "PRECONDITION_NOT_MET"->"선행 조건이 충족되지 않아 이 조치를 진행할 수 없습니다.";default->"선행 조건을 확인하지 못해 조치를 진행할 수 없습니다.";};
            return label+"\n"+Objects.toString(data.get("followup"),"추가 조작을 하지 말고 공식 상담으로 제품 상태를 확인해 주세요.");
        }
        if("NEEDS_INPUT".equals(result.status()))return "마이페이지에 해당 품목의 제품을 등록해 주세요. 같은 품목의 제품이 여러 개라면 상담할 제품의 표시 이름이나 모델명을 대화로 알려 주세요.";
        if("OK".equals(result.status())&&data.get("steps") instanceof List<?> steps&&!steps.isEmpty()){
            var step=(Map<?,?>)steps.getFirst();return Objects.toString(step.get("text"),"")+"\n"+Objects.toString(step.get("caution"),"")+"\n이 단계 후 해결됐는지 알려 주세요. 이미 수행했고 해결되지 않았다면 아니요로 답해 주세요.";
        }
        return "이 제품·증상에는 검토를 마친 단계별 자가점검 절차가 아직 없어요. 자가조치가 없다고 확정한 것은 아니며, 문제가 계속되면 공식 점검을 받아 주세요.";
    }
}
