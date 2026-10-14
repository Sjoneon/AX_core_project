package com.project.backend.mcp.consultation;

import java.util.*;
import java.util.regex.Pattern;
import com.project.backend.conversation.ProductCategory;
import com.project.backend.mcp.tool.ToolDataCatalog;

/** Literal category codes from the packaged catalog; full boundaries preserve model/SKU strings. */
public final class ErrorCodeParser {
    private ErrorCodeParser(){ }
    public static String find(ToolDataCatalog catalog,ProductCategory category,String input){
        var codes=new TreeSet<String>(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()));
        for(var source:catalog.knowledge)if(category.name().equals(source.get("category")))for(String raw:ToolDataCatalog.strings(source,"errorCodes")){
            String code=raw.replaceAll("\\s+","").toUpperCase(Locale.ROOT);
            if(code.matches("[A-Z][A-Z0-9-]{1,11}"))codes.add(code);
        }
        // Compatibility with earlier saved/isolated conversations; code presence never proves model applicability.
        codes.addAll(List.of("OE","UE","IE","FE","LE","PE","DE","CE","TE","SE"));
        for(String code:codes){
            String spelling=String.join("\\s*",code.split(""));
            if(Pattern.compile("(?i)(?<![A-Z0-9./-])"+spelling+"(?![A-Z0-9/-]|\\.[A-Z0-9])").matcher(input).find())return code;
        }
        return null;
    }
}
