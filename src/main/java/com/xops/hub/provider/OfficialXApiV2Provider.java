package com.xops.hub.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.RawSnapshotModels.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 对接 X 官方 API v2（api.x.com/2）：
 * 统一接入全局有界线程池 {@link SharedApiExecutor}，获取 userId 后四路并发查询时间线、提及与关注/粉丝图谱。
 */
@Component
public class OfficialXApiV2Provider {

    private final XOpsProperties properties;
    private final ObjectMapper objectMapper;
    private final SharedApiExecutor sharedExecutor;
    private final HttpClient httpClient;

    public OfficialXApiV2Provider(XOpsProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, new SharedApiExecutor(properties));
    }

    @Autowired
    public OfficialXApiV2Provider(XOpsProperties properties, ObjectMapper objectMapper, SharedApiExecutor sharedExecutor) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.sharedExecutor = sharedExecutor;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public RawAccountSnapshot fetchSnapshot(String username, String overrideBearerToken) {
        String rawToken = (overrideBearerToken != null && !overrideBearerToken.isBlank())
                ? overrideBearerToken.trim()
                : properties.getOfficialBearerToken();
        if (rawToken == null || rawToken.isBlank()) {
            throw new XDataProviderException(400, "未配置 X 官方 API v2 Bearer Token，请在 application.yml 或环境变量 X_OFFICIAL_BEARER_TOKEN 中配置");
        }
        String token = rawToken.regionMatches(true, 0, "Bearer ", 0, 7)
                ? rawToken.substring(7).trim()
                : rawToken;

        String baseUrl = trimTrailingSlash(properties.getOfficialBaseUrl());
        String encodedUser = URLEncoder.encode(username, StandardCharsets.UTF_8);

        // 1. 获取用户基本信息（经由共用线程池执行）
        JsonNode userResp = getJson(
                baseUrl + "/users/by/username/" + encodedUser
                        + "?user.fields=created_at,description,profile_image_url,profile_banner_url,public_metrics,verified",
                token
        );
        JsonNode userData = userResp.path("data");
        if (userData.isMissingNode() || userData.path("id").asText("").isBlank()) {
            throw new XDataProviderException(404, "在 X 官方接口中未找到账号 @" + username);
        }
        XUserSummary profile = parseV2User(userData);
        String userId = profile.id();

        // 2-4. 在共用线程池上四路并发拉取动态、提及与关注/粉丝列表
        CompletableFuture<JsonNode> tweetsF = getJsonAsync(
                baseUrl + "/users/" + userId + "/tweets?max_results=100"
                        + "&post.fields=created_at,conversation_id,public_metrics,entities,attachments"
                        + "&expansions=author_id,in_reply_to_user_id,referenced_posts"
                        + "&user.fields=username,name,profile_image_url,public_metrics,verified",
                token
        );
        CompletableFuture<JsonNode> mentionsF = getJsonAsync(
                baseUrl + "/users/" + userId + "/mentions?max_results=100"
                        + "&post.fields=created_at,conversation_id,public_metrics,entities,attachments"
                        + "&expansions=author_id,in_reply_to_user_id,referenced_posts"
                        + "&user.fields=username,name,profile_image_url,public_metrics,verified",
                token
        ).exceptionally(ex -> null);
        CompletableFuture<JsonNode> followingF = getJsonAsync(
                baseUrl + "/users/" + userId + "/following?max_results=200"
                        + "&user.fields=username,name,profile_image_url,public_metrics,verified,description",
                token
        ).exceptionally(ex -> null);
        CompletableFuture<JsonNode> followersF = getJsonAsync(
                baseUrl + "/users/" + userId + "/followers?max_results=200"
                        + "&user.fields=username,name,profile_image_url,public_metrics,verified,description",
                token
        ).exceptionally(ex -> null);

        JsonNode tweetsResp;
        try {
            CompletableFuture.allOf(tweetsF, mentionsF, followingF, followersF).join();
            tweetsResp = tweetsF.join();
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new XDataProviderException(502, "请求 X 官方 API v2 失败：" + ce.getMessage(), ce);
        }

        Map<String, XUserSummary> userById = indexIncludedUsers(tweetsResp.path("includes").path("users"));
        Map<String, String> tweetAuthors = indexTweetAuthors(tweetsResp);
        List<RawTweet> timeline = new ArrayList<>();
        JsonNode tweetArray = tweetsResp.path("data");
        if (tweetArray.isArray()) {
            for (JsonNode t : tweetArray) {
                timeline.add(parseV2Tweet(t, userId, profile.username(), userById, tweetAuthors));
            }
        }

        List<InboundInteraction> inbound = new ArrayList<>();
        JsonNode mentionsResp = mentionsF.join();
        if (mentionsResp != null) {
            Map<String, XUserSummary> mentionUsers = indexIncludedUsers(mentionsResp.path("includes").path("users"));
            Map<String, String> mentionTweetAuthors = indexTweetAuthors(mentionsResp);
            JsonNode mData = mentionsResp.path("data");
            if (mData.isArray()) {
                for (JsonNode m : mData) {
                    String authorId = m.path("author_id").asText("");
                    XUserSummary peer = mentionUsers.get(authorId);
                    if (peer == null || peer.username().equalsIgnoreCase(profile.username())) {
                        continue;
                    }
                    String replyUserId = replyTargetId(m, mentionTweetAuthors);
                    XUserSummary replyTarget = mentionUsers.get(replyUserId);
                    String replyToUsername = replyUserId.equals(userId)
                            ? profile.username()
                            : (replyTarget != null ? replyTarget.username() : "");
                    String inReplyToId = "";
                    InboundKind kind = InboundKind.MENTION_ME;
                    JsonNode refs = renamedField(m, "referenced_posts", "referenced_tweets");
                    if (refs.isArray()) {
                        for (JsonNode r : refs) {
                            String rType = r.path("type").asText("");
                            if ("replied_to".equals(rType)) {
                                inReplyToId = r.path("id").asText("");
                                kind = replyUserId.equals(userId) ? InboundKind.REPLY_TO_ME : InboundKind.MENTION_ME;
                                break;
                            } else if ("quoted".equals(rType) && userId.equals(mentionTweetAuthors.get(r.path("id").asText("")))) {
                                kind = InboundKind.QUOTE_ME;
                            }
                        }
                    }
                    inbound.add(new InboundInteraction(
                            m.path("id").asText(""),
                            peer,
                            TwitterApiIoProvider.parseFlexibleTimestamp(m.path("created_at").asText("")),
                            kind,
                            m.path("text").asText(""),
                            m.path("conversation_id").asText(""),
                            inReplyToId,
                            replyToUsername
                    ));
                }
            }
        }

        Map<String, RelationshipEntry> relMap = new LinkedHashMap<>();
        JsonNode followingResp = followingF.join();
        JsonNode followersResp = followersF.join();
        boolean followGraphRestricted = (followingResp == null || followersResp == null);
        boolean peerIdentityConflict = false;
        {
            if (followingResp != null && followingResp.path("data").isArray()) {
                for (JsonNode uNode : followingResp.path("data")) {
                    XUserSummary u = parseV2User(uNode);
                    relMap.put(u.username().toLowerCase(Locale.ROOT), new RelationshipEntry(u, true, false));
                }
            }
            if (followersResp != null && followersResp.path("data").isArray()) {
                for (JsonNode uNode : followersResp.path("data")) {
                    XUserSummary u = parseV2User(uNode);
                    String key = u.username().toLowerCase(Locale.ROOT);
                    RelationshipEntry prev = relMap.get(key);
                    if (prev != null) {
                        if (!prev.user().id().equals(u.id())) {
                            peerIdentityConflict = true;
                            continue;
                        }
                        relMap.put(key, new RelationshipEntry(prev.user(), true, true));
                    } else {
                        relMap.put(key, new RelationshipEntry(u, false, true));
                    }
                }
            }
        }

        Instant fetchedAt = Instant.now();
        boolean followersTruncated = peerIdentityConflict || incompletePage(followersResp)
                || (followersResp != null && followersResp.path("data").size() < profile.followersCount());
        boolean followingsTruncated = incompletePage(followingResp)
                || (followingResp != null && followingResp.path("data").size() < profile.followingCount());
        boolean timelineIncomplete = incompleteStreamPage(tweetsResp);
        boolean mentionsIncomplete = incompleteStreamPage(mentionsResp);
        int coveredDays = Math.min(coveredDays(tweetsResp, fetchedAt), coveredDays(mentionsResp, fetchedAt));
        boolean partial = timelineIncomplete || mentionsIncomplete || followersTruncated || followingsTruncated;
        String notice = String.format("X 官方 API v2 本次有界采集首屏：%d 条动态、%d 条提及、%d 个关系样本；连续时间覆盖 %d 天。",
                timeline.size(), inbound.size(), relMap.size(), coveredDays);
        if (partial) notice += "数据未完整覆盖；分页令牌、请求失败或名单不足已标记，未知关系不能认定为未关注/未回关。";
        if (mentionsResp == null) notice += "提及请求失败，互动数据降级。";
        if (followGraphRestricted) notice += "部分关注图谱请求失败，已保留另一侧可用样本。";
        return new RawAccountSnapshot(ProviderMode.X_OFFICIAL_V2, true, notice, fetchedAt, profile,
                timeline, inbound, new ArrayList<>(relMap.values()), followersTruncated, partial, coveredDays,
                oldestTime(tweetsResp), nextToken(tweetsResp), !timelineIncomplete,
                oldestTime(mentionsResp), nextToken(mentionsResp), !mentionsIncomplete, followingsTruncated);
    }

    private static String nextToken(JsonNode response) {
        return response == null ? "" : response.path("meta").path("next_token").asText("");
    }

    private static boolean incompletePage(JsonNode response) {
        if (response == null || !nextToken(response).isBlank() || response.path("errors").size() > 0) return true;
        // 官方空列表可省略 data，但必须由 result_count=0 明确证实；缺字段不是「已取完」。
        return !response.path("data").isArray()
                && !(response.path("meta").path("result_count").isIntegralNumber()
                && response.path("meta").path("result_count").asInt() == 0);
    }

    private static Instant oldestTime(JsonNode response) {
        Instant oldest = null;
        if (response != null && response.path("data").isArray()) {
            for (JsonNode item : response.path("data")) {
                try {
                    Instant at = Instant.parse(item.path("created_at").asText(""));
                    if (oldest == null || at.isBefore(oldest)) oldest = at;
                } catch (java.time.format.DateTimeParseException ignored) { }
            }
        }
        return oldest;
    }

    private static boolean incompleteStreamPage(JsonNode response) {
        if (incompletePage(response)) return true;
        for (JsonNode item : response.path("data")) {
            if (TwitterApiIoProvider.parseFlexibleTimestamp(item.path("created_at").asText("")) == null) return true;
        }
        return false;
    }

    private static int coveredDays(JsonNode response, Instant fetchedAt) {
        if (!incompleteStreamPage(response)) return 30;
        Instant tail = oldestTime(response);
        return tail == null ? 0 : (int) Math.max(0, Math.min(30, Duration.between(tail, fetchedAt).toDays()));
    }

    // 当前官方契约优先，字段缺失时兼容此前的 v2 返回名；不把两套别名累加。
    private static JsonNode renamedField(JsonNode parent, String currentName, String legacyName) {
        JsonNode current = parent.path(currentName);
        return current.isMissingNode() || current.isNull() ? parent.path(legacyName) : current;
    }

    private static String replyTargetId(JsonNode post, Map<String, String> postAuthors) {
        String explicit = post.path("in_reply_to_user_id").asText("");
        if (!explicit.isBlank()) return explicit;
        for (JsonNode ref : renamedField(post, "referenced_posts", "referenced_tweets")) {
            if ("replied_to".equals(ref.path("type").asText(""))) {
                return postAuthors.getOrDefault(ref.path("id").asText(""), "");
            }
        }
        return "";
    }

    private static Map<String, String> indexTweetAuthors(JsonNode response) {
        Map<String, String> authors = new HashMap<>();
        for (JsonNode tweet : renamedField(response.path("includes"), "posts", "tweets")) {
            authors.put(tweet.path("id").asText(""), tweet.path("author_id").asText(""));
        }
        return authors;
    }

    private CompletableFuture<JsonNode> getJsonAsync(String url, String bearerToken) {
        return sharedExecutor.submitAsync(() -> doGetJson(url, bearerToken));
    }

    private JsonNode getJson(String url, String bearerToken) {
        return sharedExecutor.execute(() -> doGetJson(url, bearerToken));
    }

    private JsonNode doGetJson(String url, String bearerToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 401) {
                throw new XDataProviderException(401, "X 官方 API Bearer Token 无效或已过期（HTTP 401）");
            }
            if (resp.statusCode() == 403) {
                throw new XDataProviderException(403, "当前 X 官方 API 订阅档位无权访问该接口（HTTP 403，Free 档不支持读取时间线）");
            }
            if (resp.statusCode() == 429) {
                throw new XDataProviderException(429, "X 官方 API 请求达到速率上限（HTTP 429），请稍后重试");
            }
            if (resp.statusCode() >= 400) {
                throw new XDataProviderException(502, "X 官方 API 返回异常状态码 HTTP " + resp.statusCode());
            }
            return objectMapper.readTree(resp.body());
        } catch (XDataProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new XDataProviderException(502, "请求 X 官方 API v2 失败：" + e.getMessage(), e);
        }
    }

    private Map<String, XUserSummary> indexIncludedUsers(JsonNode usersNode) {
        Map<String, XUserSummary> map = new HashMap<>();
        if (usersNode != null && usersNode.isArray()) {
            for (JsonNode u : usersNode) {
                XUserSummary summary = parseV2User(u);
                map.put(summary.id(), summary);
            }
        }
        return map;
    }

    private XUserSummary parseV2User(JsonNode u) {
        JsonNode pm = u.path("public_metrics");
        String avatar = u.path("profile_image_url").asText("").replace("_normal.", "_400x400.");
        return new XUserSummary(
                u.path("id").asText(""),
                u.path("username").asText(""),
                u.path("name").asText(u.path("username").asText("")),
                avatar,
                u.path("description").asText(""),
                u.path("verified").asBoolean(false),
                pm.path("followers_count").asInt(0),
                pm.path("following_count").asInt(0),
                renamedField(pm, "post_count", "tweet_count").asInt(0),
                u.path("created_at").asText(""),
                u.path("profile_banner_url").asText("")
        );
    }

    private RawTweet parseV2Tweet(
            JsonNode t,
            String selfId,
            String selfUsername,
            Map<String, XUserSummary> userById,
            Map<String, String> tweetAuthors
    ) {
        TweetKind kind = TweetKind.POST;
        String inReplyToId = "";
        JsonNode refs = renamedField(t, "referenced_posts", "referenced_tweets");
        if (refs.isArray()) {
            for (JsonNode ref : refs) {
                String type = ref.path("type").asText("");
                if ("retweeted".equals(type)) {
                    kind = TweetKind.RETWEET;
                    break;
                } else if ("replied_to".equals(type)) {
                    kind = TweetKind.REPLY;
                    inReplyToId = ref.path("id").asText("");
                } else if ("quoted".equals(type) && kind == TweetKind.POST) {
                    kind = TweetKind.QUOTE;
                }
            }
        }

        String replyUserId = replyTargetId(t, tweetAuthors);
        XUserSummary targetUser = null;
        String targetUsername = null;
        boolean selfThreadReply = false;
        if (kind == TweetKind.REPLY && !replyUserId.isBlank()) {
            if (replyUserId.equals(selfId)) {
                kind = TweetKind.POST; // 自回复串推计入原创流但标记 selfThreadReply
                selfThreadReply = true;
            } else {
                targetUser = userById.get(replyUserId);
                if (targetUser != null) {
                    targetUsername = targetUser.username();
                }
            }
        }

        if (kind == TweetKind.QUOTE) {
            for (JsonNode ref : refs) {
                if ("quoted".equals(ref.path("type").asText(""))) {
                    targetUser = userById.get(tweetAuthors.get(ref.path("id").asText("")));
                    targetUsername = targetUser == null ? null : targetUser.username();
                    break;
                }
            }
        }
        List<String> mentions = new ArrayList<>();
        JsonNode mentionArr = t.path("entities").path("mentions");
        if (mentionArr.isArray()) {
            for (JsonNode m : mentionArr) {
                String mu = m.path("username").asText("");
                if (!mu.isBlank()) {
                    mentions.add(mu);
                }
            }
        }

        JsonNode pm = t.path("public_metrics");
        String tweetId = t.path("id").asText("");
        String convId = t.path("conversation_id").asText("");
        if (convId.isBlank() && kind != TweetKind.REPLY && kind != TweetKind.RETWEET) {
            convId = tweetId;
        }
        String text = t.path("text").asText("");
        boolean hasMedia = t.path("attachments").hasNonNull("media_keys");
        boolean isThread = selfThreadReply || text.length() >= 220 || text.contains("🧵");

        return new RawTweet(
                tweetId,
                text,
                TwitterApiIoProvider.parseFlexibleTimestamp(t.path("created_at").asText("")),
                kind,
                targetUsername,
                targetUser,
                mentions,
                hasMedia,
                isThread,
                pm.path("impression_count").asLong(0L),
                pm.path("like_count").asInt(0),
                pm.path("reply_count").asInt(0),
                renamedField(pm, "repost_count", "retweet_count").asInt(0) + pm.path("quote_count").asInt(0),
                pm.path("bookmark_count").asInt(0),
                selfThreadReply,
                convId,
                inReplyToId,
                unavailablePublicMetrics(pm)
        );
    }

    private static java.util.Set<String> unavailablePublicMetrics(JsonNode metrics) {
        java.util.Set<String> unavailable = new java.util.HashSet<>();
        String[][] fields = {{"views", "impression_count"}, {"likes", "like_count"}, {"replies", "reply_count"},
                {"bookmarks", "bookmark_count"}};
        for (String[] field : fields) {
            if (!metrics.path(field[1]).isIntegralNumber() || metrics.path(field[1]).asLong() < 0) unavailable.add(field[0]);
        }
        JsonNode reposts = renamedField(metrics, "repost_count", "retweet_count");
        if (!reposts.isIntegralNumber() || reposts.asLong() < 0 || !metrics.path("quote_count").isIntegralNumber()
                || metrics.path("quote_count").asLong() < 0) unavailable.add("reposts");
        return unavailable;
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
