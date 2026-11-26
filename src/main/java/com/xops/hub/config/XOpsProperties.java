package com.xops.hub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "xops")
public class XOpsProperties {

    private String defaultProvider = "TWITTERAPI_IO";
    private String twitterapiIoKey = "";
    private String twitterapiIoBaseUrl = "https://api.twitterapi.io";
    private Boolean splitInboundSearchQueries;
    private boolean fxtwitterEnabled = false;
    private String fxtwitterBaseUrl = "https://api.fxtwitter.com/2";
    private String oauthClientId = "";
    private String oauthClientSecret = "";
    private String oauthRedirectUri = "";
    private String officialBearerToken = "";
    private String officialBaseUrl = "https://api.x.com/2";
    private String officialProxyUrl = "";
    private int maxTimelinePages = 600;
    private int maxMentionPages = 600;
    private int maxFollowingPages = 4;
    private int maxFollowerPages = 5;
    private int apiPoolCoreSize = 20;
    private int apiPoolMaxSize = 36;
    private int apiPoolQueueCapacity = 320;
    private long apiQueueTimeoutMs = 12000L;
    private int apiMaxParallelSlices = 4;
    private long cacheTtlSeconds = 300;
    private String diskCacheDir = "./.cache/snapshots";
    private boolean requireLoginSelfOnly = true;
    private String timezone = "Asia/Shanghai";
    private String tokenEncryptionSecret = "";
    private boolean schedulerEnabled = true;
    private Boolean autoRepostEnabled;
    private int autoRepostQuietStartHour = 12;
    private int autoRepostQuietEndHour = 24;
    private long schedulerPollMs = 15000L;
    private long scheduleGraceWindowSeconds = 300L;
    private boolean top50AutoSyncEnabled = true;
    private String top50AutoSyncCron = "0 20 23 * * *";
    private boolean top10SilentHistoryEnabled = false;
    private long top10SilentHistoryPollMs = 45000L;
    private String algoProfilePath = "./.cache/x-algorithm-profile.json";
    private int maxConcurrentUsers = 50;
    private int maxThrottleQueueSize = 150;
    private long maxThrottleWaitMs = 15000L;
    private long activeUserWindowSeconds = 30L;
    private long overCapacityBaseDelayMs = 100L;
    private long overCapacityMaxDelayMs = 1200L;
    private int ipApiFloodLimitPerMinute = 6000;
    private int ipAuthLimitPerMinute = 150;
    private int hourlyUpstreamApiBudget = 6000;
    private int dailyUpstreamApiBudget = 30000;
    private int dailyTweetPublishLimitPerUser = 24;
    private String hourlyUpstreamUsdBudget = "10.00";
    private String dailyUpstreamUsdBudget = "30.00";
    private String monthlyUpstreamUsdBudget = "100.00";
    private String unpricedUpstreamRequestReserveUsd = "0.20";

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    public String getTwitterapiIoKey() {
        return twitterapiIoKey;
    }

    public void setTwitterapiIoKey(String twitterapiIoKey) {
        this.twitterapiIoKey = twitterapiIoKey;
    }

    public String getTwitterapiIoBaseUrl() {
        return twitterapiIoBaseUrl;
    }

    public void setTwitterapiIoBaseUrl(String twitterapiIoBaseUrl) {
        this.twitterapiIoBaseUrl = twitterapiIoBaseUrl;
    }

    public Boolean getSplitInboundSearchQueries() {
        return splitInboundSearchQueries;
    }

    public void setSplitInboundSearchQueries(Boolean splitInboundSearchQueries) {
        this.splitInboundSearchQueries = splitInboundSearchQueries;
    }

    public boolean isFxtwitterEnabled() {
        return fxtwitterEnabled;
    }

    public void setFxtwitterEnabled(boolean fxtwitterEnabled) {
        this.fxtwitterEnabled = fxtwitterEnabled;
    }

    public String getFxtwitterBaseUrl() {
        return fxtwitterBaseUrl;
    }

    public void setFxtwitterBaseUrl(String fxtwitterBaseUrl) {
        this.fxtwitterBaseUrl = fxtwitterBaseUrl;
    }

    public String getOauthClientId() {
        return oauthClientId;
    }

    public void setOauthClientId(String oauthClientId) {
        this.oauthClientId = oauthClientId;
    }

    public String getOauthClientSecret() {
        return oauthClientSecret;
    }

    public void setOauthClientSecret(String oauthClientSecret) {
        this.oauthClientSecret = oauthClientSecret;
    }

    public String getOauthRedirectUri() {
        return oauthRedirectUri;
    }

    public void setOauthRedirectUri(String oauthRedirectUri) {
        this.oauthRedirectUri = oauthRedirectUri;
    }

