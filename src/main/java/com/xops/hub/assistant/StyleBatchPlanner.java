package com.xops.hub.assistant;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Unicode-safe batching. Every source code point belongs to exactly one segment. */
public final class StyleBatchPlanner {
    private static final int SEGMENT_OVERHEAD = 48;
    private StyleBatchPlanner() { }

    public static List<Batch> plan(List<Sample> samples, int inputTokenBudget, int fixedPromptTokens) {
        int contentBudget = inputTokenBudget - fixedPromptTokens;
        if (contentBudget < 128) throw new IllegalArgumentException("input budget is too small for style extraction");
        int segmentLimit = Math.max(64, contentBudget - SEGMENT_OVERHEAD);
        List<Segment> segments = new ArrayList<>();
        for (Sample sample : samples) segments.addAll(split(sample, segmentLimit));
        List<Batch> batches = new ArrayList<>();
        List<Segment> current = new ArrayList<>();
        int used = 0;
        for (Segment segment : segments) {
            int cost = estimatedTokens(segment.text()) + SEGMENT_OVERHEAD;
            if (!current.isEmpty() && used + cost > contentBudget) {
                batches.add(batch(current));
                current = new ArrayList<>();
                used = 0;
            }
            current.add(segment);
            used += cost;
        }
        if (!current.isEmpty()) batches.add(batch(current));
        return List.copyOf(batches);
    }

    public static Coverage coverage(List<Sample> samples, List<Batch> batches) {
        int total = samples.stream().mapToInt(sample -> sample.text().codePointCount(0, sample.text().length())).sum();
        int processed = batches.stream().flatMap(batch -> batch.segments().stream())
                .mapToInt(segment -> segment.endCodePoint() - segment.startCodePoint()).sum();
        List<String> ids = batches.stream().flatMap(batch -> batch.sampleIds().stream()).distinct().toList();
        return new Coverage(samples.size(), ids.size(), total, processed, processed == total,
                processed == total ? "" : "Some source code points were not assigned to a model batch");
    }

    private static List<Segment> split(Sample sample, int maxCodePoints) {
        int[] points = sample.text().codePoints().toArray();
        List<Segment> segments = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (start < points.length) {
            int hardEnd = Math.min(points.length, start + maxCodePoints);
            int end = preferredBoundary(points, start, hardEnd);
            if (end <= start) end = hardEnd;
            segments.add(new Segment(sample.id(), index++, start, end, new String(points, start, end - start)));
            start = end;
        }
        return segments;
    }

    private static int preferredBoundary(int[] points, int start, int hardEnd) {
        if (hardEnd == points.length) return hardEnd;
        int minimum = start + Math.max(32, (hardEnd - start) / 2);
        for (int i = hardEnd - 1; i >= minimum; i--) {
            int cp = points[i];
            if (cp == '\n' || cp == '。' || cp == '！' || cp == '？' || cp == '.' || cp == '!' || cp == '?') return i + 1;
        }
        return hardEnd;
    }

    static int estimatedTokens(String text) {
        int codePoints = text.codePointCount(0, text.length());
        int utf8 = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        return Math.max((utf8 + 3) / 4, codePoints);
    }

    private static Batch batch(List<Segment> segments) {
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(segments.stream().map(Segment::sampleId).toList()));
        return new Batch(List.copyOf(ids), List.copyOf(segments), ids.size());
    }

    public record Sample(String id, String text) {
        public Sample {
            if (id == null || id.isBlank() || text == null || text.isBlank()) throw new IllegalArgumentException("style sample id and text are required");
        }
    }
    public record Segment(String sampleId, int segmentIndex, int startCodePoint, int endCodePoint, String text) { }
    public record Batch(List<String> sampleIds, List<Segment> segments, int processedSamples) { }
    public record Coverage(int collectedSamples, int processedSamples, int totalCodePoints, int processedCodePoints,
                           boolean complete, String uncoveredReason) { }
}
