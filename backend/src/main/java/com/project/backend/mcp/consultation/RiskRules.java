package com.project.backend.mcp.consultation;
import java.util.*;
import com.project.backend.conversation.ProductCategory;
public final class RiskRules {
    public record Finding(String code,boolean emergency){ }
    private static final List<String[]> RULES=List.of(
        new String[]{"RC12","사람.*갇|아이.*안.*문.*안|반려.*갇","E"},new String[]{"RC01","불꽃|스파크|불이 붙","E"},
        new String[]{"RC02","연기.*계속|계속.*연기","E"},new String[]{"RC06","감전|찌릿|전기가 와","E"},
        new String[]{"RC03","연기","S"},new String[]{"RC04","타는\\s*냄새|녹는\\s*냄새","S"},
        new String[]{"RC05","치지직|탁탁.*튀","S"},new String[]{"RC07","플러그.*뜨거|코드.*녹|콘센트.*까맣","S"},
        new String[]{"RC08","차단기.*(내려|떨어)|두꺼비집","S"},
        new String[]{"RC09","물.*콘센트|콘센트.*젖|전기.*물.*닿|(?:세탁기|건조기|냉장고|식기세척기|제품)(?:가|는|이|도|에|은)?\\s*(?:침수|물에\\s*잠)|(?:침수된|물에\\s*잠긴)\\s*(?:세탁기|건조기|냉장고|식기세척기|제품)","S"},
        new String[]{"RC10","몸통.*뜨거|손.*대기.*뜨거|비정상.*과열","S"},new String[]{"RC11","가스.*냄새|냉매.*누출","S"},new String[]{"RC13","펑.*소리|유리.*깨","S"});
    // Laundry soaked with oil or solvents can ignite in a dryer or washer; checked only for those categories.
    private static final String FLAMMABLE="(기름|식용유|오일|휘발유|등유|경유|시너|신나|아세톤|벤젠|알코올|인화성).{0,15}(묻|젖|오염|빨래|옷|세탁물|수건|걸레)|(묻|젖)은?.{0,10}(기름|식용유|휘발유|등유|시너|신나)";
    private RiskRules(){ }
    public static Finding detect(String input,ProductCategory category){
        if(input==null)return null;
        String text=input.replaceAll("(?:연기|타는\\s*냄새)(?:는|가)?\\s*(?:없(?:어요|습니다|어|다)?|안\\s*(?:나요|나|남))","");
        for(var r:RULES)if(text.matches("(?s).*"+"(?:"+r[1]+")"+".*"))return new Finding(r[0],r[2].equals("E"));
        if((category==ProductCategory.DRYER||category==ProductCategory.WASHER)&&text.matches("(?s).*(?:"+FLAMMABLE+").*"))return new Finding("RC14",false);
        return null;
    }
    public static List<String> questions(String code){return switch(Objects.toString(code,"")){
        case "RC03"->List.of("흰 김이 아니라 회색·검은 연기인가요?","지금도 나거나 그을린 자국이 있나요?");
        case "RC04"->List.of("플라스틱이나 전선이 타는 듯한 냄새인가요?","제품 뒤쪽이나 전원부에서 나나요?");
        case "RC11"->List.of("제품 주변에서 가스 같은 냄새가 지금도 나나요?");
        case "RC14"->List.of("기름·휘발유·시너 같은 인화성 물질이 묻은 세탁물을 넣었거나 넣으려고 하나요?","지금 제품 안에서 열이나 냄새가 나나요?");
        default->List.of("위험한 이상이 지금도 계속되거나 손상·통증이 있나요?","연기·타는 냄새·젖은 전원부·다친 사람이 함께 있나요?");};}
    public static String warning(){return "안전을 위해 제품 사용을 멈춰 주세요. 위험한 제품이나 전원부를 만지지 말고 안전한 곳에서 긴급 도움 또는 공식 상담을 요청하세요.";}
    /** Service connection sentence that follows every safety warning. */
    public static String serviceLine(String phone){return "점검은 LG전자 고객센터("+phone+")로 연결해 주세요. 확인 전까지 다시 사용하지 마세요.";}
}
