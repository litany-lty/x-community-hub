package com.xops.hub.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.config.XOpsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Public read adapter. It never receives or sends a paid API key. */
public final class FxTwitterClient {
    private static final Logger log = LoggerFactory.getLogger(FxTwitterClient.class);
    // Spend at most one concurrent wave on missing parents, leaving time for the verified fallback.
    private static final int MAX_THREAD_LOOKUPS_PER_PAGE = 4;
    private static final long THREAD_LOOKUP_BUDGET_MS = 1500;
    private static final String CURSOR_PREFIX = "fx:";
    private static final Pattern TWEET_ID = Pattern.compile("[0-9]{2,20}");
    private static final Pattern USER_ID = Pattern.compile("[0-9]{1,20}");
    private static final Pattern HANDLE = Pattern.compile("[A-Za-z0-9_]{1,15}");
    private static final String USER_AGENT = "x-community-hub/1.0 (+https://github.com/litany-lty/x-community-hub)";
    private final XOpsProperties properties;
    private final ObjectMapper mapper;
    private final SharedApiExecutor executor;
    private final HttpClient httpClient;

    public FxTwitterClient(XOpsProperties properties, ObjectMapper mapper, SharedApiExecutor executor) {
        this.properties = properties;
        this.mapper = mapper;
        this.executor = executor;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public boolean supports(String paidUrl) {
        if (!properties.isFxtwitterEnabled()) return false;
        try {
            Route route = route(paidUrl);
            return switch (route.path()) {
                case "/twitter/user/info" -> validProfileSelector(route.query().get("userName"));
                case "/twitter/tweet/advanced_search" -> "Latest".equalsIgnoreCase(route.query().get("queryType"))
                        && route.query().getOrDefault("query", "").length() > 0
                        && route.query().get("query").length() <= 512
                        && (route.query().getOrDefault("cursor", "").isEmpty()
                            || route.query().get("cursor").startsWith(CURSOR_PREFIX));
                case "/twitter/tweets" -> !tweetIds(route.query().get("tweet_ids")).isEmpty();
                default -> false;
            };
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public CompletableFuture<JsonNode> fetchAsync(String paidUrl, Instant deadline, AtomicBoolean requestStarted) {
        return fetchAsync(paidUrl, deadline, requestStarted, SearchReplyRoots.empty());
    }

    CompletableFuture<JsonNode> fetchAsync(String paidUrl, Instant deadline, AtomicBoolean requestStarted,
                                         SearchReplyRoots searchRoots) {
        if (!supports(paidUrl)) return CompletableFuture.failedFuture(invalid("不支持此公开数据请求"));
        Route route = route(paidUrl);
        return switch (route.path()) {
            case "/twitter/user/info" -> {
                String selector = route.query().get("userName");
                yield mapCancellable(request("/profile/" + encode(selector), deadline, requestStarted, null), fetched -> {
                    ObjectNode user = user(fetched.body().path("user"));
                    boolean matches = selector.regionMatches(true, 0, "id:", 0, 3)
                            ? user.path("id").asText().equals(selector.substring(3))
                            : user.path("userName").asText().equalsIgnoreCase(selector);
                    if (!matches) throw invalid("公开账号身份与请求不一致");
                    ObjectNode out = envelope();
                    out.set("data", user);
                    return out;
                });
            }
            case "/twitter/tweet/advanced_search" -> {
                String query = route.query().get("query");
                String cursor = route.query().getOrDefault("cursor", "");
                if (cursor.equals(CURSOR_PREFIX)) yield CompletableFuture.failedFuture(invalid("公开数据分页游标无效"));
                String path = "/search?q=" + encode(query) + "&feed=latest&count=30"
                        + (cursor.isEmpty() ? "" : "&cursor=" + encode(cursor.substring(CURSOR_PREFIX.length())));
                yield TwitterApiIoProvider.handleComposeCancellable(request(path, deadline, requestStarted, null), (fetched, error) -> {
                    if (error != null) return CompletableFuture.failedFuture(error);
                    return search(fetched, query, cursor, searchRoots, deadline);
                });
            }
            case "/twitter/tweets" -> metrics(tweetIds(route.query().get("tweet_ids")), deadline, requestStarted);
            default -> CompletableFuture.failedFuture(invalid("不支持此公开数据请求"));
        };
    }

    private CompletableFuture<Fetched> request(String path, Instant deadline, AtomicBoolean requestStarted, Runnable onStarted) {
        long remaining = deadline == null ? 0 : Duration.between(Instant.now(), deadline).toMillis();
        if (remaining <= 0) return CompletableFuture.failedFuture(invalid("公开数据请求已超过本轮时限"));
        CompletableFuture<Fetched> future = executor.submitAsync(() -> {
            long timeLeft = Duration.between(Instant.now(), deadline).toMillis();
            if (timeLeft <= 0 || Thread.currentThread().isInterrupted()) throw invalid("公开数据请求已超过本轮时限");
            String base = properties.getFxtwitterBaseUrl().replaceAll("/+$", "");
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofMillis(Math.min(10_000, timeLeft)))
                    .header("Accept", "application/json").header("User-Agent", USER_AGENT).GET().build();
            if (requestStarted != null) requestStarted.set(true);
            if (onStarted != null) onStarted.run();
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                long observedAt = Instant.now().getEpochSecond();
                if (response.statusCode() != 200) throw invalid("公开数据服务暂不可用（HTTP " + response.statusCode() + "）");
                JsonNode json = mapper.readTree(response.body());
                if (json == null || !json.isObject() || !json.path("code").isIntegralNumber()
                        || json.path("code").asInt() != 200) throw invalid("公开数据服务返回异常状态");
                log.debug("FxTwitter {} read succeeded", path.startsWith("/profile/") ? "profile"
                        : path.startsWith("/status/") ? "status" : path.startsWith("/thread/") ? "thread" : "search");
                return new Fetched(json, observedAt);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new XDataProviderException(503, "公开数据读取已中断");
            } catch (IOException ex) {
                throw new XDataProviderException(502, "公开数据服务响应无法读取");
            }
        });
        // Completing the executor future also cancels/removes its queued or running task.
        return future.orTimeout(remaining, TimeUnit.MILLISECONDS);
    }

    private CompletableFuture<JsonNode> search(Fetched fetched, String query, String requestedCursor,
                                               SearchReplyRoots roots, Instant deadline) {
        JsonNode rows = fetched.body().get("results");
        JsonNode cursors = fetched.body().get("cursor");
        if (rows == null || !rows.isArray() || cursors == null || !cursors.isObject()
                || !cursors.has("bottom") || (!cursors.path("bottom").isNull() && !cursors.path("bottom").isTextual())) {
            throw invalid("公开搜索分页结构不完整");
        }
        String bottom = cursors.path("bottom").asText("");
        if ((!bottom.isEmpty() && bottom.isBlank()) || (rows.isEmpty() && !bottom.isEmpty())
                || (!bottom.isEmpty() && (CURSOR_PREFIX + bottom).equals(requestedCursor))) {
            throw invalid("公开搜索分页没有取得进展");
        }
        long since = timeBound(query, "since_time", Long.MIN_VALUE);
        long until = timeBound(query, "until_time", Long.MAX_VALUE);
        if (since >= until) throw invalid("公开搜索时间范围无效");
        Set<String> from = terms(query, "(?<![\\w@:-])from:([A-Za-z0-9_]{1,15})(?![\\w])");
        Set<String> excluded = terms(query, "(?<![\\w])-from:([A-Za-z0-9_]{1,15})(?![\\w])");
        Set<String> to = terms(query, "(?<![\\w@:-])to:([A-Za-z0-9_]{1,15})(?![\\w])");
        Set<String> mentions = terms(query, "(?<![\\w])@([A-Za-z0-9_]{1,15})(?![\\w])");
        Set<String> quotedIds = terms(query, "(?<![\\w:-])quoted_user_id:([0-9]{1,20})(?![0-9])");
        ObjectNode out = envelope();
        ArrayNode tweets = out.putArray("tweets");
        SearchReplyRoots.Page page = roots.new Page();
        boolean hasReplies = false;
        long previous = Long.MAX_VALUE;
        for (JsonNode raw : rows) {
            ObjectNode tweet = tweet(raw, null, fetched.observedAt());
            if (!raw.has("replying_to")) throw SearchReplyRoots.invalid();
            hasReplies |= tweet.path("isReply").asBoolean();
            long timestamp = raw.path("created_timestamp").asLong();
            String author = tweet.path("author").path("userName").asText().toLowerCase(Locale.ROOT);
            if (timestamp < since || timestamp >= until || timestamp > previous
                    || (!from.isEmpty() && !from.contains(author)) || excluded.contains(author)) {
                throw invalid("公开搜索的作者、时间或排序与查询不一致");
            }
            if ((!to.isEmpty() || !mentions.isEmpty() || !quotedIds.isEmpty())
                    && !related(raw, to, mentions, quotedIds)) throw invalid("公开搜索返回了无法确认相关的互动");
            previous = timestamp;
            page.add(SearchReplyRoots.Post.fromNormalized(tweet, timestamp));
            tweets.add(tweet);
        }
        out.put("has_next_page", !bottom.isEmpty());
        out.put("next_cursor", bottom.isEmpty() ? "" : CURSOR_PREFIX + bottom);
        if (!hasReplies) return CompletableFuture.completedFuture(out);
        Set<String> missing = page.fill(tweets);
        if (missing.isEmpty()) return CompletableFuture.completedFuture(out);
        Set<String> fetchParents = new LinkedHashSet<>();
        for (String id : missing) {
            List<SearchReplyRoots.Post> cached = roots.thread(id);
            if (cached == null) fetchParents.add(id);
            else cached.forEach(page::add);
        }
        missing = page.fill(tweets);
        if (missing.isEmpty()) return CompletableFuture.completedFuture(out);
        if (!fetchParents.containsAll(missing) || missing.size() > MAX_THREAD_LOOKUPS_PER_PAGE) throw SearchReplyRoots.invalid();

        Instant now = Instant.now();
        long availableMs = Duration.between(now, deadline).toMillis();
        if (availableMs <= 0) throw SearchReplyRoots.invalid();
        Instant threadDeadline = now.plusMillis(Math.min(THREAD_LOOKUP_BUDGET_MS, availableMs));
        List<CompletableFuture<List<SearchReplyRoots.Post>>> lookups = new ArrayList<>();
        for (String id : missing) {
            var lookup = mapCancellable(request("/thread/" + id, threadDeadline, null, null),
                    fetchedThread -> threadAncestors(id, fetchedThread));
            lookup.whenComplete((chain, error) -> {
                if (!lookup.isCancelled()) roots.rememberThread(id, error == null ? chain : List.of());
            });
            lookups.add(lookup);
        }
        var wave = CompletableFuture.allOf(lookups.toArray(CompletableFuture[]::new));
        wave.whenComplete((v, error) -> {
            if (error != null) lookups.forEach(f -> f.cancel(true));
        });
        for (var lookup : lookups) {
            lookup.whenComplete((chain, error) -> {
                if (error != null) wave.completeExceptionally(error);
            });
        }
        return mapCancellable(wave, unused -> {
            for (var lookup : lookups) lookup.join().forEach(page::add);
            if (!page.fill(tweets).isEmpty()) throw SearchReplyRoots.invalid();
            return out;
        });
    }

    private List<SearchReplyRoots.Post> threadAncestors(String expectedId, Fetched fetched) {
        JsonNode focal = fetched.body().path("status");
        if (!expectedId.equals(focal.path("id").asText())) throw SearchReplyRoots.invalid();
        Map<String, JsonNode> candidates = new LinkedHashMap<>();
        for (JsonNode node : fetched.body().path("thread")) {
            if (!"status".equals(node.path("type").asText())) continue;
            String id = node.path("id").asText();
            if (candidates.putIfAbsent(id, node) != null) throw SearchReplyRoots.invalid();
        }
        JsonNode repeatedFocal = candidates.get(expectedId);
        if (repeatedFocal != null && (!repeatedFocal.path("replying_to").equals(focal.path("replying_to"))
                || !repeatedFocal.path("author").path("id").equals(focal.path("author").path("id"))
                || !repeatedFocal.path("created_timestamp").equals(focal.path("created_timestamp")))) {
            throw SearchReplyRoots.invalid();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<SearchReplyRoots.Post> chain = new ArrayList<>();
        JsonNode current = focal;
        while (current != null) {
            if (!current.has("replying_to") || !seen.add(current.path("id").asText())) throw SearchReplyRoots.invalid();
            ObjectNode normalized = tweet(current, null, fetched.observedAt());
            var post = SearchReplyRoots.Post.fromNormalized(normalized, current.path("created_timestamp").asLong());
            chain.add(post);
            current = candidates.get(post.parent());
        }
        return chain;
    }

    private boolean related(JsonNode raw, Set<String> to, Set<String> mentions, Set<String> quotedIds) {
        if (to.contains(raw.path("replying_to").path("screen_name").asText("").toLowerCase(Locale.ROOT))) return true;
        if ("status".equals(raw.path("quote").path("type").asText())) {
            JsonNode quoteAuthor = raw.path("quote").path("author");
            String quoteHandle = quoteAuthor.path("screen_name").asText("").toLowerCase(Locale.ROOT);
            if (quotedIds.contains(quoteAuthor.path("id").asText("")) || to.contains(quoteHandle) || mentions.contains(quoteHandle)) return true;
        }
        for (JsonNode facet : raw.path("raw_text").path("facets")) {
            if (!"mention".equals(facet.path("type").asText())) continue;
            if (mentions.contains(mentionHandle(raw, facet).toLowerCase(Locale.ROOT))
                    || quotedIds.contains(facet.path("id").asText(""))) return true;
        }
        return false;
    }

    private CompletableFuture<JsonNode> metrics(List<String> ids, Instant deadline, AtomicBoolean requestStarted) {
        Batch batch = new Batch(ids, deadline, requestStarted);
        batch.result.whenComplete((value, error) -> {
            if (batch.result.isCancelled()) batch.active.forEach(f -> f.cancel(true));
        });
        metricGroup(batch, 0);
        return batch.result;
    }

    private void metricGroup(Batch batch, int offset) {
        if (batch.result.isDone()) return;
        if (offset >= batch.ids.size() || batch.deadline == null || !Instant.now().isBefore(batch.deadline)) {
            finishMetrics(batch);
            return;
        }
        int end = Math.min(offset + 4, batch.ids.size());
        List<CompletableFuture<?>> group = new ArrayList<>();
        for (int i = offset; i < end; i++) {
            String id = batch.ids.get(i);
            CompletableFuture<Fetched> request = request("/status/" + id, batch.deadline, batch.requestStarted,
                    () -> batch.attempted.add(id));
            batch.active.add(request);
            if (batch.result.isCancelled()) request.cancel(true);
            group.add(request.handle((fetched, error) -> {
                batch.active.remove(request);
                if (error == null) {
                    try {
                        batch.tweets.put(id, tweet(fetched.body().path("status"), id, fetched.observedAt()));
                    } catch (XDataProviderException ignored) {
                        // An unverifiable status is incomplete, never evidence of deletion.
                    }
                }
                return null;
            }));
        }
        CompletableFuture.allOf(group.toArray(CompletableFuture[]::new))
                .whenComplete((unused, error) -> metricGroup(batch, end));
    }

    private void finishMetrics(Batch batch) {
        ObjectNode out = envelope();
        ArrayNode tweets = out.putArray("tweets");
        ArrayNode attempted = out.putArray("__attempted_ids");
        ArrayNode incomplete = out.putArray("__incomplete_ids");
        for (String id : batch.ids) {
            if (batch.attempted.contains(id)) attempted.add(id);
            ObjectNode tweet = batch.tweets.get(id);
            if (tweet == null) incomplete.add(id);
            else tweets.add(tweet);
        }
        batch.result.complete(out);
    }

    private ObjectNode tweet(JsonNode raw, String expectedId, long observedAt) {
        if (!raw.isObject() || !"status".equals(raw.path("type").asText())
                || !validId(raw.get("id"), TWEET_ID) || !raw.path("text").isTextual()
                || raw.hasNonNull("reposted_by")) throw invalid("公开推文结构或转发身份无法验证");
        String id = raw.path("id").asText();
        if (expectedId != null && !expectedId.equals(id)) throw invalid("公开推文 ID 与请求不一致");
        JsonNode stamp = raw.path("created_timestamp");
        if (!stamp.isIntegralNumber() || !stamp.canConvertToLong() || stamp.asLong() < 0) throw invalid("公开推文时间无效");
        Instant created;
        try {
            created = Instant.ofEpochSecond(stamp.asLong());
        } catch (RuntimeException ex) {
            throw invalid("公开推文时间无效");
        }
        if (raw.hasNonNull("created_at")) {
            Instant parsed = TwitterApiIoProvider.parseFlexibleTimestamp(raw.path("created_at").asText(""));
            if (parsed == null || !parsed.equals(created)) throw invalid("公开推文时间字段不一致");
        }
        ObjectNode out = envelope();
        // Fx's display text expands links and removes media shortlinks. Keep authored text,
        // including a valid empty string, to preserve the existing content/length semantics.
        JsonNode authoredText = raw.path("raw_text").path("text");
        out.put("id", id).put("text", authoredText.isTextual() ? authoredText.asText() : raw.path("text").asText())
                .put("createdAt", created.toString());
        out.put("url", "https://x.com/" + raw.path("author").path("screen_name").asText("") + "/status/" + id);
        out.put("__observed_at", observedAt);
        out.set("author", user(raw.path("author")));
        for (String[] metric : new String[][]{{"likes", "likeCount"}, {"replies", "replyCount"},
                {"reposts", "retweetCount"}, {"quotes", "quoteCount"}, {"views", "viewCount"}, {"bookmarks", "bookmarkCount"}}) {
            copyCount(raw, metric[0], out, metric[1], false, "views".equals(metric[0]) ? Long.MAX_VALUE : Integer.MAX_VALUE);
        }
        if (raw.hasNonNull("raw_text")) out.set("raw_text", raw.get("raw_text").deepCopy());
        if (raw.has("is_note_tweet")) out.set("is_note_tweet", raw.get("is_note_tweet").deepCopy());
        if (raw.hasNonNull("article")) out.set("article", raw.get("article").deepCopy());
        JsonNode parent = raw.path("replying_to");
        if (!parent.isNull() && !parent.isMissingNode()) {
            if (!validId(parent.get("status"), TWEET_ID) || !HANDLE.matcher(parent.path("screen_name").asText("")).matches()) {
                throw invalid("公开推文的回复关系无效");
            }
            out.put("isReply", true).put("inReplyToId", parent.path("status").asText())
                    .put("inReplyToUsername", parent.path("screen_name").asText());
        } else {
            out.put("conversationId", id);
        }
        JsonNode quote = raw.path("quote");
        if (!quote.isNull() && !quote.isMissingNode()) {
            ObjectNode quoted = mapper.createObjectNode();
            if ("tombstone".equals(quote.path("type").asText())) {
                quoted.put("unavailable", true);
                if (validId(quote.get("id"), TWEET_ID)) quoted.put("id", quote.path("id").asText());
            } else {
                if (!"status".equals(quote.path("type").asText()) || !validId(quote.get("id"), TWEET_ID)) {
                    throw invalid("公开推文的引用关系无效");
                }
                quoted.put("id", quote.path("id").asText()).put("text", quote.path("text").asText(""));
                quoted.set("author", user(quote.path("author")));
            }
            out.set("quoted_tweet", quoted);
        }
        ObjectNode entities = out.putObject("entities");
        ArrayNode mentions = entities.putArray("user_mentions");
        for (JsonNode facet : raw.path("raw_text").path("facets")) {
            if (!"mention".equals(facet.path("type").asText())) continue;
            String handle = mentionHandle(raw, facet);
            if (!HANDLE.matcher(handle).matches()) continue;
            ObjectNode mention = mentions.addObject().put("screen_name", handle);
            if (validId(facet.get("id"), USER_ID)) mention.put("id", facet.path("id").asText());
            if (facet.path("indices").isArray()) mention.set("indices", facet.get("indices").deepCopy());
        }
        ArrayNode media = out.putObject("extendedEntities").putArray("media");
        if (raw.path("media").path("all").isArray()) {
            raw.path("media").path("all").forEach(item -> media.add(item.deepCopy()));
        } else {
            for (String kind : List.of("photos", "videos")) {
                if (raw.path("media").path(kind).isArray()) raw.path("media").path(kind).forEach(item -> media.add(item.deepCopy()));
            }
        }
        return out;
    }

    record Badge(String id, String handle, boolean blue) {}

    /** Read badge evidence only; this page does not establish membership or change pagination in the relationship graph. */
    CompletableFuture<List<Badge>> readBadgePage(String ownerId, String direction, Instant deadline) {
        String path = "/profile/" + encode("id:" + ownerId) + "/" + direction + "?count=100";
        return mapCancellable(request(path, deadline, null, null), fetched -> {
            List<Badge> badges = new ArrayList<>();
            JsonNode rows = fetched.body().path("results");
            if (!rows.isArray()) throw invalid("公开名单缺少资料");
            for (JsonNode raw : rows) {
                String handle = raw.path("screen_name").asText("");
                if (!"profile".equals(raw.path("type").asText()) || !validId(raw.get("id"), USER_ID)
                        || !HANDLE.matcher(handle).matches()) continue;
                Boolean blue = individualBlue(raw);
                if (blue != null) badges.add(new Badge(raw.get("id").textValue(), handle, blue));
            }
            return badges;
        });
    }

    private static Boolean individualBlue(JsonNode raw) {
        JsonNode verification = raw.path("verification");
        JsonNode verified = verification.path("verified");
        if (!verified.isBoolean()) return null;
        if (!verified.booleanValue()) return false;
        return "individual".equals(verification.path("type").asText()) ? Boolean.TRUE : null;
    }

    private ObjectNode user(JsonNode raw) {
        if (!raw.isObject() || !validId(raw.get("id"), USER_ID)
                || !HANDLE.matcher(raw.path("screen_name").asText("")).matches()) throw invalid("公开账号身份字段无效");
        ObjectNode out = envelope();
        out.put("id", raw.path("id").asText()).put("userName", raw.path("screen_name").asText());
        for (String[] field : new String[][]{{"name", "name"}, {"description", "description"}, {"avatar_url", "profilePicture"},
                {"banner_url", "coverPicture"}, {"joined", "createdAt"}}) {
            if (raw.path(field[0]).isTextual()) out.set(field[1], raw.get(field[0]).deepCopy());
        }
        copyCount(raw, "followers", out, "followers", true, Integer.MAX_VALUE);
        copyCount(raw, "following", out, "following", true, Integer.MAX_VALUE);
        copyCount(raw, "statuses", out, "statusesCount", true, Integer.MAX_VALUE);
        Boolean blue = individualBlue(raw);
        if (blue != null) out.put("isBlueVerified", blue.booleanValue());
        return out;
    }

    private static String mentionHandle(JsonNode raw, JsonNode facet) {
        String handle = facet.path("original").asText("");
        if (handle.isBlank()) {
            JsonNode indices = facet.path("indices");
            String text = raw.path("raw_text").path("text").asText("");
            if (indices.isArray() && indices.size() == 2 && indices.get(0).isIntegralNumber() && indices.get(1).isIntegralNumber()) {
                int start = indices.get(0).asInt(-1), end = indices.get(1).asInt(-1);
                if (start >= 0 && end >= start && end <= text.length()) handle = text.substring(start, end);
            }
        }
        return handle.startsWith("@") ? handle.substring(1) : handle;
    }

    private static void copyCount(JsonNode raw, String source, ObjectNode out, String target, boolean required, long max) {
        JsonNode value = raw.get(source);
        if (value == null || value.isNull()) {
            if (required) throw invalid("公开账号计数缺失");
            return;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0 || value.asLong() > max) {
            throw invalid("公开数据计数格式无效");
        }
        out.put(target, value.asLong());
    }

    private ObjectNode envelope() { return mapper.createObjectNode().put("_source", "fxtwitter"); }

    private static boolean validId(JsonNode value, Pattern pattern) {
        return value != null && value.isTextual() && pattern.matcher(value.asText()).matches();
    }

    private static boolean validProfileSelector(String value) {
        return value != null && (HANDLE.matcher(value).matches()
                || (value.regionMatches(true, 0, "id:", 0, 3) && USER_ID.matcher(value.substring(3)).matches()));
    }

    private static List<String> tweetIds(String value) {
        if (value == null || value.isBlank()) throw invalid("推文 ID 缺失");
        Set<String> ids = new LinkedHashSet<>();
        for (String part : value.split(",", -1)) {
            if (!TWEET_ID.matcher(part).matches()) throw invalid("推文 ID 无效");
            ids.add(part);
        }
        return List.copyOf(ids);
    }

    private static Route route(String url) {
        URI uri = URI.create(url);
        Map<String, String> values = new LinkedHashMap<>();
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                String[] parts = pair.split("=", 2);
                String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                String value = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
                if (values.putIfAbsent(name, value) != null) throw invalid("请求包含重复参数");
            }
        }
        return new Route(uri.getPath(), values);
    }

