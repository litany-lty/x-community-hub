package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.provider.TwitterApiIoProvider;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public final class AssistantIntelligence {
    public static final String BASE_URL_ENV = "X_CONTENT_ASSISTANT_COMMANDCODE_BASE_URL";
    public static final String API_KEY_ENV = "X_CONTENT_ASSISTANT_COMMANDCODE_API_KEY";
    private static final int MAX_SEARCH_ROUNDS = 4;
    private static final int MAX_SOURCES_READ = 8;
    static final List<Stage> VERIFY_ROLES = List.of(Stage.CLASSIFY,Stage.SCORE,Stage.STYLE,Stage.RESEARCH,Stage.DRAFT,Stage.OPTIMIZE);
    private final ObjectMapper mapper;
    private final Environment environment;
    private final TextModelClient modelClient;
    private final TextSourceReader sourceReader;
    private final ArticleTextResolver articleResolver;
    private final ContentAssistantModelTasks tasks;
    private final ModelCapabilityVerifier verifier;
    private final ExecutorService sourceReadPool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "assistant-source-reader");
        t.setDaemon(true);
        return t;
    });

    @FunctionalInterface
    interface ArticleTextResolver {
        TwitterApiIoProvider.ArticleBackfill resolve(String tweetId, Instant deadline);
    }

    public AssistantIntelligence(ObjectMapper mapper, Environment environment) {
        this(mapper, environment, (TwitterApiIoProvider) null);
    }

    @Autowired
    public AssistantIntelligence(ObjectMapper mapper, Environment environment,
                                 @Autowired(required = false) TwitterApiIoProvider twitterProvider) {
        this(mapper, environment, createClient(mapper, environment),
                new SafeHttpTextSourceReader(environment.getProperty(SafeHttpTextSourceReader.DOH_URL_ENV)),
                twitterProvider == null ? null : twitterProvider::fetchArticleText);
    }

    AssistantIntelligence(ObjectMapper mapper, Environment environment, TextModelClient modelClient, TextSourceReader sourceReader) {
        this(mapper, environment, modelClient, sourceReader, null);
    }

    AssistantIntelligence(ObjectMapper mapper, Environment environment, TextModelClient modelClient,
                          TextSourceReader sourceReader, ArticleTextResolver articleResolver) {
        this.mapper = mapper;
        this.environment = environment;
        this.modelClient = modelClient;
        this.sourceReader = sourceReader;
        this.articleResolver = articleResolver;
        this.tasks = new ContentAssistantModelTasks(mapper, modelClient, SearchProvider.fromModelClient(modelClient));
        this.verifier = new ModelCapabilityVerifier(mapper, modelClient);
    }

    @PreDestroy
    void closeReadPool() {
        sourceReadPool.shutdownNow();
    }

    private static TextModelClient createClient(ObjectMapper mapper, Environment environment) {
        var transport = new JavaNetModelTransport(mapper);
        java.util.function.Function<String, String> credential = environment::getProperty;
        var miniMax = new MiniMaxTextModelClient(mapper, transport, credential);
        var commandCode = new CommandCodeTextModelClient(mapper, transport, credential::apply);
        return new TextModelClient() {
            @Override
            public ModelResult complete(ModelBinding binding, TextRequest request) {
                return "minimax".equalsIgnoreCase(binding.connection().serviceName())
                        ? miniMax.complete(binding, request)
                        : commandCode.complete(binding, request);
            }

            @Override
            public boolean requiresExplicitSearchQuery(ModelBinding binding) {
                return "minimax".equalsIgnoreCase(binding.connection().serviceName())
                        ? miniMax.requiresExplicitSearchQuery(binding)
                        : commandCode.requiresExplicitSearchQuery(binding);
            }
        };
    }

    /** Runs one persisted task stage. Call this only from the assistant worker, outside database transactions. */
    public ObjectNode run(String stageName, ObjectNode settings, ObjectNode input) {
        Stage stage = Stage.valueOf(stageName.toUpperCase());
        try {
            Stage bindingRole = switch (stage) {
                case RESEARCH_SYNTHESIS -> Stage.RESEARCH;
                case OPTIMIZE_REVISION -> Stage.OPTIMIZE;
                default -> stage;
            };
            TextModelClient.ModelBinding binding = binding(settings, bindingRole);
            return switch (stage) {
                case CLASSIFY -> classify(binding, input);
                case SCORE -> score(binding, settings, input);
                case RESEARCH -> research(binding, input);
                case RESEARCH_SYNTHESIS -> synthesizeResearch(binding, input);
                case DRAFT -> draft(binding, input);
                case OPTIMIZE, OPTIMIZE_REVISION -> optimize(binding, input, stage == Stage.OPTIMIZE_REVISION);
                case STYLE -> style(binding, input);
                case VERIFY -> verify(settings);
            };
        } catch (AssistantIntelligenceException e) {
            throw e;
        } catch (CommandCodeTextModelClient.ModelCallException e) {
            FailureKind kind = e.resultUnknown() ? FailureKind.RESULT_UNKNOWN : FailureKind.DEFINITE_FAILURE;
            throw new AssistantIntelligenceException(kind, e.retryable(), e.requestId(), e.getMessage(), e);
        }
    }

    private ObjectNode classify(TextModelClient.ModelBinding binding, ObjectNode input) {
        JsonNode raw=input.path("material");
        var material=new ContentAssistantModelTasks.Material(raw.path("id").asText(),raw.path("text").asText(),
                raw.path("quotedText").asText(),raw.path("articleText").asText(),raw.path("publishedAt").asText(),List.of(),
                AssistantTopics.links(raw));
        var candidates=new java.util.ArrayList<ContentAssistantModelTasks.Candidate>();
        for(var c:input.path("candidates")) candidates.add(new ContentAssistantModelTasks.Candidate(c.path("id").asText(),
                c.path("title").asText()+" "+c.path("summary").asText(),c.path("publishedAt").asText(),List.of(),c.has("coreClaims") ? list(c.path("coreClaims"), String.class) : List.of(),
                c.has("sourceLinks") ? list(c.path("sourceLinks"), String.class) : List.of()));
        var value = tasks.decideMerge(binding, material, candidates);
        return result(mapper.valueToTree(value.value()), value.raw());
    }

    private ObjectNode score(TextModelClient.ModelBinding binding, ObjectNode settings, ObjectNode input) {
        var topic = researchTopic(input);
        var value = tasks.score(binding, topic, input.path("researchVerified").asBoolean(false),
                ContentIntelligenceEngine.scoreWeights(settings.path("scoreWeights")));
        return result(mapper.valueToTree(value.value()), value.raw());
    }

    private ObjectNode research(TextModelClient.ModelBinding binding, ObjectNode input) {
        long stageMillis = Math.max(15_000L, binding.timeout().toMillis());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(stageMillis);
        long synthesisReserve = Math.min(TimeUnit.MINUTES.toNanos(3), TimeUnit.MILLISECONDS.toNanos(stageMillis / 3));
        long readReserve = Math.min(TimeUnit.SECONDS.toNanos(40), TimeUnit.MILLISECONDS.toNanos(stageMillis / 6));

        ObjectNode workingInput = input.deepCopy();
        ObjectNode articleBackfills = backfillArticlePreviews(workingInput, deadline - readReserve);
        var topic = researchTopic(workingInput);

        Map<String, TextModelClient.SearchResult> seedTargets = new LinkedHashMap<>();
        JsonNode previous = workingInput.path("previousResearch");
        // Reserve at least half of the source budget for new search results.
        for (JsonNode read : previous.path("sourceReads")) {
            if (seedTargets.size() == 4) break;
            if (!"READ".equals(read.path("status").asText())) continue;
            String url = read.path("requestedUrl").asText(read.path("url").asText());
            String key = AssistantEvidence.normalizedUrl(url);
            if (!key.isBlank()) seedTargets.putIfAbsent(key, new TextModelClient.SearchResult(
                    "", URI.create(url), read.path("title").asText(), "", read.path("publishedAt").asText()));
        }
        for (JsonNode material : workingInput.path("materials")) {
            if (seedTargets.size() == 6) break;
            if (!"ARTICLE_PREVIEW".equals(material.path("textStatus").asText())
                    && !"PREVIEW".equals(material.path("articleTextStatus").asText())) continue;
            String url = material.path("articleUrl").asText();
            String key = AssistantEvidence.normalizedUrl(url);
            if (!key.isBlank()) seedTargets.putIfAbsent(key, new TextModelClient.SearchResult("", URI.create(url), "", "", ""));
        }

        List<String> requestedQuestions = new ArrayList<>();
        for (JsonNode q : workingInput.path("questions")) {
            if (q.isTextual() && !q.asText().isBlank()) requestedQuestions.add(q.asText());
        }

        var sources = new ArrayList<ContentAssistantModelTasks.SourceEvidence>();
        var readRecords = mapper.createArrayNode();
        Map<String, String> finalSources = new LinkedHashMap<>();
        Map<String, List<URI>> linksBySourceId = new LinkedHashMap<>();
        var relations = mapper.createArrayNode();
        Set<String> attemptedUrls = new LinkedHashSet<>();
        List<String> unreadableUrls = new ArrayList<>();
        List<TextModelClient.SearchResult> allSearchResults = new ArrayList<>();
        Map<String, JsonNode> allSearchEvents = new LinkedHashMap<>();
        var queryPlans = mapper.createArrayNode();
        ContentAssistantModelTasks.ModelCallMetadata firstQueryPlan = null;
        TextModelClient.ModelResult lastSearch = null;
        int roundsCompleted = 0;
        boolean boundaryReached = false;
        int totalPlannedTargets = 0;

        for (int round = 1; round <= MAX_SEARCH_ROUNDS; round++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException("research stage interrupted");
            }
            long searchDeadline = deadline - (round == 1 ? synthesisReserve : readReserve);
            if (round > 1 && System.nanoTime() >= searchDeadline) {
                boundaryReached = true;
                break;
            }
            ContentAssistantModelTasks.SearchAttempt searchAttempt;
            if (round == 1) {
                checkStage(deadline);
                searchAttempt = tasks.search(binding, topic, Math.max(System.nanoTime() + TimeUnit.SECONDS.toNanos(5), searchDeadline));
                TextModelClient.ModelResult first = searchAttempt.result();
                if (first.toolEvents().isEmpty() || first.searchResults().isEmpty()) {
                    throw new AssistantIntelligenceException(FailureKind.DEFINITE_FAILURE, false, first.requestId(),
                            "research model returned no web-search event or source results", null);
                }
                firstQueryPlan = searchAttempt.queryPlan();
            } else {
                List<String> focus = requestedQuestions.size() >= round
                        ? List.of(requestedQuestions.get(round - 1)) : requestedQuestions;
                try {
                    searchAttempt = tasks.searchRound(binding, topic, unreadableUrls, focus, round, searchDeadline);
                } catch (RuntimeException followUpError) {
                    if (Thread.currentThread().isInterrupted()) throw followUpError;
                    boundaryReached = true;
                    break;
                }
                if (searchAttempt.result().toolEvents().isEmpty() || searchAttempt.result().searchResults().isEmpty()) {
                    boundaryReached = true;
                    break;
                }
            }

            roundsCompleted = round;
            lastSearch = searchAttempt.result();
            if (searchAttempt.queryPlan() != null) queryPlans.add(mapper.valueToTree(searchAttempt.queryPlan()));
            allSearchResults.addAll(lastSearch.searchResults());
            final int roundNum = round;
            lastSearch.toolEvents().forEach((key, value) -> allSearchEvents.put(
                    roundNum == 1 && !allSearchEvents.containsKey(key) ? key : "r" + roundNum + "-" + key, value));

            List<TextModelClient.SearchResult> batchTargets = new ArrayList<>();
            if (round == 1) {
                for (var entry : seedTargets.entrySet()) {
                    if (attemptedUrls.size() >= MAX_SOURCES_READ) break;
                    if (attemptedUrls.add(entry.getKey())) batchTargets.add(entry.getValue());
                }
            }
            for (var item : lastSearch.searchResults()) {
                if (attemptedUrls.size() >= MAX_SOURCES_READ) break;
                String normalized = AssistantEvidence.normalizedUrl(item.url().toString());
                if (!normalized.isBlank() && attemptedUrls.add(normalized)) batchTargets.add(item);
            }
            totalPlannedTargets += batchTargets.size();

            int concurrency = Math.max(1, Math.min(2, sourceReader.maxConcurrency()));
            for (int index = 0; index < batchTargets.size(); index += concurrency) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.util.concurrent.CancellationException("research stage interrupted");
                }
                if (deadline - System.nanoTime() <= readReserve) {
                    boundaryReached = true;
                    break;
                }
                int endIdx = Math.min(batchTargets.size(), index + concurrency);
                List<TextModelClient.SearchResult> wave = batchTargets.subList(index, endIdx);
                List<TextSourceReader.ReadResult> reads = readWave(wave, deadline);
                for (int w = 0; w < wave.size(); w++) {
                    var item = wave.get(w);
                    var read = reads.get(w);
                    String sourceId = "source-" + (readRecords.size() + 1);
                    String url = read.finalUri() == null ? item.url().toString() : read.finalUri().toString();
                    String title = read.title().isBlank() ? item.title() : read.title();
                    String canonical = AssistantEvidence.normalizedUrl(url);
                    var record = readRecords.addObject().put("id", sourceId).put("requestedUrl", item.url().toString())
                            .put("url", url).put("status", read.status().name()).put("readAt", read.readAt().toString())
                            .put("title", title).put("publisher", safeHost(url)).put("publishedAt", item.publishedAt())
                            .put("contentType", read.contentType()).put("locator", read.locator()).put("detail", read.detail())
                            .put("originalCodePoints", read.text().codePointCount(0, read.text().length()));
                    if (read.status() == TextSourceReader.Status.READ) {
                        linksBySourceId.put(sourceId, read.visibleLinks());
                        String firstSourceId = finalSources.putIfAbsent(canonical, sourceId);
                        if (firstSourceId == null) {
                            sources.add(new ContentAssistantModelTasks.SourceEvidence(sourceId, url, title, safeHost(url),
                                    item.publishedAt(), read.readAt().toString(), read.text(), read.locator(), read.status().name()));
                        } else {
                            record.put("duplicateSource", true);
                            relations.addObject().put("fromSourceId", sourceId).put("toSourceId", firstSourceId)
                                    .put("relation", "SAME_FINAL_URL");
                        }
                    } else {
                        unreadableUrls.add(item.url().toString());
                    }
                }
            }

            if (boundaryReached) break;
            boolean needMoreRounds = sources.isEmpty()
                    || (round < Math.min(MAX_SEARCH_ROUNDS, requestedQuestions.size()) && sources.size() < 4);
            if (attemptedUrls.size() >= MAX_SOURCES_READ) {
                if (needMoreRounds) boundaryReached = true;
                break;
            }
            if (!needMoreRounds) break;
            if (round == MAX_SEARCH_ROUNDS) boundaryReached = true;
        }

        Map<String, String> sourceIdsByUrl = new LinkedHashMap<>(finalSources);
        readRecords.forEach(record -> {
            String finalUrl = AssistantEvidence.normalizedUrl(record.path("url").asText());
            String sourceId = finalSources.getOrDefault(finalUrl, record.path("id").asText());
            sourceIdsByUrl.putIfAbsent(AssistantEvidence.normalizedUrl(record.path("requestedUrl").asText()), sourceId);
        });
        linksBySourceId.forEach((sourceId, links) -> links.stream().map(URI::toString).map(AssistantEvidence::normalizedUrl)
                .map(sourceIdsByUrl::get).filter(targetId -> targetId != null && !targetId.equals(sourceId)).distinct()
                .forEach(targetId -> relations.addObject().put("fromSourceId", sourceId).put("toSourceId", targetId)
                        .put("relation", "LINKED_IN_BODY")));

        var output = mapper.createObjectNode().put("quality", sources.isEmpty() ? "NO_USABLE_EVIDENCE" : "PARTIAL")
                .put("draftable", false).put("pendingSynthesis", !sources.isEmpty());
        output.put("sourcesRead", sources.size());
        output.put("searchRounds", roundsCompleted);
        output.put("boundaryReached", boundaryReached);
        output.put("allSourcesRead", !boundaryReached && readRecords.size() == totalPlannedTargets
                && java.util.stream.StreamSupport.stream(readRecords.spliterator(), false)
                .allMatch(record -> "READ".equals(record.path("status").asText())));
        output.set("searchResults", mapper.valueToTree(allSearchResults));
        output.set("sourceReads", readRecords);
        output.set("sourceRelations", relations);
        output.set("searchEvents", mapper.valueToTree(allSearchEvents));
        output.set("searchUsage", mapper.valueToTree(lastSearch.usage()));
        if (!articleBackfills.isEmpty()) output.set("articleBackfills", articleBackfills);
        if (!sources.isEmpty()) {
            var included = new ArrayList<>(sources);
            included.addAll(materialEvidence(workingInput));
            var prepared = tasks.prepareEvidence(topic, included, binding);
            output.set("evidence", mapper.valueToTree(prepared.stream().filter(source -> !isMaterialSource(source.id())).toList()));
            output.set("materialEvidence", mapper.valueToTree(prepared.stream().filter(source -> isMaterialSource(source.id())).toList()));
        }
        if (firstQueryPlan != null) output.set("searchQueryPlan", mapper.valueToTree(firstQueryPlan));
        if (queryPlans.size() > 1) output.set("searchQueryPlans", queryPlans);
        output.put("searchRequestId", lastSearch.requestId());
        return result(output, lastSearch);
    }

    private ObjectNode backfillArticlePreviews(ObjectNode workingInput, long deadline) {
        ObjectNode backfilled = mapper.createObjectNode();
        if (articleResolver == null) return backfilled;
        for (JsonNode node : workingInput.path("materials")) {
            if (!(node instanceof ObjectNode material)) continue;
            boolean preview = "PREVIEW".equals(material.path("articleTextStatus").asText())
                    || "ARTICLE_PREVIEW".equals(material.path("textStatus").asText());
            if (!preview) continue;
            long remaining = deadline - System.nanoTime();
            if (remaining <= TimeUnit.SECONDS.toNanos(2)) break;
            String role = material.path("articleRole").asText("");
            String tweetId = "QUOTED".equals(role)
                    ? material.path("quotedId").asText("")
                    : material.path("id").asText("");
            if (tweetId.isBlank()) continue;
            Instant callDeadline = Instant.now().plusNanos(Math.min(TimeUnit.SECONDS.toNanos(15), remaining));
            TwitterApiIoProvider.ArticleBackfill resolved = articleResolver.resolve(tweetId, callDeadline);
            if (resolved != null && resolved.full() && !resolved.text().isBlank()) {
                material.put("articleText", resolved.text()).put("articleTextStatus", "FULL");
                if ("ARTICLE_PREVIEW".equals(material.path("textStatus").asText())) {
                    material.put("textStatus", "ARTICLE_FULL");
                }
                if ("AUTHOR".equals(role) && "PREVIEW".equals(material.path("authorTextStatus").asText())) {
                    material.put("authorTextStatus", "FULL");
                }
                backfilled.putObject(material.path("id").asText())
                        .put("articleText", resolved.text())
                        .put("articleRole", role)
                        .put("expectedVersion", material.path("version").asInt(0));
            }
        }
        return backfilled;
    }

    private List<TextSourceReader.ReadResult> readWave(List<TextModelClient.SearchResult> wave, long deadline) {
        if (wave.size() <= 1) {
            return wave.isEmpty() ? List.of() : List.of(sourceReader.read(wave.get(0).url()));
        }
        List<CompletableFuture<TextSourceReader.ReadResult>> futures = wave.stream()
                .map(item -> CompletableFuture.supplyAsync(() -> sourceReader.read(item.url()), sourceReadPool))
                .toList();
        List<TextSourceReader.ReadResult> results = new ArrayList<>(wave.size());
        for (int i = 0; i < wave.size(); i++) {
            URI uri = wave.get(i).url();
            long remainingMs = Math.max(250L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            try {
                results.add(futures.get(i).get(remainingMs, TimeUnit.MILLISECONDS));
            } catch (TimeoutException timeout) {
                futures.get(i).cancel(true);
                results.add(new TextSourceReader.ReadResult(uri, uri, "", "", "", "", Instant.now(),
                        TextSourceReader.Status.FAILED, "read deadline"));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                futures.forEach(f -> f.cancel(true));
                throw new java.util.concurrent.CancellationException("research stage interrupted");
            } catch (ExecutionException failed) {
                results.add(new TextSourceReader.ReadResult(uri, uri, "", "", "", "", Instant.now(),
                        TextSourceReader.Status.FAILED, failed.getCause() == null ? "ExecutionException" : failed.getCause().getClass().getSimpleName()));
            }
        }
        return results;
    }

    private static String safeHost(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private ObjectNode synthesizeResearch(TextModelClient.ModelBinding binding, ObjectNode input) {
        long availableMillis = input.path("synthesisDeadline").asLong(System.currentTimeMillis() + 600_000)
                - System.currentTimeMillis();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(availableMillis);
        checkStage(deadline);
        var output = ((ObjectNode) input.path("researchReads")).deepCopy();
        var evidence = new ArrayList<>(list(output.path("evidence"), ContentAssistantModelTasks.SourceEvidence.class));
        if (output.path("materialEvidence").isArray())
            evidence.addAll(list(output.path("materialEvidence"), ContentAssistantModelTasks.SourceEvidence.class));
        var value = tasks.synthesizeResearch(binding, researchTopic(input), evidence, deadline);
        output.remove(List.of("pendingSynthesis", "id", "version", "updatedAt", "snapshot"));
        if (value.value() == null) {
            output.put("quality", "PARTIAL").put("synthesisError", value.error());
        } else {
            output.remove("synthesisError");
            output.setAll((ObjectNode) mapper.valueToTree(value.value()));
            boolean draftable = value.value().claims().stream().anyMatch(claim ->
                    claim.relation() == ContentIntelligenceEngine.EvidenceRelation.SUPPORTS
                            && claim.sourceIds().stream().anyMatch(id -> !isMaterialSource(id)));
            boolean unresolved = value.value().claims().stream().anyMatch(claim ->
                    claim.relation() == ContentIntelligenceEngine.EvidenceRelation.REFUTES || claim.relation() == ContentIntelligenceEngine.EvidenceRelation.UNVERIFIED);
            boolean fullyCovered = evidence.stream().allMatch(source -> source.originalCodePoints() == source.coveredCodePoints());
            output.put("draftable", draftable);
            output.put("quality", draftable && !unresolved && value.value().unverified().isEmpty()
                    && output.path("allSourcesRead").asBoolean() && fullyCovered ? "SUPPORTED" : "PARTIAL");
        }
        return result(output, value.raw());
    }

    private List<ContentAssistantModelTasks.SourceEvidence> materialEvidence(ObjectNode input) {
        var evidence = new ArrayList<ContentAssistantModelTasks.SourceEvidence>();
        String selected = input.path("selectedMaterialId").asText();
        for (JsonNode material : input.path("materials")) {
            if (!selected.equals(material.path("id").asText())) continue;
            String author = material.path("username").asText();
            String quoteAuthor = material.path("quotedUsername").asText();
            String quoteId = material.path("quotedId").asText();
            boolean ownVerified = "ROW_ID_VERIFIED".equals(material.path("sourceIdentity").asText())
                    && material.path("authorId").asText().matches("[0-9]+");
            boolean quoteVerified = material.path("quotedAuthorId").asText().matches("[0-9]+");
            if (ownVerified && "FULL".equals(material.path("authorTextStatus").asText())) {
                addMaterialEvidence(evidence, material, "author", selected, author, material.path("text").asText());
            }
            if (quoteVerified && "FULL".equals(material.path("quotedTextStatus").asText())) {
                addMaterialEvidence(evidence, material, "quoted", quoteId, quoteAuthor, material.path("quotedText").asText());
            }
            if ("FULL".equals(material.path("articleTextStatus").asText())) {
                switch (material.path("articleRole").asText()) {
                    case "AUTHOR" -> {
                        if (ownVerified) addMaterialEvidence(evidence, material, "author-article", selected, author, material.path("articleText").asText());
                    }
                    case "QUOTED" -> {
                        if (quoteVerified) addMaterialEvidence(evidence, material, "quoted-article", quoteId, quoteAuthor, material.path("articleText").asText());
                    }
                    default -> { }
                }
            }
            break;
        }
        return evidence;
    }

    private void addMaterialEvidence(List<ContentAssistantModelTasks.SourceEvidence> evidence, JsonNode material, String role,
                                     String postId, String author, String text) {
        if (!author.matches("[A-Za-z0-9_]{1,15}") || !postId.matches("[0-9]+") || text.isBlank()) return;
        String url = "https://x.com/" + author + "/status/" + postId;
        long acquired = material.path("updatedAt").asLong();
        String acquiredAt = acquired > 0 ? Instant.ofEpochMilli(acquired).toString() : "";
        String version = material.path("version").asText();
        evidence.add(new ContentAssistantModelTasks.SourceEvidence(
                "material-" + material.path("id").asText() + "-" + role, url, "X " + role + " @" + author,
                "@" + author, "", acquiredAt, text, "原帖 " + postId + " · 素材版本 " + version + " · " + role, "MATERIAL_FULL"));
    }

    private static boolean isMaterialSource(String id) { return id.startsWith("material-"); }

    private ObjectNode draft(TextModelClient.ModelBinding binding, ObjectNode input) {
        var research = draftResearch(input.path("research"));
        var style = draftStyle(input.path("style"));
        String instruction = input.path("instruction").asText("");
        String baseline = input.path("text").asText("");
        boolean revision = !input.path("draftId").asText().isEmpty();
        var constraints = DraftRevisionConstraints.from(revision ? instruction : "");
        instruction += constraints.prompt(baseline);
        if (input.path("questions").isArray() && !input.path("questions").isEmpty())
            instruction += "\n补充研究需要落实的问题：" + input.path("questions");
        var value = tasks.draft(binding, research, style, input.path("preferences").path("text").asText(""),
                instruction, baseline);
        return result(writingResult(value.value(), constraints, baseline), value.raw());
    }

    private ObjectNode optimize(TextModelClient.ModelBinding binding, ObjectNode input, boolean correcting) {
        var research = draftResearch(input.path("research"));
        var style = input.path("style").isObject() ? draftStyle(input.path("style")) : null;
        String baseline = input.path("text").asText("");
        String instruction = input.path("instruction").asText("");
        var constraints = DraftRevisionConstraints.from(instruction);
        instruction += constraints.prompt(baseline);
        var first = input.path("correctionCandidate");
        if (correcting) instruction += DraftRevisionConstraints.correctionPrompt(DraftRevisionConstraints.checks(first));
        try {
            var value = tasks.optimize(binding, baseline, research, style,
                    input.path("preferences").path("text").asText(""), instruction,
                    correcting ? first.path("text").asText() : null);
            ObjectNode output = writingResult(value.value(), constraints, baseline);
            output.put("needsAdditionalResearch", false);
            return result(output, value.raw());
        } catch (ContentAssistantModelTasks.AdditionalResearchRequiredException e) {
            if (correcting) throw new AssistantIntelligenceException(FailureKind.DEFINITE_FAILURE, false, null,
                    "模型未提交修订稿，已保留首次候选和原稿。", e);
            ObjectNode output = mapper.createObjectNode().put("needsAdditionalResearch", true);
            output.set("researchQuestions", mapper.valueToTree(e.questions()));
            output.put("originalText", e.originalDraft());
            return result(output, null);
        }
    }

    private ObjectNode writingResult(ContentIntelligenceEngine.Draft draft, DraftRevisionConstraints constraints, String baseline) {
        ObjectNode output = mapper.valueToTree(draft);
        constraints.measure(output, baseline, draft.text());
        return output;
    }

    private ContentIntelligenceEngine.Research draftResearch(JsonNode research) {
        ObjectNode result = mapper.createObjectNode();
        for (String key : List.of("summary", "claims", "angles", "unverified")) {
            if (research.has(key)) result.set(key, research.get(key));
        }
        return mapper.convertValue(result, ContentIntelligenceEngine.Research.class);
    }

    private ContentAssistantModelTasks.StyleProfile draftStyle(JsonNode style) {
        // Keep persisted workflow metadata out of the writing contract.
        ObjectNode profile = mapper.createObjectNode();
        for (String key : List.of("profile", "processedSampleIds", "exampleSampleIds", "batches", "modelCalls", "examples")) {
            if (style.has(key)) profile.set(key, style.get(key));
        }
        return mapper.convertValue(profile, ContentAssistantModelTasks.StyleProfile.class);
    }

    private ObjectNode style(TextModelClient.ModelBinding binding, ObjectNode input) {
        List<StyleBatchPlanner.Sample> samples = list(input.path("samples"), StyleBatchPlanner.Sample.class);
        int reserve = input.path("promptTokenReserve").asInt(1000);
        List<StyleBatchPlanner.Batch> batches = StyleBatchPlanner.plan(samples, binding.contextTokens() * 9 / 10 - binding.maxOutputTokens(), reserve);
        StyleBatchPlanner.Coverage coverage = StyleBatchPlanner.coverage(samples, batches);
        ContentAssistantModelTasks.StyleProfile profile = tasks.extractStyle(binding, batches);
        ObjectNode output = mapper.valueToTree(profile);
        output.put("collectedSamples", samples.size());
        output.put("processedSamples", profile.processedSampleIds().size());
        output.set("coverage", mapper.valueToTree(coverage));
        var examples = output.putArray("examples");
        var samplesById = new java.util.HashMap<String, String>();
        samples.forEach(sample -> samplesById.put(sample.id(), sample.text()));
        for (String id : profile.exampleSampleIds().stream().distinct().limit(3).toList()) {
            String text = samplesById.get(id);
            int end = text.offsetByCodePoints(0, Math.min(220, text.codePointCount(0, text.length())));
            examples.addObject().put("sampleId", id).put("text", text.substring(0, end));
        }
        var ranges = output.putArray("processedRanges");
        for (var batch : batches) {
            var segments = ranges.addArray();
            for (var segment : batch.segments()) segments.addObject().put("sampleId", segment.sampleId())
                    .put("startCodePoint", segment.startCodePoint()).put("endCodePoint", segment.endCodePoint());
        }
        return result(output, null);
    }

    private TextModelClient.ModelBinding binding(ObjectNode settings, Stage role) {
        String provider = AssistantConnection.selectedProvider(settings, environment);
        String baseUrl = AssistantConnection.endpoint(environment, provider);
        String credential = AssistantConnection.credentialName(provider);
        if (baseUrl == null || baseUrl.isBlank() || environment.getProperty(credential, "").isBlank()) {
            throw new AssistantIntelligenceException(FailureKind.MISSING_CONFIG, false, null, "内容助手模型服务未在服务器配置", null);
        }
        required(settings, "model");
        required(settings, "protocol");
        var config = roleBinding(settings, role, provider);
        TextModelClient.Protocol protocol = TextModelClient.Protocol.valueOf(config.protocol());
        if (!modelClient.supportedProtocols().contains(protocol) || !AssistantConnection.supportsProtocol(provider, config.protocol()))
            throw new AssistantIntelligenceException(FailureKind.MISSING_CONFIG, false, null,
                    "当前模型服务不支持 " + config.protocol() + " 协议，请切换兼容协议", null);
        if (config.context() < 1024 || config.maxOutput() > config.context() - 512)
            throw new AssistantIntelligenceException(FailureKind.MISSING_CONFIG, false, null,
                    role.name()+" 的上下文必须至少保留 512 token 输入空间", null);
        String version = required(settings, "version") + ":" + role.name() + ":" + Integer.toUnsignedString(
                java.util.Objects.hash(config.model(),config.protocol(),config.context(),config.timeout(),config.maxOutput()));
        return new TextModelClient.ModelBinding(
                new TextModelClient.ServiceConnection(provider, URI.create(baseUrl), credential,
                        settings.path("connectionVersion").asLong()),
                config.model(), protocol, config.context(),
                Duration.ofSeconds(config.timeout()), config.maxOutput(), version);
    }

    record RoleBinding(String model, String protocol, int context, int timeout, int maxOutput) { }

    static RoleBinding roleBinding(JsonNode settings, Stage role, String provider) {
        boolean research = role == Stage.RESEARCH;
        JsonNode override = settings.path("bindings").path(role.name());
        String model = settings.path("model").asText();
        String protocol = settings.path("protocol").asText();
        int context = settings.path("contextTokens").asInt();
        int timeout = settings.path("timeoutSeconds").asInt(120);
        int maxOutput = settings.path("maxOutputTokens").asInt(defaultOutput(role));
        if (research) {
            model = optional(settings, "researchModel", model);
            String researchProtocol = optional(settings, "researchProtocol", protocol);
            if (!AssistantConnection.supportsProtocol(provider, "RESPONSES")
                    ? AssistantConnection.supportsProtocol(provider, researchProtocol)
                    : !settings.path("researchModel").asText("").isBlank()) protocol = researchProtocol;
            if (settings.path("researchContextTokens").asInt() > 0) context = settings.path("researchContextTokens").asInt();
            timeout = settings.path("researchTimeoutSeconds").asInt(600);
            maxOutput = settings.path("researchMaxOutputTokens").asInt(defaultOutput(role));
        }
        if (override.isObject()) {
            model = optional(override, "model", model);
            protocol = optional(override, "protocol", protocol);
            if (override.path("contextTokens").asInt() > 0) context = override.path("contextTokens").asInt();
            if (override.path("timeoutSeconds").asInt() > 0) timeout = override.path("timeoutSeconds").asInt();
            if (override.path("maxOutputTokens").asInt() > 0) maxOutput = override.path("maxOutputTokens").asInt();
        }
        if (!AssistantConnection.supportsProtocol(provider, protocol) && !override.hasNonNull("protocol")) {
            protocol = AssistantConnection.defaultProtocol(provider);
        }
        return new RoleBinding(model, protocol, context, timeout, maxOutput);
    }

    private ObjectNode result(JsonNode value, TextModelClient.ModelResult raw) {
        ObjectNode response = mapper.createObjectNode().set("result", value);
        if (raw != null) {
            response.set("model", raw.modelSnapshot());
            response.set("usage", mapper.valueToTree(raw.usage()));
            if (raw.requestId() != null) response.put("requestId", raw.requestId());
        }
        return response;
    }

    private <T> List<T> list(JsonNode node, Class<T> type) {
        if (!node.isArray()) throw new IllegalArgumentException("expected array input for " + type.getSimpleName());
        return mapper.convertValue(node, mapper.getTypeFactory().constructCollectionType(List.class, type));
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private ObjectNode verify(ObjectNode settings) {
        TextModelClient.ModelBinding main = mainBinding(settings);
        Map<Stage,TextModelClient.ModelBinding> roles = new LinkedHashMap<>();
        for(Stage role:VERIFY_ROLES) roles.put(role,binding(settings,role));
        TextModelClient.ModelBinding research = roles.get(Stage.RESEARCH);
        ObjectNode output = mapper.createObjectNode();
        Map<BindingKey,java.util.List<Stage>> grouped = new LinkedHashMap<>();
        roles.forEach((role,binding)->grouped.computeIfAbsent(BindingKey.of(binding),ignored->new java.util.ArrayList<>()).add(role));
        BindingKey mainKey=BindingKey.of(main);
        grouped.computeIfAbsent(mainKey,ignored->new java.util.ArrayList<>());
        Map<BindingKey,JsonNode> results=new LinkedHashMap<>();
        for(var entry:grouped.entrySet()) {
            boolean requireSearch=entry.getValue().contains(Stage.RESEARCH);
            TextModelClient.ModelBinding representative=entry.getKey().equals(mainKey)?main:roles.get(entry.getValue().get(0));
            results.put(entry.getKey(),mapper.valueToTree(verifier.verify(representative,requireSearch)));
        }
        output.set("main",results.get(mainKey));
        output.set("research",results.get(BindingKey.of(research)));
        ObjectNode roleResults=output.putObject("roles");
        roles.forEach((role,binding)->{
            ObjectNode status=((ObjectNode)results.get(BindingKey.of(binding))).deepCopy();
            status.put("bindingVersion",binding.version());
            roleResults.set(role.name(),status);
        });
        output.put("uniqueCombinations",results.size());
        output.put("sameCombination",results.size()==1);
        boolean rolesReady=roles.keySet().stream().allMatch(role->roleResults.path(role.name()).path("ready").asBoolean());
        output.put("rolesReady",rolesReady);
        output.put("ready",output.path("main").path("ready").asBoolean() && rolesReady);
        return result(output, null);
    }

    private TextModelClient.ModelBinding mainBinding(ObjectNode settings) {
        return binding(mainBindingSettings(settings),Stage.CLASSIFY);
    }

    static ObjectNode mainBindingSettings(ObjectNode settings) {
        return settings.deepCopy().without("bindings");
    }

    private static int defaultOutput(Stage role) {
        return switch(role) {
            case CLASSIFY,SCORE -> 2000;
            case STYLE -> 4000;
            case RESEARCH,RESEARCH_SYNTHESIS,DRAFT,OPTIMIZE,OPTIMIZE_REVISION -> 6000;
            case VERIFY -> 2000;
        };
    }

    private ContentAssistantModelTasks.TopicText researchTopic(ObjectNode input) {
        JsonNode topic = input.path("topic");
        java.util.ArrayList<String> texts = new java.util.ArrayList<>();

        JsonNode selectedMaterial = null;
        String selected = input.path("selectedMaterialId").asText("");
        for (JsonNode material : input.path("materials")) {
            if (selected.equals(material.path("id").asText())) selectedMaterial = material;
            StringBuilder materialText = new StringBuilder("Material ").append(material.path("id").asText("unknown"));
            if (!material.path("username").asText("").isBlank()) materialText.append(" by @").append(material.path("username").asText());
            if (!material.path("sourceUrl").asText("").isBlank()) materialText.append(" source ").append(material.path("sourceUrl").asText());
            for (String statusField : List.of("textStatus", "authorTextStatus", "quotedTextStatus", "articleTextStatus", "articleRole")) {
                String status = material.path(statusField).asText();
                if (!status.isBlank()) materialText.append(" [").append(statusField).append(": ").append(status).append(']');
            }
            for (String field : List.of("text", "quotedText", "articleText")) {
                String value = material.path(field).asText("");
                if (!value.isBlank()) materialText.append("\n").append(field).append(": ").append(value);
            }
            if (materialText.indexOf("\n") >= 0) {
                if (selected.equals(material.path("id").asText())) texts.add(0, materialText.toString());
                else texts.add(materialText.toString());
            }
        }
        if (selectedMaterial == null) selectedMaterial = input.path("materials").path(0);
        Map<String, Long> metrics = new LinkedHashMap<>();
        for (String name : List.of("views", "likes", "replies", "reposts", "bookmarks")) {
            JsonNode metric = selectedMaterial.path(name);
            if (metric.isIntegralNumber() && metric.canConvertToLong() && metric.asLong() >= 0) metrics.put(name, metric.asLong());
        }
        String instruction = input.path("instruction").asText("");
        if (input.path("questions").isArray() && !input.path("questions").isEmpty())
            instruction += "\nQuestions requiring new evidence: " + input.path("questions");
        String assessment = input.path("researchVerified").asBoolean()
                ? "\nVerified research claims: " + input.path("research").path("claims") : "";
        JsonNode publishedAt = selectedMaterial.path("publishedAt");
        String published = publishedAt.isIntegralNumber() && publishedAt.asLong() > 0
                ? Instant.ofEpochMilli(publishedAt.asLong()).toString() : publishedAt.asText();
        return new ContentAssistantModelTasks.TopicText(topic.path("id").asText(), topic.path("title").asText()+" "+topic.path("summary").asText() + (selected.isBlank() ? "" : "\nSelected material: " + selected)
                + (instruction.isBlank() ? "" : "\nResearch instruction: " + instruction) + assessment, List.copyOf(texts), published, metrics);
    }

    private static String optional(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("");
        return value.isBlank() ? fallback : value;
    }

    private static void checkStage(long deadline) {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("research stage interrupted");
        if (System.nanoTime() > deadline) throw new IllegalStateException("research stage exceeded its 10 minute deadline");
    }

    public enum FailureKind { MISSING_CONFIG, DEFINITE_FAILURE, RESULT_UNKNOWN }
    public static final class AssistantIntelligenceException extends RuntimeException {
        private final FailureKind kind;
        private final boolean retryable;
        private final String requestId;
        public AssistantIntelligenceException(FailureKind kind, boolean retryable, String requestId, String message, Throwable cause) {
            super(message, cause); this.kind = kind; this.retryable = retryable; this.requestId = requestId;
        }
        public FailureKind kind() { return kind; }
        public boolean retryable() { return retryable; }
        public String requestId() { return requestId; }
    }

    public enum Stage { CLASSIFY, SCORE, RESEARCH, RESEARCH_SYNTHESIS, DRAFT, OPTIMIZE, OPTIMIZE_REVISION, STYLE, VERIFY }
    private record BindingKey(String model,TextModelClient.Protocol protocol,int context,long timeoutMillis,int maxOutput) {
        static BindingKey of(TextModelClient.ModelBinding binding) {
            return new BindingKey(binding.modelId(),binding.protocol(),binding.contextTokens(),binding.timeout().toMillis(),binding.maxOutputTokens());
        }
    }
}
