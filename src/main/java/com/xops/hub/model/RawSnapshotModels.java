package com.xops.hub.model;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class RawSnapshotModels {

    private RawSnapshotModels() {
    }

    public enum ProviderMode {
        AUTO,
        DEMO,
        TWITTERAPI_IO,
        X_OFFICIAL_V2;

        public static ProviderMode fromString(String value, ProviderMode fallback) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            for (ProviderMode mode : values()) {
                if (mode.name().equalsIgnoreCase(value.trim())) {
                    return mode;
                }
            }
            return fallback;
        }
    }

    public enum TweetKind {
        POST,
        REPLY,
        QUOTE,
        RETWEET
    }

    public enum InboundKind {
        REPLY_TO_ME,
        QUOTE_ME,
        MENTION_ME
    }

    public record XUserSummary(
            String id,
            String username,
            String displayName,
            String avatarUrl,
            String bio,
            boolean verified,
            int followersCount,
            int followingCount,
            int totalPostsCount,
            String joinedDate
    ) {
    }

    public record RawTweet(
            String id,
            String text,
            Instant createdAt,
            TweetKind kind,
            String targetUsername,
            XUserSummary targetUser,
            List<String> mentionedUsernames,
            boolean hasMedia,
            boolean isThread,
            long viewCount,
            int likeCount,
            int replyCount,
            int retweetCount,
            int bookmarkCount,
            boolean selfThreadReply,
            String conversationId,
            String inReplyToId,
            Set<String> unavailableMetrics
    ) {
        public static final Set<String> ALL_METRICS = Set.of("views", "likes", "replies", "reposts", "bookmarks");
        @JsonCreator
        public RawTweet {
            conversationId = conversationId != null ? conversationId : "";
            inReplyToId = inReplyToId != null ? inReplyToId : "";
            unavailableMetrics = unavailableMetrics == null ? ALL_METRICS : Set.copyOf(unavailableMetrics);
        }

        public RawTweet(String id, String text, Instant createdAt, TweetKind kind, String targetUsername,
                XUserSummary targetUser, List<String> mentionedUsernames, boolean hasMedia, boolean isThread,
                long viewCount, int likeCount, int replyCount, int retweetCount, int bookmarkCount,
                boolean selfThreadReply, String conversationId, String inReplyToId) {
            this(id, text, createdAt, kind, targetUsername, targetUser, mentionedUsernames, hasMedia, isThread,
                    viewCount, likeCount, replyCount, retweetCount, bookmarkCount, selfThreadReply,
                    conversationId, inReplyToId, Set.of());
        }

        public RawTweet(
                String id,
                String text,
                Instant createdAt,
                TweetKind kind,
                String targetUsername,
                XUserSummary targetUser,
                List<String> mentionedUsernames,
                boolean hasMedia,
                boolean isThread,
                long viewCount,
                int likeCount,
                int replyCount,
                int retweetCount,
                int bookmarkCount,
                boolean selfThreadReply
        ) {
            this(id, text, createdAt, kind, targetUsername, targetUser, mentionedUsernames,
                    hasMedia, isThread, viewCount, likeCount, replyCount, retweetCount, bookmarkCount,
                    selfThreadReply, "", "");
        }

        public RawTweet(
                String id,
                String text,
                Instant createdAt,
                TweetKind kind,
                String targetUsername,
                XUserSummary targetUser,
                List<String> mentionedUsernames,
                boolean hasMedia,
                boolean isThread,
                long viewCount,
                int likeCount,
                int replyCount,
                int retweetCount,
                int bookmarkCount
        ) {
            this(id, text, createdAt, kind, targetUsername, targetUser, mentionedUsernames,
                    hasMedia, isThread, viewCount, likeCount, replyCount, retweetCount, bookmarkCount,
                    false, "", "");
        }
    }

    public record InboundInteraction(
            String tweetId,
            XUserSummary peer,
            Instant createdAt,
            InboundKind kind,
            String snippet,
            String conversationId,
            String inReplyToId,
            String inReplyToUsername
    ) {
        @JsonCreator
        public InboundInteraction {
            conversationId = conversationId != null ? conversationId : "";
            inReplyToId = inReplyToId != null ? inReplyToId : "";
            inReplyToUsername = inReplyToUsername != null ? inReplyToUsername : "";
        }

        public InboundInteraction(
                String tweetId,
                XUserSummary peer,
                Instant createdAt,
                InboundKind kind,
                String snippet
        ) {
            this(tweetId, peer, createdAt, kind, snippet, "", "", "");
        }
    }

    public record RelationshipEntry(
            XUserSummary user,
            boolean iFollowThem,
            boolean theyFollowMe,
            boolean relationshipVerified
    ) {
        public RelationshipEntry(XUserSummary user, boolean iFollowThem, boolean theyFollowMe) {
            this(user, iFollowThem, theyFollowMe, false);
        }
    }

    public record RawAccountSnapshot(
            ProviderMode resolvedProvider,
            boolean liveData,
            String bannerNotice,
            Instant fetchedAt,
            XUserSummary profile,
            List<RawTweet> timeline,
            List<InboundInteraction> inboundInteractions,
            List<RelationshipEntry> relationships,
            boolean followersTruncated,
            boolean degradedPartial,
            int coveredWindowDays,
            Instant timelineTailTime,
            String timelineNextCursor,
            boolean timelineExhausted,
            Instant mentionsTailTime,
            String mentionsNextCursor,
            boolean mentionsExhausted,
            boolean followingsTruncated
    ) {
        @JsonCreator
        public RawAccountSnapshot {
            timelineNextCursor = timelineNextCursor != null ? timelineNextCursor : "";
            mentionsNextCursor = mentionsNextCursor != null ? mentionsNextCursor : "";
        }

        public RawAccountSnapshot(ProviderMode resolvedProvider, boolean liveData, String bannerNotice,
                Instant fetchedAt, XUserSummary profile, List<RawTweet> timeline,
                List<InboundInteraction> inboundInteractions, List<RelationshipEntry> relationships,
                boolean followersTruncated, boolean degradedPartial, boolean followingsTruncated) {
            this(resolvedProvider, liveData, bannerNotice, fetchedAt, profile, timeline, inboundInteractions,
                    relationships, followersTruncated, degradedPartial, 30, null, "", true, null, "", true, followingsTruncated);
        }

        public RawAccountSnapshot(ProviderMode resolvedProvider, boolean liveData, String bannerNotice,
                Instant fetchedAt, XUserSummary profile, List<RawTweet> timeline,
                List<InboundInteraction> inboundInteractions, List<RelationshipEntry> relationships,
                boolean followersTruncated, boolean degradedPartial, int coveredWindowDays,
                Instant timelineTailTime, String timelineNextCursor, boolean timelineExhausted,
                Instant mentionsTailTime, String mentionsNextCursor, boolean mentionsExhausted) {
            this(resolvedProvider, liveData, bannerNotice, fetchedAt, profile, timeline, inboundInteractions,
                    relationships, followersTruncated, degradedPartial, coveredWindowDays, timelineTailTime,
                    timelineNextCursor, timelineExhausted, mentionsTailTime, mentionsNextCursor, mentionsExhausted, false);
        }

        public RawAccountSnapshot(
                ProviderMode resolvedProvider,
                boolean liveData,
                String bannerNotice,
                Instant fetchedAt,
                XUserSummary profile,
                List<RawTweet> timeline,
                List<InboundInteraction> inboundInteractions,
                List<RelationshipEntry> relationships,
                boolean followersTruncated,
                boolean degradedPartial
        ) {
            this(resolvedProvider, liveData, bannerNotice, fetchedAt, profile,
                    timeline, inboundInteractions, relationships, followersTruncated, degradedPartial,
                    30, null, "", true, null, "", true);
        }

        public RawAccountSnapshot(
                ProviderMode resolvedProvider,
                boolean liveData,
                String bannerNotice,
                Instant fetchedAt,
                XUserSummary profile,
                List<RawTweet> timeline,
                List<InboundInteraction> inboundInteractions,
                List<RelationshipEntry> relationships
        ) {
            this(resolvedProvider, liveData, bannerNotice, fetchedAt, profile,
                    timeline, inboundInteractions, relationships, false, false,
                    30, null, "", true, null, "", true);
        }
    }
}