    public String getOfficialBearerToken() {
        return officialBearerToken;
    }

    public void setOfficialBearerToken(String officialBearerToken) {
        this.officialBearerToken = officialBearerToken;
    }

    public String getOfficialBaseUrl() {
        return officialBaseUrl;
    }

    public void setOfficialBaseUrl(String officialBaseUrl) {
        this.officialBaseUrl = officialBaseUrl;
    }

    public String getOfficialProxyUrl() {
        return officialProxyUrl;
    }

    public void setOfficialProxyUrl(String officialProxyUrl) {
        this.officialProxyUrl = officialProxyUrl;
    }

    public int getMaxTimelinePages() {
        return maxTimelinePages;
    }

    public void setMaxTimelinePages(int maxTimelinePages) {
        this.maxTimelinePages = maxTimelinePages;
    }

    public int getMaxMentionPages() {
        return maxMentionPages;
    }

    public void setMaxMentionPages(int maxMentionPages) {
        this.maxMentionPages = maxMentionPages;
    }

    public int getMaxFollowingPages() {
        return maxFollowingPages;
    }

    public void setMaxFollowingPages(int maxFollowingPages) {
        this.maxFollowingPages = maxFollowingPages;
    }

    public int getMaxFollowerPages() {
        return maxFollowerPages;
    }

    public void setMaxFollowerPages(int maxFollowerPages) {
        this.maxFollowerPages = maxFollowerPages;
    }

    public int getApiPoolCoreSize() {
        return apiPoolCoreSize;
    }

    public void setApiPoolCoreSize(int apiPoolCoreSize) {
        this.apiPoolCoreSize = apiPoolCoreSize;
    }

    public int getApiPoolMaxSize() {
        return apiPoolMaxSize;
    }

    public void setApiPoolMaxSize(int apiPoolMaxSize) {
        this.apiPoolMaxSize = apiPoolMaxSize;
    }

    public int getApiPoolQueueCapacity() {
        return apiPoolQueueCapacity;
    }

    public void setApiPoolQueueCapacity(int apiPoolQueueCapacity) {
        this.apiPoolQueueCapacity = apiPoolQueueCapacity;
    }

    public long getApiQueueTimeoutMs() {
        return apiQueueTimeoutMs;
    }

    public void setApiQueueTimeoutMs(long apiQueueTimeoutMs) {
        this.apiQueueTimeoutMs = apiQueueTimeoutMs;
    }

    public int getApiMaxParallelSlices() {
        return apiMaxParallelSlices;
    }

    public void setApiMaxParallelSlices(int apiMaxParallelSlices) {
        this.apiMaxParallelSlices = apiMaxParallelSlices;
    }

