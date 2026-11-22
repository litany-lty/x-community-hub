package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentIntelligenceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void adaptsEachSupportedProtocolAndKeepsTheCredentialOutOfSnapshots() throws Exception {
        var transport = new RecordingTransport(mapper.readTree("""
                {"output":[{"type":"message","content":[{"type":"output_text","text":"{\\"ok\\":true}"}]}],
                 "output_text":"{\\"ok\\":true}","usage":{"input_tokens":4,"output_tokens":3}}
                """));
        var client = new CommandCodeTextModelClient(mapper, transport, ref -> "very-secret");
        var binding = binding(TextModelClient.Protocol.RESPONSES);

        var result = client.complete(binding, new TextModelClient.TextRequest("system", "business text", true, true));

        assertThat(result.text()).isEqualTo("{\"ok\":true}");
        assertThat(transport.uri.getPath()).isEqualTo("/v1/responses");
        assertThat(transport.body.path("input").isArray()).isTrue();
        assertThat(transport.headers).containsEntry("Authorization", "Bearer very-secret");
        assertThat(result.modelSnapshot().toString()).doesNotContain("secret", "credential");
    }

    @Test
    void rejectsMergeCandidatesAndEvidenceThatWereNotActuallyProvided() throws Exception {
        var engine = new ContentIntelligenceEngine(mapper);
        JsonNode unknownCandidate = mapper.readTree("""
                {"decision":"SAME_EVENT_NEW_INFORMATION","candidateId":"invented","confidence":"HIGH","reason":"same event"}
                """);
        assertThatThrownBy(() -> engine.parseMergeDecision(unknownCandidate, List.of("topic-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("candidateId");

        JsonNode unknownSource = mapper.readTree("""
                {"summary":"s","claims":[{"claim":"c","relation":"SUPPORTS","sourceIds":["source-9"]}],"angles":[],"unverified":[]}
                """);
        assertThatThrownBy(() -> engine.parseResearch(unknownSource, List.of("source-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceIds");
    }

    @Test
    void normalizesCoveredScoreWeightsInsteadOfTreatingUnknownSignalsAsZero() throws Exception {
        var engine = new ContentIntelligenceEngine(mapper);
        JsonNode result = mapper.readTree("""
                {"title":"标题","summary":"摘要","tags":["AI"],
                 "topic":{"components":{"novelty":80,"traceability":60,"importance":null,"timeliness":null,"depth":null},"reasons":["source-backed"]},
                 "reach":{"components":{"hook":90,"shareability":70,"clarity":null,"followReason":null,"signals":null},"reasons":["clear angle"]}}
                """);

        var score = engine.parseScore(result, ContentIntelligenceEngine.defaultWeights(), false);

        assertThat(score.topicValue()).isEqualTo(70);
        assertThat(score.title()).isEqualTo("标题");
        assertThat(score.tags()).containsExactly("AI");
        assertThat(score.reachPotential()).isEqualTo(80);
        assertThat(score.composite()).isEqualTo(75);
        assertThat(score.topicCoverage()).isEqualTo(0.5);
        assertThat(score.reachCoverage()).isEqualTo(0.5);
        assertThat(score.evidenceStatus()).isEqualTo(ContentIntelligenceEngine.EvidenceStatus.PENDING_VERIFICATION);
    }

    @Test
    void researchClaimsMustCiteAnExcerptBelongingToTheClaimedSource() throws Exception {
        var engine = new ContentIntelligenceEngine(mapper);
        JsonNode result = mapper.readTree("""
                {"summary":"s","claims":[{"claim":"c","relation":"SUPPORTS","sourceIds":["source-1"],"excerptIds":["source-2#p1"]}],"angles":[],"unverified":[]}
                """);
        assertThatThrownBy(() -> engine.parseResearch(result, List.of("source-1", "source-2"),
                Map.of("source-1", java.util.Set.of("source-1#p1"), "source-2", java.util.Set.of("source-2#p1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("excerptIds");
    }

    @Test
    void styleBatchesSplitLongUnicodeSamplesWithoutDroppingText() {
        String longText = "甲😀乙。".repeat(240);
        var batches = StyleBatchPlanner.plan(List.of(
                new StyleBatchPlanner.Sample("1", "short"),
                new StyleBatchPlanner.Sample("2", longText),
                new StyleBatchPlanner.Sample("3", "tail")
        ), 300, 30);

        var coverage = StyleBatchPlanner.coverage(List.of(
                new StyleBatchPlanner.Sample("1", "short"),
                new StyleBatchPlanner.Sample("2", longText),
                new StyleBatchPlanner.Sample("3", "tail")
        ), batches);
        String reconstructed = batches.stream().flatMap(batch -> batch.segments().stream())
                .filter(segment -> segment.sampleId().equals("2"))
                .sorted(java.util.Comparator.comparingInt(StyleBatchPlanner.Segment::segmentIndex))
                .map(StyleBatchPlanner.Segment::text).collect(java.util.stream.Collectors.joining());
        assertThat(reconstructed).isEqualTo(longText);
        assertThat(coverage.complete()).isTrue();
        assertThat(coverage.processedSamples()).isEqualTo(3);
    }

    @Test
    void rejectsLocalResearchTargetsBeforeSendingARequest() {
        assertThatThrownBy(() -> SafeHttpTextSourceReader.validatePublicUri(URI.create("http://127.0.0.1/private")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-public");
    }

    @Test
    void extractsResponsesSearchEventsAndTheirCitedUrls() throws Exception {
        var transport = new RecordingTransport(mapper.readTree("""
                {"output":[
                  {"type":"web_search_call","action":{"query":"primary source"}},
                  {"type":"message","content":[{"type":"output_text","text":"{\\"status\\":\\"ok\\"}",
                    "annotations":[{"type":"url_citation","url":"https://example.org/source","title":"Source"}]}]}
                ]}
                """));
        var client = new CommandCodeTextModelClient(mapper, transport, ref -> "secret");

        var result = client.complete(binding(TextModelClient.Protocol.RESPONSES),
                new TextModelClient.TextRequest("system", "search", true, true));

        assertThat(result.toolEvents()).hasSize(1);
        assertThat(result.searchResults()).singleElement().satisfies(item -> {
            assertThat(item.query()).isEqualTo("primary source");
            assertThat(item.url()).isEqualTo(URI.create("https://example.org/source"));
        });
    }

    @Test
    void realStageContractsRecheckEarlierSourcesAndDraftFromPersistedResearch() throws Exception {
        URI old = URI.create("https://example.org/old"), found = URI.create("https://example.org/new");
        var environment = new org.springframework.mock.env.MockEnvironment()
                .withProperty(AssistantIntelligence.API_KEY_ENV, "fixture");
        var opened = new java.util.ArrayList<URI>();
        var protocols = new java.util.ArrayList<TextModelClient.Protocol>();
        TextSourceReader reader = uri -> {
            opened.add(uri);
            return new TextSourceReader.ReadResult(uri, uri, "text/plain", "事实", "原始新版本依据",
                    "第1段", java.time.Instant.EPOCH, TextSourceReader.Status.READ, "", uri.equals(old) ? List.of(found) : List.of());
        };
        TextModelClient model = (binding, request) -> {
            protocols.add(binding.protocol());
            if (request.webSearch()) return new TextModelClient.ModelResult("", List.of(
                    new TextModelClient.SearchResult("topic", found, "新来源", "", "")), null, "search", mapper.createObjectNode(),
                    Map.of("search", mapper.createObjectNode()));
            String text = request.userText().startsWith("Build a research result") ? """
                    {"summary":"新版","claims":[{"claim":"事实","relation":"SUPPORTS","sourceIds":["source-1"],
                    "excerptIds":["source-1#p1"]},{"claim":"原帖明确给出 4.5×","relation":"SUPPORTS",
                    "sourceIds":["material-123-quoted-article"],"excerptIds":["material-123-quoted-article#p1"]}],"angles":["角度"],"unverified":[]}
                    """ : """
                    {"draft":"符合用户偏好的草稿","usedClaimIds":["claim-1"],"caveats":[],"factChanges":[]}
                    """;
            if (request.userText().startsWith("Write one")) {
                assertThat(request.userText()).contains("不用表情","真实样本","claim-1");
            }
            if (request.userText().startsWith("Improve structure")) {
                assertThat(request.userText()).contains("初稿原文","claim-1","usedClaimIds");
                text = """
                        {"needsAdditionalResearch":false,"draft":"按研究依据润色","usedClaimIds":["claim-1"],"caveats":[]}
                        """;
            }
            return new TextModelClient.ModelResult(text, List.of(), null, "model", mapper.createObjectNode(), Map.of());
        };
        var stages = new AssistantIntelligence(mapper, environment, model, reader);
        var settings = mapper.createObjectNode().put("version", 1).put("model", "plain").put("protocol", "MESSAGES")
                .put("researchModel", "research").put("researchProtocol", "RESPONSES").put("contextTokens", 32000);
        var input = mapper.createObjectNode();
        input.putObject("topic").put("title", "事件");
        input.put("selectedMaterialId", "123").putArray("materials").addObject().put("id", "123")
                .put("quotedUsername","dkundel").put("quotedAuthorId","24").put("quotedId","456")
                .put("articleRole","QUOTED").put("articleTextStatus","FULL").put("version",3)
                .put("articleText","up to 4.5× faster token generation in Codex");
        input.putObject("previousResearch").putArray("sourceReads").addObject().put("url", old.toString()).put("status", "READ");
        var reads = stages.run("RESEARCH", settings, input).withObject("/result");
        assertThat(opened).containsExactly(old, found);
        assertThat(reads.path("pendingSynthesis").asBoolean()).isTrue();
        input.set("researchReads", reads);
        var research = stages.run("RESEARCH_SYNTHESIS", settings, input).withObject("/result");
        assertThat(opened).containsExactly(old, found);
        assertThat(research.path("draftable").asBoolean()).isTrue();
        var materialEvidence = research.path("materialEvidence").path(0);
        assertThat(materialEvidence.path("id").asText()).isEqualTo("material-123-quoted-article");
        assertThat(materialEvidence.path("url").asText()).isEqualTo("https://x.com/dkundel/status/456");
        assertThat(materialEvidence.path("excerpts").path(0).path("text").asText()).contains("4.5×");
        assertThat(research.path("sourceReads")).hasSize(2);
        assertThat(research.path("sourceRelations").path(0).path("relation").asText()).isEqualTo("LINKED_IN_BODY");
        assertThat(research.path("sourceRelations").path(0).path("toSourceId").asText()).isEqualTo("source-2");
        assertThat(research.path("evidence").get(0).path("contentFingerprint").asText()).isNotEmpty();
        input.set("research", research.put("version", 2).put("id", "stored-research"));
        var style = input.putObject("style").put("profile", "精确").put("version", 3).put("learnedCount", 100);
        style.putArray("examples").addObject().put("sampleId", "s1").put("text", "真实样本");
        input.putObject("preferences").put("text", "不用表情");
        var draft = stages.run("DRAFT", settings, input).path("result");
        assertThat(draft.path("text").asText()).isEqualTo("符合用户偏好的草稿");
        assertThat(draft.path("usedClaims").path(0).asText()).isEqualTo("事实");
        input.put("text", "初稿原文").put("instruction", "只改表达");
        var optimized = stages.run("OPTIMIZE", settings, input).path("result");
        assertThat(optimized.path("text").asText()).isEqualTo("按研究依据润色");
        assertThat(optimized.path("usedClaims").path(0).asText()).isEqualTo("事实");
        assertThat(protocols).containsExactly(TextModelClient.Protocol.RESPONSES, TextModelClient.Protocol.RESPONSES,
                TextModelClient.Protocol.MESSAGES, TextModelClient.Protocol.MESSAGES);
        assertThat(opened).containsExactly(old, found);
    }

    @Test
    void aTextReplyDoesNotMakeAResearchBindingReady() {
        TextModelClient model = (binding, request) -> new TextModelClient.ModelResult(
                "{\"status\":\"ok\"}", List.of(), null, "request", mapper.createObjectNode(), Map.of());
        var verifier = new ModelCapabilityVerifier(mapper, model);
        var status = verifier.verify(binding(TextModelClient.Protocol.RESPONSES), true);
        assertThat(status.textGeneration()).isTrue();
        assertThat(status.structuredOutput()).isTrue();
        assertThat(status.searchEvent()).isFalse();
        assertThat(status.sourceReturned()).isFalse();
        assertThat(verifier.verify(binding(TextModelClient.Protocol.RESPONSES), true).ready()).isFalse();
    }

    @Test
    void safeHttpReaderStripsScriptAndStyleAndBoundsCompressedStreams() throws Exception {
        String html = """
                <html><head><title>官方公告</title><style>.hidden{color:red;content:'secret-css'}</style>
                <script>window.__SECRET__ = 'do-not-extract';</script></head>
                <body><main><p>正式发布新模型评测结果。</p><noscript>no-js-fallback</noscript>
                <svg><text>svg-noise</text></svg><a href="/docs">文档</a></main></body></html>
                """;
        var parsed = SafeHttpTextSourceReader.parseHtml(html, URI.create("https://example.org/blog/post"));
        assertThat(parsed.title()).isEqualTo("官方公告");
        assertThat(parsed.text()).contains("正式发布新模型评测结果")
                .doesNotContain("secret-css", "do-not-extract", "no-js-fallback", "svg-noise");
        assertThat(parsed.visibleLinks()).containsExactly(URI.create("https://example.org/docs"));

        var rawOut = new java.io.ByteArrayOutputStream();
        try (var gzip = new java.util.zip.GZIPOutputStream(rawOut)) {
            gzip.write("压缩正文内容".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        byte[] decoded = SafeHttpTextSourceReader.readDecodedBounded(
                new java.io.ByteArrayInputStream(rawOut.toByteArray()), "gzip", 1024);
        assertThat(new String(decoded, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("压缩正文内容");

        var bombOut = new java.io.ByteArrayOutputStream();
        try (var gzip = new java.util.zip.GZIPOutputStream(bombOut)) {
            gzip.write("x".repeat(4096).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        assertThat(SafeHttpTextSourceReader.readDecodedBounded(
                new java.io.ByteArrayInputStream(bombOut.toByteArray()), "gzip", 512)).isNull();
    }

    @Test
    void multiRoundParallelResearchBackfillsPreviewArticleAndRetriesFollowUpQueries() {
        var environment = new org.springframework.mock.env.MockEnvironment()
                .withProperty(AssistantIntelligence.API_KEY_ENV, "test-key");
        URI deadUrl = URI.create("https://example.org/dead");
        URI primaryA = URI.create("https://example.org/report-a");
        URI primaryB = URI.create("https://example.org/report-b");
        var opened = new java.util.concurrent.CopyOnWriteArrayList<URI>();
        var searchRounds = new java.util.concurrent.atomic.AtomicInteger();
        TextSourceReader parallelReader = new TextSourceReader() {
            @Override public int maxConcurrency() { return 2; }
            @Override public ReadResult read(URI uri) {
                opened.add(uri);
                if (uri.equals(deadUrl)) {
                    return new ReadResult(uri, uri, "", "", "", "", java.time.Instant.EPOCH,
                            Status.FAILED, "upstream unavailable", List.of());
                }
                return new ReadResult(uri, uri, "text/html", "Primary " + uri.getPath(),
                        "Verified primary benchmark result for " + uri.getPath(), "第1段",
                        java.time.Instant.EPOCH, Status.READ, "", List.of());
            }
        };
        TextModelClient model = (binding, request) -> {
            if (request.webSearch()) {
                int round = searchRounds.incrementAndGet();
                var hits = round == 1
                        ? List.of(new TextModelClient.SearchResult("q1", deadUrl, "dead", "", "snippet"))
                        : List.of(new TextModelClient.SearchResult("q2", primaryA, "A", "", "a"),
                                  new TextModelClient.SearchResult("q2", primaryB, "B", "", "b"));
                String json = round == 1
                        ? "{\"queries\":[\"follow-up benchmark\"],\"candidateSources\":[],\"questionsToVerify\":[\"q1\",\"q2\"]}"
                        : "{\"queries\":[],\"candidateSources\":[],\"questionsToVerify\":[]}";
                return new TextModelClient.ModelResult(json, hits, null, "r-" + round,
                        mapper.createObjectNode(), Map.of("search", mapper.createObjectNode()));
            }
            String synthesis = """
                    {"summary":"多轮核实完成",
                    "claims":[{"claim":"基准提升","relation":"SUPPORTS","sourceIds":["source-2","material-1001-author-article"],
                    "excerptIds":["source-2#p1","material-1001-author-article#p1"]}],
                    "angles":["一手对比"],"unverified":[]}
                    """;
            return new TextModelClient.ModelResult(synthesis, List.of(), null, "syn", mapper.createObjectNode(), Map.of());
        };
        AssistantIntelligence.ArticleTextResolver resolver = (tweetId, deadline) -> "1001".equals(tweetId)
                ? new com.xops.hub.provider.TwitterApiIoProvider.ArticleBackfill("完整回填的 X 长文正文内容", true)
                : new com.xops.hub.provider.TwitterApiIoProvider.ArticleBackfill("仍是截断预览", false);

        var stages = new AssistantIntelligence(mapper, environment, model, parallelReader, resolver);
        try {
            var settings = mapper.createObjectNode().put("version", 1).put("model", "plain").put("protocol", "MESSAGES")
                    .put("researchModel", "research").put("researchProtocol", "RESPONSES").put("contextTokens", 32000);
            var input = mapper.createObjectNode();
            input.putObject("topic").put("title", "长文回填与多轮搜索");
            var materials = input.put("selectedMaterialId", "1001").putArray("materials");
            materials.addObject().put("id", "1001").put("username", "alice").put("authorId", "101")
                    .put("sourceIdentity", "ROW_ID_VERIFIED").put("version", 1)
                    .put("articleRole", "AUTHOR").put("articleTextStatus", "PREVIEW").put("articleText", "短预览");
            materials.addObject().put("id", "1002").put("username", "bob").put("authorId", "102")
                    .put("sourceIdentity", "ROW_ID_VERIFIED").put("version", 1)
                    .put("articleRole", "AUTHOR").put("articleTextStatus", "PREVIEW").put("articleText", "短预览2");

            var reads = stages.run("RESEARCH", settings, input).withObject("/result");
            assertThat(searchRounds.get()).isGreaterThanOrEqualTo(2);
            assertThat(opened).contains(deadUrl, primaryA, primaryB);
            assertThat(reads.path("articleBackfills").has("1001")).isTrue();
            assertThat(reads.path("articleBackfills").has("1002")).isFalse();
            assertThat(reads.at("/articleBackfills/1001/articleText").asText()).contains("完整回填");
            assertThat(input.at("/materials/1/articleTextStatus").asText()).isEqualTo("PREVIEW");

            input.set("researchReads", reads);
            var research = stages.run("RESEARCH_SYNTHESIS", settings, input).withObject("/result");
            assertThat(research.path("draftable").asBoolean()).isTrue();
            assertThat(research.path("materialEvidence")).extracting(n -> n.path("id").asText())
                    .contains("material-1001-author-article")
                    .doesNotContain("material-1002-author-article");
        } finally {
            stages.closeReadPool();
        }
    }

    private TextModelClient.ModelBinding binding(TextModelClient.Protocol protocol) {
        return new TextModelClient.ModelBinding(
                new TextModelClient.ServiceConnection("command-code", URI.create("https://api.example.test"), "credential-ref", 3),
                "model-1", protocol, 8192, Duration.ofSeconds(10), 1000, "binding-v1");
    }

    private static final class RecordingTransport implements CommandCodeTextModelClient.Transport {
        private final JsonNode response;
        private URI uri;
        private Map<String, String> headers;
        private JsonNode body;

        private RecordingTransport(JsonNode response) {
            this.response = response;
        }

        @Override
        public CommandCodeTextModelClient.TransportResponse post(
                URI uri, Map<String, String> headers, JsonNode body, Duration timeout) {
            this.uri = uri;
            this.headers = headers;
            this.body = body;
            return new CommandCodeTextModelClient.TransportResponse(200, response, Map.of("x-request-id", "req-1"));
        }
    }
}
