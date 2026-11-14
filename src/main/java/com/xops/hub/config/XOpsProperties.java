package com.xops.hub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "xops")
public class XOpsProperties {

    private String defaultProvider = "TWITTERAPI_IO";
    private String twitterapiIoKey = "";
    private String twitterapiIoBaseUrl = "https://api.twitterapi.io";
    private String oauthClientId = "";
    private String oauthClientSecret = "";
    private String oauthRedirectUri = "";
    private String officialBearerToken = "";
    private String officialBaseUrl = "https://api.x.com/2";
    private int maxTimelinePages = 600;
    private int maxMentionPages = 600;
    private int maxFollowingPages = 4;
    private int maxFollowerPages = 5;
    private int apiPoolCoreSize = 16;
    private int apiPoolMaxSize = 28;
    private int apiPoolQueueCapacity = 128;
    private long apiQueueTimeoutMs = 4500L;
    private int apiMaxParallelSlices = 4;
    private long cacheTtlSeconds = 300;
    private String diskCacheDir = "./.cache/snapshots";
    private boolean requireLoginSelfOnly = true;
    private String timezone = "Asia/Shanghai";
    private String tokenEncryptionSecret = "";
    private boolean schedulerEnabled = true;
    private long schedulerPollMs = 15000L;
    private long scheduleGraceWindowSeconds = 300L;
    private boolean top50AutoSyncEnabled = true;
    private String top50AutoSyncCron = "0 20 23 * * *";
    private String algoProfilePath = "./.cache/x-algorithm-profile.json";

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

    public boolean hasTwitterApiIoKey() {
        return twitterapiIoKey != null && !twitterapiIoKey.isBlank();
    }

    public boolean hasOfficialBearerToken() {
        return officialBearerToken != null && !officialBearerToken.isBlank();
    }

    public boolean hasOAuthClient() {
        return oauthClientId != null && !oauthClientId.isBlank();
    }
}
