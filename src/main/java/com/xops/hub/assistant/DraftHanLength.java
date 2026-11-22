package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.regex.Pattern;

import static com.xops.hub.assistant.AssistantStore.object;

record DraftHanLength(int min, int max) {
    private static final Pattern RANGE = Pattern.compile(
            "(?<![\\d.])([0-9]{1,9})\\s*(?:[-‐‑‒–—−~～－]|到|至)\\s*([0-9]{1,9})\\s*个?\\s*(?:汉字|中文字|字)(?!符|节)");

    static DraftHanLength parse(String instruction) {
        var match = RANGE.matcher(instruction);
        if (!match.find()) return null;
        int min = Integer.parseInt(match.group(1));
        int max = Integer.parseInt(match.group(2));
        return min <= max ? new DraftHanLength(min, max) : null;
    }

    String prompt(String baseline) {
        return "\n本轮长度目标为 %d–%d 个汉字，原稿实测 %d 个。只数 Unicode 汉字（Han 表意文字，含扩展字），"
                .formatted(min, max, count(baseline))
                + "标点、空格、阿拉伯数字、拉丁字母及表情不计。以汉字目标衡量改写结果，保留事实及证据强度。";
    }

    ObjectNode report(String baseline, String draft) {
        var report = object().put("min", min).put("max", max).put("baseline", count(baseline));
        updateReport(report, draft);
        return report;
    }

    static void updateReport(ObjectNode report, String draft) {
        int actual = count(draft);
        report.put("actual", actual)
                .put("met", actual >= report.path("min").asInt() && actual <= report.path("max").asInt());
    }

    private static int count(String text) {
        return (int) text.codePoints().filter(codePoint -> Character.isIdeographic(codePoint)
                && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN).count();
    }
}
