package com.project.backend.mcp.consultation;

import java.util.*;
import java.util.regex.Pattern;
import com.project.backend.conversation.ProductCategory;
import com.project.backend.mcp.tool.ErrorGuides;
import com.project.backend.mcp.tool.ToolDataCatalog;

/**
 * Literal category codes from the packaged catalog and LG's error guidance; full boundaries preserve model/SKU strings.
 * A display message such as "배수 안됨" counts only when the sentence says it is shown, and every match is stored as
 * the category's canonical code, so a label and its code lead to the same guidance.
 */
public final class ErrorCodeParser {
    private ErrorCodeParser(){ }
    private static final Pattern DISPLAYED=Pattern.compile("표시|떠|뜨|나와|나타|화면|에러|오류|메시지|메세지|알림|깜빡");
    public static String find(ToolDataCatalog catalog,ProductCategory category,String input){
        var codes=new TreeSet<String>(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()));
        for(var source:catalog.knowledge)if(category.name().equals(source.get("category")))for(String raw:ToolDataCatalog.strings(source,"errorCodes")){
            String code=raw.replaceAll("\\s+","").toUpperCase(Locale.ROOT);
            if(code.matches("[A-Z][A-Z0-9-]{1,11}"))codes.add(code);
        }
        // Compatibility with earlier saved/isolated conversations; code presence never proves model applicability.
        codes.addAll(List.of("OE","UE","IE","FE","LE","PE","DE","CE","TE","SE"));
        // Codes and labels that only the error guidance adds need a sentence saying they are displayed.
        var displayed=new TreeSet<String>(codes.comparator());
        for(var entry:catalog.errorGuides)if(category.name().equals(entry.get("category")))
            for(String code:ToolDataCatalog.strings(entry,"codes"))if(!codes.contains(code))displayed.add(code);
        for(String code:codes)if(matches(code,input))return ErrorGuides.canonical(catalog,category,code);
        if(DISPLAYED.matcher(input).find())for(String code:displayed)if(matches(code,input))return ErrorGuides.canonical(catalog,category,code);
        return null;
    }
    private static boolean matches(String code,String input){
        String spelling=String.join("\\s*",code.split(""));
        if(!code.matches("[A-Z0-9]+"))return Pattern.compile(spelling).matcher(input).find();
        return Pattern.compile("(?i)(?<![A-Z0-9./-])"+spelling+"(?![A-Z0-9/-]|\\.[A-Z0-9])").matcher(input).find();
    }
}
