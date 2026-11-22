package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static com.xops.hub.assistant.AssistantStore.object;

final class DraftNumberChanges {
    static final String PROMPT = "\n本轮仅以原稿为基准，不添加原稿没有的阿拉伯数字或百分比，即使研究资料中已有该数字。";
    private static final Pattern NO_ADDITION = Pattern.compile(
            "(?:不(?:要|得|能|许)?|禁止|避免)\\s*(?:再\\s*)?(?:添加|增加|新增|加入|引入|补充)\\s*"
                    + "(?:任何\\s*)?(?:(?:额外|新)的?\\s*)?(?:数字|数值|百分比)");
    private static final Pattern NUMBER = Pattern.compile(
            "(?<![0-9])([+−-]?(?:[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)*|\\.[0-9]+))(?:\\h*([%％]))?");

    private DraftNumberChanges() { }

    static boolean requiredBy(String instruction) {
        return NO_ADDITION.matcher(instruction).find();
    }

    static ObjectNode compare(String baseline, String draft) {
        Set<String> added = numbers(draft);
        added.removeAll(numbers(baseline));
        var report = object().put("met", added.isEmpty());
        var values = report.putArray("added");
        added.forEach(values::add);
        return report;
    }

    private static Set<String> numbers(String text) {
        Set<String> values = new LinkedHashSet<>();
        var number = NUMBER.matcher(text);
        while (number.find()) values.add(number.group(1) + (number.group(2) == null ? "" : "%"));
        return values;
    }

    static ObjectNode withReport(AssistantStore.Tx tx, ObjectNode draft) {
        if (!draft.has("numberChanges")) recalculate(tx, draft);
        return draft;
    }

    static void recalculate(AssistantStore.Tx tx, ObjectNode draft) {
        String baseDraftId = draft.path("baseDraftId").asText();
        String taskId = draft.path("originTaskId").asText();
        if (baseDraftId.isBlank() || taskId.isBlank()) return;
        // Read the immutable accepted request so older or manually edited versions use the original instruction and text.
        var accepted = tx.version("task", taskId, 1);
        if (accepted == null || !"OPTIMIZE".equals(accepted.path("kind").asText())) return;
        var input = accepted.path("input");
        if (baseDraftId.equals(input.path("draftId").asText())
                && draft.path("baseVersion").equals(input.path("draftVersion"))
                && input.path("text").isTextual() && draft.path("text").isTextual()
                && requiredBy(input.path("instruction").asText())) {
            draft.set("numberChanges", compare(input.path("text").asText(), draft.path("text").asText()));
        }
    }
}