    public long getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public void setCacheTtlSeconds(long cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    public String getDiskCacheDir() {
        return diskCacheDir;
    }

    public void setDiskCacheDir(String diskCacheDir) {
        this.diskCacheDir = diskCacheDir;
    }

    public boolean isRequireLoginSelfOnly() {
        return requireLoginSelfOnly;
    }

    public void setRequireLoginSelfOnly(boolean requireLoginSelfOnly) {
        this.requireLoginSelfOnly = requireLoginSelfOnly;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getTokenEncryptionSecret() {
        return tokenEncryptionSecret;
    }

    public void setTokenEncryptionSecret(String tokenEncryptionSecret) {
        this.tokenEncryptionSecret = tokenEncryptionSecret;
    }

    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }

    public long getSchedulerPollMs() {
        return schedulerPollMs;
    }

    public void setSchedulerPollMs(long schedulerPollMs) {
        this.schedulerPollMs = schedulerPollMs;
    }

    public long getScheduleGraceWindowSeconds() {
        return scheduleGraceWindowSeconds;
    }

    public void setScheduleGraceWindowSeconds(long scheduleGraceWindowSeconds) {
        this.scheduleGraceWindowSeconds = scheduleGraceWindowSeconds;
    }

    public boolean isTop50AutoSyncEnabled() {
        return top50AutoSyncEnabled;
    }

    public void setTop50AutoSyncEnabled(boolean top50AutoSyncEnabled) {
        this.top50AutoSyncEnabled = top50AutoSyncEnabled;
    }

    public String getTop50AutoSyncCron() {
        return top50AutoSyncCron;
    }

    public void setTop50AutoSyncCron(String top50AutoSyncCron) {
        this.top50AutoSyncCron = top50AutoSyncCron;
    }

    public String getAlgoProfilePath() {
        return algoProfilePath;
    }

    public void setAlgoProfilePath(String algoProfilePath) {
        this.algoProfilePath = algoProfilePath;
    }

    public int getMaxConcurrentUsers() {
        return maxConcurrentUsers;
    }

    public void setMaxConcurrentUsers(int maxConcurrentUsers) {
        this.maxConcurrentUsers = maxConcurrentUsers;
    }

    public int getMaxThrottleQueueSize() {
        return maxThrottleQueueSize;
    }

    public void setMaxThrottleQueueSize(int maxThrottleQueueSize) {
        this.maxThrottleQueueSize = maxThrottleQueueSize;
    }

    public long getMaxThrottleWaitMs() {
        return maxThrottleWaitMs;
    }

    public void setMaxThrottleWaitMs(long maxThrottleWaitMs) {
        this.maxThrottleWaitMs = maxThrottleWaitMs;
    }

    public long getActiveUserWindowSeconds() {
        return activeUserWindowSeconds;
    }

    public void setActiveUserWindowSeconds(long activeUserWindowSeconds) {
        this.activeUserWindowSeconds = activeUserWindowSeconds;
    }

    public long getOverCapacityBaseDelayMs() {
        return overCapacityBaseDelayMs;
    }

    public void setOverCapacityBaseDelayMs(long overCapacityBaseDelayMs) {
        this.overCapacityBaseDelayMs = overCapacityBaseDelayMs;
    }

    public long getOverCapacityMaxDelayMs() {
        return overCapacityMaxDelayMs;
    }

    public void setOverCapacityMaxDelayMs(long overCapacityMaxDelayMs) {
        this.overCapacityMaxDelayMs = overCapacityMaxDelayMs;
    }

    public int getIpApiFloodLimitPerMinute() {
        return ipApiFloodLimitPerMinute;
    }

    public void setIpApiFloodLimitPerMinute(int ipApiFloodLimitPerMinute) {
        this.ipApiFloodLimitPerMinute = ipApiFloodLimitPerMinute;
    }

    public int getIpAuthLimitPerMinute() {
        return ipAuthLimitPerMinute;
    }

    public void setIpAuthLimitPerMinute(int ipAuthLimitPerMinute) {
        this.ipAuthLimitPerMinute = ipAuthLimitPerMinute;
    }

    public int getHourlyUpstreamApiBudget() {
        return hourlyUpstreamApiBudget;
    }

    public void setHourlyUpstreamApiBudget(int hourlyUpstreamApiBudget) {
        this.hourlyUpstreamApiBudget = hourlyUpstreamApiBudget;
    }

    public int getDailyUpstreamApiBudget() {
        return dailyUpstreamApiBudget;
    }

    public void setDailyUpstreamApiBudget(int dailyUpstreamApiBudget) {
        this.dailyUpstreamApiBudget = dailyUpstreamApiBudget;
    }

    public int getDailyTweetPublishLimitPerUser() {
        return dailyTweetPublishLimitPerUser;
    }

    public void setDailyTweetPublishLimitPerUser(int dailyTweetPublishLimitPerUser) {
        this.dailyTweetPublishLimitPerUser = dailyTweetPublishLimitPerUser;
    }

    public String getHourlyUpstreamUsdBudget() { return hourlyUpstreamUsdBudget; }
    public void setHourlyUpstreamUsdBudget(String value) { hourlyUpstreamUsdBudget = value; }
    public String getDailyUpstreamUsdBudget() { return dailyUpstreamUsdBudget; }
    public void setDailyUpstreamUsdBudget(String value) { dailyUpstreamUsdBudget = value; }
    public String getMonthlyUpstreamUsdBudget() { return monthlyUpstreamUsdBudget; }
    public void setMonthlyUpstreamUsdBudget(String value) { monthlyUpstreamUsdBudget = value; }
    public String getUnpricedUpstreamRequestReserveUsd() { return unpricedUpstreamRequestReserveUsd; }
    public void setUnpricedUpstreamRequestReserveUsd(String value) { unpricedUpstreamRequestReserveUsd = value; }

    public boolean isTop10SilentHistoryEnabled() {
        return top10SilentHistoryEnabled;
    }

    public void setTop10SilentHistoryEnabled(boolean top10SilentHistoryEnabled) {
        this.top10SilentHistoryEnabled = top10SilentHistoryEnabled;
    }

    public long getTop10SilentHistoryPollMs() {
        return top10SilentHistoryPollMs;
    }

    public void setTop10SilentHistoryPollMs(long top10SilentHistoryPollMs) {
        this.top10SilentHistoryPollMs = top10SilentHistoryPollMs;
    }

    public boolean hasTwitterApiIoKey() {
        return twitterapiIoKey != null && !twitterapiIoKey.isBlank();
    }

    public boolean hasOfficialBearerToken() {
        return officialBearerToken != null && !officialBearerToken.isBlank();
    }

    public boolean hasOAuthClient() {
        return oauthClientId != null && !oauthClientId.isBlank();
    }

    public Boolean getAutoRepostEnabled() {
        return autoRepostEnabled;
    }

    public void setAutoRepostEnabled(Boolean autoRepostEnabled) {
        this.autoRepostEnabled = autoRepostEnabled;
    }

    public int getAutoRepostQuietStartHour() {
        return autoRepostQuietStartHour;
    }

    public void setAutoRepostQuietStartHour(int autoRepostQuietStartHour) {
        this.autoRepostQuietStartHour = autoRepostQuietStartHour;
    }

    public int getAutoRepostQuietEndHour() {
        return autoRepostQuietEndHour;
    }

    public void setAutoRepostQuietEndHour(int autoRepostQuietEndHour) {
        this.autoRepostQuietEndHour = autoRepostQuietEndHour;
    }

    public boolean isAutoRepostQuietHour(long epochMs, java.time.ZoneId zone) {
        return isQuietHour(epochMs, zone, autoRepostQuietStartHour, autoRepostQuietEndHour);
    }

    /**
     * Checks whether {@code epochMs} falls inside the 24-hour quiet window {@code [startHour, endHour)}.
     */
    public static boolean isQuietHour(long epochMs, java.time.ZoneId zone, int startHour, int endHour) {
        int s = Math.max(0, Math.min(24, startHour));
        int e = Math.max(0, Math.min(24, endHour));
        if (s == e) {
            return false;
        }
        java.time.ZoneId effectiveZone = zone != null ? zone : java.time.ZoneId.of("Asia/Shanghai");
        int hour = java.time.Instant.ofEpochMilli(epochMs).atZone(effectiveZone).getHour();
        if (s < e) {
            return hour >= s && hour < e;
        }
        return hour >= s || hour < e;
    }

    /**
     * Computes the next wakeup timestamp (with a 5s buffer) right after the quiet window ends.
     */
    public static long nextQuietWindowEndEpochMs(long epochMs, java.time.ZoneId zone, int startHour, int endHour) {
        int s = Math.max(0, Math.min(24, startHour));
        int e = Math.max(0, Math.min(24, endHour));
        if (!isQuietHour(epochMs, zone, s, e)) {
            return epochMs;
        }
        java.time.ZoneId effectiveZone = zone != null ? zone : java.time.ZoneId.of("Asia/Shanghai");
        java.time.ZonedDateTime now = java.time.Instant.ofEpochMilli(epochMs).atZone(effectiveZone);
        java.time.LocalDate targetDate = now.toLocalDate();
        int targetHour = e;
        if (e >= 24) {
            targetDate = targetDate.plusDays(1);
            targetHour = 0;
        } else if (s > e && now.getHour() >= s) {
            targetDate = targetDate.plusDays(1);
        }
        return targetDate.atTime(targetHour, 0, 5).atZone(effectiveZone).toInstant().toEpochMilli();
    }

    public boolean isProductionOrigin() {
        if (oauthRedirectUri == null || oauthRedirectUri.isBlank()) {
            return false;
        }
        try {
            java.net.URI uri = java.net.URI.create(oauthRedirectUri.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || !"https".equalsIgnoreCase(scheme) || host == null || host.isBlank()) {
                return false;
            }
            String lowerHost = host.toLowerCase(java.util.Locale.ROOT);
            return !("localhost".equals(lowerHost)
                    || "127.0.0.1".equals(lowerHost)
                    || "0.0.0.0".equals(lowerHost)
                    || "::1".equals(lowerHost)
                    || "[::1]".equals(lowerHost));
        } catch (Exception ex) {
            return false;
        }
    }

    private static boolean isNonProductionProfile(org.springframework.core.env.Environment env) {
        if (env == null) {
            return false;
        }
        for (String profile : env.getActiveProfiles()) {
            if (profile == null) continue;
            String normalized = profile.strip().toLowerCase(java.util.Locale.ROOT);
            if ("local".equals(normalized) || "preview".equals(normalized) || "test".equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    public boolean isAutoRepostActive(org.springframework.core.env.Environment env) {
        if (autoRepostEnabled != null) {
            return autoRepostEnabled;
        }
        if (isNonProductionProfile(env)) {
            return false;
        }
        return isProductionOrigin();
    }

    public boolean isScheduledPublishActive(org.springframework.core.env.Environment env) {
        if (!schedulerEnabled) {
            return false;
        }
        if (isNonProductionProfile(env)) {
            return false;
        }
        return isProductionOrigin();
    }
}
