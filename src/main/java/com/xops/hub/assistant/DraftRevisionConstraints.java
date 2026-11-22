package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

import static com.xops.hub.assistant.AssistantStore.object;

record DraftRevisionConstraints(DraftHanLength length, boolean noNewNumbers) {
    static DraftRevisionConstraints from(String instruction) {
        return new DraftRevisionConstraints(DraftHanLength.parse(instruction), DraftNumberChanges.requiredBy(instruction));
    }

    boolean requested() { return length != null || noNewNumbers; }

    String prompt(String baseline) {
        return (length == null ? "" : length.prompt(baseline)) + (noNewNumbers ? DraftNumberChanges.PROMPT : "");
    }

    void measure(ObjectNode output, String baseline, String candidate) {
        if (length != null) output.set("hanLength", length.report(baseline, candidate));
        if (noNewNumbers) output.set("numberChanges", DraftNumberChanges.compare(baseline, candidate));
    }

    static ObjectNode checks(JsonNode result) {
        var checks = object();
        for (String field : List.of("hanLength", "numberChanges")) {
            if (result.path(field).isObject()) checks.set(field, result.get(field));
        }
        return checks;
    }

    static boolean unmet(JsonNode checks) {
        return checks.path("hanLength").path("met").asBoolean(true) == false
                || checks.path("numberChanges").path("met").asBoolean(true) == false;
    }

    static String correctionPrompt(JsonNode checks) {
        var prompt = new StringBuilder("\n这是对 candidateDraft 的一次纠偏。currentDraft 是用户原稿，仅用于对照事实、数字和原意。"
                + "输出经过实际改写的一篇完整成稿。保留既有有依据事实和首稿中符合要求的修改，不能直接返回原稿，不补查资料。"
                + "提交前同时复核所有用户给出的约束。");
        var count = checks.path("hanLength");
        if (count.isObject()) {
            int min = count.path("min").asInt(), max = count.path("max").asInt(), actual = count.path("actual").asInt();
            prompt.append("首稿实测 %d 个汉字，允许 %d–%d 个，建议目标 %d 个。".formatted(actual, min, max, (min + max) / 2));
            if (actual < min) prompt.append("请至少增加 %d 个汉字，用已有事实补足表达。".formatted(min - actual));
            if (actual > max) prompt.append("请至少删减 %d 个汉字，先合并重叠的表述。".formatted(actual - max));
        }
        var added = checks.path("numberChanges").path("added");
        if (added.isArray() && !added.isEmpty()) {
            prompt.append("首稿新增了原稿没有的阿拉伯数字或百分比 ").append(added)
                    .append("。删去这些新增数字，用原稿和现有证据可支持的定性表述保留原意；不能换成汉字数字规避。");
        }
        return prompt.toString();
    }
}
