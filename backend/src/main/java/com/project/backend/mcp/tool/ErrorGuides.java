package com.project.backend.mcp.tool;

import static java.util.Objects.requireNonNull;

import static com.project.backend.mcp.tool.ToolDataCatalog.*;
import java.util.*;
import com.project.backend.conversation.ProductCategory;

/**
 * LG's official guidance for a displayed error, read from
 * homecare/error-guides.json.
 *
 * A manual's error table applies to every model the manual covers (a color
 * variant through its representative model);
 * refrigerator guidance is category-wide. A text label ("배수 안됨") and the code
 * with the same manual guidance ("OE")
 * share one canonical code, so both find the same entries and the same part
 * links.
 */
public final class ErrorGuides {
    private ErrorGuides() {
    }

    /**
     * Displayed form (code or label, spaces removed) → canonical code of the
     * category; itself when no entry maps it.
     */
    public static String canonical(ToolDataCatalog catalog, ProductCategory category, String code) {
        if (code == null)
            return null;
        String wanted = normalize(code);
        for (var entry : catalog.errorGuides)
            if (category.name().equals(entry.get("category")) && entry.get("canonical") instanceof String canonical
                    && strings(entry, "codes").contains(wanted))
                return canonical;
        return wanted;
    }

    /**
     * Entries for the canonical code that apply to the model; several entries of
     * one manual are combined in order.
     */
    public static List<Map<String, Object>> entries(ToolDataCatalog catalog, ProductCategory category, String model,
            String code) {
        if (code == null)
            return List.of();
        String wanted = canonical(catalog, category, code);
        var manuals = manuals(catalog, model);
        return catalog.errorGuides.stream().filter(entry -> category.name().equals(entry.get("category"))
                && (wanted.equals(entry.get("canonical")) || strings(entry, "codes").contains(wanted))
                && ("CATEGORY".equals(entry.get("scope"))
                        || strings(entry, "manuals").stream().anyMatch(manuals::contains)))
                .toList();
    }

    /**
     * Customer-facing guidance: steps the customer can take (contact-only items
     * left out), the contact sentences that
     * close the guidance, the display name, meaning, cause and source. Null when no
     * entry applies.
     */
    public static Map<String, Object> guide(ToolDataCatalog catalog, ProductCategory category, String model,
            String code) {
        var found = entries(catalog, category, model, code);
        if (found.isEmpty())
            return null;
        var steps = new LinkedHashSet<String>();
        var contact = new LinkedHashSet<String>();
        for (var entry : found)
            if (entry.get("items") instanceof List<?> items)
                for (Object raw : items) {
                    var item = requireNonNull((Map<?, ?>) raw);
                    String action = Objects.toString(item.get("action"), "").strip();
                    String check = item.get("check") instanceof String text && !text.isBlank() ? text.strip() + " "
                            : "";
                    if (Boolean.TRUE.equals(item.get("contact_only")))
                        contact.add(action);
                    else
                        steps.add(check + action);
                }
        var first = found.getFirst();
        var causes = found.stream().map(e -> e.get("cause")).filter(Objects::nonNull)
                .map(cause -> Objects.toString(cause)).distinct().toList();
        return map("code", canonical(catalog, category, code), "shown", first.get("shown"), "meaning",
                first.get("meaning"), "cause", causes.isEmpty() ? null : String.join(" ", causes),
                "steps", List.copyOf(steps), "contact", List.copyOf(contact), "source_url",
                ((Map<?, ?>) first.get("source")).get("url"));
    }

    /**
     * Manuals of the registered model, or of its representative when it is a color
     * variant without its own list.
     */
    static Set<String> manuals(ToolDataCatalog catalog, String model) {
        if (model == null || !(catalog.models.get(model.strip().toUpperCase(Locale.ROOT)) instanceof Map<?, ?> entry))
            return Set.of();
        var found = new LinkedHashSet<String>();
        if (entry.get("manual_ids") instanceof List<?> ids)
            ids.forEach(id -> found.add(requireNonNull(id).toString()));
        if (found.isEmpty() && entry.get("representative_model_code") instanceof String representative
                && catalog.models.get(representative) instanceof Map<?, ?> rep
                && rep.get("manual_ids") instanceof List<?> ids)
            ids.forEach(id -> found.add(requireNonNull(id).toString()));
        return found;
    }

    static String normalize(String code) {
        return code.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }
}
