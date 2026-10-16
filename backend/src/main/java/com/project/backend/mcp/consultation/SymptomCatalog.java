package com.project.backend.mcp.consultation;

import static java.util.Objects.requireNonNull;

import java.util.*;
import com.project.backend.conversation.ProductCategory;

/** Consultation codes identify symptoms, never causes or approved actions. */
public final class SymptomCatalog {
    public record Symptom(String code, String label) { }
    private static final Map<ProductCategory,List<Symptom>> CODES=Map.of(
        ProductCategory.WASHER,List.of(s("VIBRATION","소음·진동이 심해요"),s("DRAIN","물이 빠지지 않아요"),s("FILL","물이 들어오지 않아요"),s("POWER","전원이 켜지지 않아요"),s("DOOR","문이 열리거나 닫히지 않아요")),
        ProductCategory.DRYER,List.of(s("DRYING","빨래가 마르지 않아요"),s("NOISE","소음·진동이 심해요"),s("POWER","전원이 켜지지 않아요"),s("DOOR","문이 열리거나 닫히지 않아요"),s("ODOR","냄새가 나요")),
        ProductCategory.REFRIGERATOR,List.of(s("COOLING","냉장·냉동이 잘되지 않아요"),s("NOISE","소음·진동이 심해요"),s("FROST","성에가 많이 생겨요"),s("POWER","전원이 켜지지 않아요"),s("ODOR","냄새가 나요")),
        ProductCategory.DISHWASHER,List.of(s("WASHING","그릇이 깨끗하게 씻기지 않아요"),s("DRYING","그릇이 마르지 않아요"),s("DRAIN","물이 빠지지 않아요"),s("FILL","물이 들어오지 않아요"),s("POWER","전원이 켜지지 않아요")));
    private static Symptom s(String code,String label){return new Symptom(code,label);}
    public static List<Symptom> forCategory(ProductCategory category){return CODES.getOrDefault(category,List.of());}
    public static boolean allows(ProductCategory category,String code){return forCategory(category).stream().anyMatch(s->s.code().equals(code));}
    public static String label(ProductCategory category,String code){return forCategory(category).stream().filter(s->s.code().equals(code)).map((Symptom element)->requireNonNull(element).label()).findFirst().orElse("");}
    private SymptomCatalog(){ }
}