    private static Set<String> terms(String query, String regex) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(query);
        while (matcher.find()) values.add(matcher.group(1).toLowerCase(Locale.ROOT));
        return values;
    }

    private static long timeBound(String query, String key, long defaultValue) {
        Set<String> values = terms(query, "(?<![\\w:-])" + key + ":([0-9]+)(?![0-9])");
        if (values.size() > 1) throw invalid("公开搜索时间参数冲突");
        if (values.isEmpty()) return defaultValue;
        try { return Long.parseLong(values.iterator().next()); }
        catch (NumberFormatException ex) { throw invalid("公开搜索时间参数无效"); }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static XDataProviderException invalid(String message) { return new XDataProviderException(502, message); }

    private static <T, R> CompletableFuture<R> mapCancellable(CompletableFuture<T> source, Function<T, R> mapping) {
        CompletableFuture<R> result = source.thenApply(mapping);
        result.whenComplete((value, error) -> { if (result.isCancelled()) source.cancel(true); });
        return result;
    }

    private record Route(String path, Map<String, String> query) { }
    private record Fetched(JsonNode body, long observedAt) { }

    private static final class Batch {
        final List<String> ids;
        final Instant deadline;
        final AtomicBoolean requestStarted;
        final Set<String> attempted = ConcurrentHashMap.newKeySet();
        final Map<String, ObjectNode> tweets = new ConcurrentHashMap<>();
        final Set<CompletableFuture<?>> active = ConcurrentHashMap.newKeySet();
        final CompletableFuture<JsonNode> result = new CompletableFuture<>();

        Batch(List<String> ids, Instant deadline, AtomicBoolean requestStarted) {
            this.ids = ids;
            this.deadline = deadline;
            this.requestStarted = requestStarted;
        }
    }
}
