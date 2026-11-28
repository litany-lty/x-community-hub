package com.xops.hub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.assistant.AssistantStore;
import com.xops.hub.assistant.ScheduledPublicationGuard;
import com.xops.hub.model.ScheduledTweetModels.AssistantSource;
import com.xops.hub.model.XPostLink;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.ScheduledTweetModels.BatchConfirmItem;
import com.xops.hub.model.ScheduledTweetModels.BatchCreateResponse;
import com.xops.hub.model.ScheduledTweetModels.OverdueReason;
import com.xops.hub.model.ScheduledTweetModels.ScheduledMediaAsset;
import com.xops.hub.model.ScheduledTweetModels.ScheduledTweetJob;
import com.xops.hub.model.ScheduledTweetModels.ScheduledTweetListResponse;
import com.xops.hub.model.ScheduledTweetModels.ScheduledTweetStatus;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.provider.XPublishClient;
import com.xops.hub.provider.XPublishClient.XPublishException;
import com.xops.hub.provider.XPublishClient.PublicationPayload;
import com.xops.hub.provider.XPublishClient.PublicationReceipt;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 图文定时推文调度、状态持久化、停机逾期二次确认补发与全链路防重服务。
 */
@Service
public class ScheduledTweetService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTweetService.class);

    public static final int QUEUE_VERSION = 3;
    public static final int MAX_STATIC_IMAGES = 4;
    public static final long MAX_STATIC_IMAGE_BYTES = 5L * 1024 * 1024;
    public static final long MAX_GIF_BYTES = 15L * 1024 * 1024;
    public static final int MAX_TEXT_LENGTH = 25_000;
    public static final long MIN_SCHEDULE_LEAD_MS = 15_000L;
    public static final long MIN_TWEET_INTERVAL_MS = 3_600_000L;
    public static final int MAX_BATCH_CREATE_ITEMS = 20;
    public static final int MAX_ACTIVE_JOBS_PER_ACCOUNT = 60;
    public static final int MAX_STORED_JOBS_PER_ACCOUNT = 500;
    public static final long MAX_STORED_MEDIA_BYTES_PER_ACCOUNT = 256L * 1024 * 1024;
    private static final long MIN_FREE_DISK_BYTES = 512L * 1024 * 1024;
    public static final long DUPLICATE_WINDOW_MS = 60_000L;
    public static final long MEDIA_REUSE_TTL_MS = 20L * 3600_000L;
    private static final int MAX_CLIENT_REQUEST_CACHE = 200;
    private static final Pattern SAFE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern QUOTE_TWEET_ID_PATTERN = Pattern.compile("^[0-9]{2,20}$");
    private static final Pattern TRAILING_STATUS_URL_PATTERN = Pattern.compile(
            "(?is)^(.+?)(?:\\r?\\n)+https?://(?:(?:www|mobile)\\.)?(?:x|twitter)\\.com/[A-Za-z0-9_]{1,15}/status/([0-9]{2,20})/?\\s*$");
    private static final DateTimeFormatter CONFLICT_TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    public record MediaUploadInput(String fileName, byte[] bytes) {
    }

    public record BatchTweetDraftInput(
            String text,
            long scheduledAtEpochMs,
            String clientRequestId,
            List<MediaUploadInput> uploads, AssistantSource assistantSource,
            String quoteTweetId, String quoteTweetUrl
    ) {
        public BatchTweetDraftInput(String text, long scheduledAtEpochMs, String clientRequestId,
                                    List<MediaUploadInput> uploads, AssistantSource assistantSource) {
            this(text, scheduledAtEpochMs, clientRequestId, uploads, assistantSource, null, null);
        }

        public BatchTweetDraftInput(String text,long time,String key,List<MediaUploadInput> uploads) {
            this(text,time,key,uploads,null,null,null);
        }
    }

    public record MediaFileContent(byte[] bytes, String mimeType, String fileName) {
    }

    private record DetectedImageFormat(String mimeType, String extension, long maxBytes) {
    }

    private record DailyPublishCounter(String accountId, String localDate, int count) {
    }

    private record AcceptedDelivery(ScheduledTweetJob job, PublicationReceipt receipt, long acceptedAt) {
        ScheduledTweetJob published(ScheduledTweetJob current) {
            String username = current.username() == null || current.username().isBlank() ? "i" : current.username();
            return current.markPublished(receipt, "https://x.com/" + username + "/status/" + receipt.tweetId(), acceptedAt);
        }
    }

    private record QueueStorage(
            int version,
            String accountId,
            String username,
            List<ScheduledTweetJob> jobs,
            Map<String, String> clientRequestMap, Map<String,ScheduledTweetJob> assistantReceipts
    ) {
        static QueueStorage empty(String accountId, String username) {
            return new QueueStorage(QUEUE_VERSION, accountId, username, new ArrayList<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
        }

        QueueStorage mutableCopy(String fallbackUsername) {
            List<ScheduledTweetJob> copyJobs = jobs != null ? new ArrayList<>(jobs) : new ArrayList<>();
            Map<String, String> copyMap = clientRequestMap != null ? new LinkedHashMap<>(clientRequestMap) : new LinkedHashMap<>();
            String resolvedUser = (username != null && !username.isBlank()) ? username : fallbackUsername;
            return new QueueStorage(QUEUE_VERSION, accountId, resolvedUser, copyJobs, copyMap, assistantReceipts == null ? new LinkedHashMap<>() : new LinkedHashMap<>(assistantReceipts));
        }
    }

    private AssistantStore assistantStore;
    @Autowired
    public void setAssistantStore(AssistantStore assistantStore) { this.assistantStore = assistantStore; }

    private boolean owner(String accountId, String username) {
        if (assistantStore != null) return com.xops.hub.model.AuthorIdentity.trustedId(assistantStore.boundOwnerId()).equals(accountId);
        if (communityHubService != null) return communityHubService.isAuthorAccount(accountId, username);
        return com.xops.hub.model.AuthorIdentity.ARCHIVE_ACCOUNT_ID.equals(accountId);
    }

    private final XOpsProperties properties;
    private final ObjectMapper objectMapper;
    private final XOAuthService oauthService;
    private final XPublishClient publishClient;
    private final CommunityHubService communityHubService;
    private static final int LOCK_STRIPES = 64;
    private final Clock clock;
    private final Object[] accountLocks;
    private final Set<String> dispatchingAccounts = ConcurrentHashMap.newKeySet();
    private final ExecutorService dispatchExecutor;
    private final AtomicBoolean bootRecovered = new AtomicBoolean(false);
    private final Object mediaSpaceLock = new Object();
    private long reservedMediaBytes;
    @Autowired(required = false)
    private org.springframework.core.env.Environment environment;

    @Autowired
    public ScheduledTweetService(
            XOpsProperties properties,
            ObjectMapper objectMapper,
            XOAuthService oauthService,
            XPublishClient publishClient,
            CommunityHubService communityHubService
    ) {
        this(
                properties,
                objectMapper,
                oauthService,
                publishClient,
                Objects.requireNonNull(communityHubService, "communityHubService must not be null"),
                Clock.systemUTC()
        );
    }

    public ScheduledTweetService(
            XOpsProperties properties,
            ObjectMapper objectMapper,
            XOAuthService oauthService,
            XPublishClient publishClient,
            Clock clock
    ) {
        this(properties, objectMapper, oauthService, publishClient, null, clock);
    }

    public ScheduledTweetService(
            XOpsProperties properties,
            ObjectMapper objectMapper,
            XOAuthService oauthService,
            XPublishClient publishClient,
            CommunityHubService communityHubService,
            Clock clock
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.oauthService = oauthService;
        this.publishClient = publishClient;
        this.communityHubService = communityHubService;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.accountLocks = new Object[LOCK_STRIPES];
        for (int i = 0; i < accountLocks.length; i++) accountLocks[i] = new Object();
        AtomicInteger seq = new AtomicInteger(1);
        this.dispatchExecutor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(128), r -> {
                    Thread t = new Thread(r, "xops-sched-dispatch-" + seq.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public boolean isScheduledPublishRuntimeEnabled() {
        return properties.isScheduledPublishActive(environment);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (assistantStore != null) reconcileOwnerDeliveryGuards(assistantStore.boundOwnerId(), true);
        if (!isScheduledPublishRuntimeEnabled()) {
            log.info("Local/non-production runtime detected; scheduled tweet background publishing is disabled.");
            return;
        }
        ensureBootRecovery();
    }

    @Scheduled(fixedDelayString = "${xops.scheduler-poll-ms:15000}")
    public void runScheduledTick() {
        if (!isScheduledPublishRuntimeEnabled()) {
            return;
        }
        ensureBootRecovery();
        for (String accountId : discoverScheduledAccountIds()) {
            try {
                triggerAsyncDispatch(accountId);
            } catch (Exception ex) {
                log.warn("Scheduled tweet tick failed for account {}: {}", accountId, ex.getMessage());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        dispatchExecutor.shutdown();
        try {
            if (!dispatchExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                dispatchExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            dispatchExecutor.shutdownNow();
        }
    }

    private synchronized void ensureBootRecovery() {
        if (!bootRecovered.get()) recoverAllAccountsOnStartup();
    }

    public synchronized void recoverAllAccountsOnStartup() {
        if (bootRecovered.get()) return;
        long nowMs = clock.millis();
        for (String accountId : discoverScheduledAccountIds()) {
            try {
                synchronized (accountLock(accountId)) {
                    QueueStorage queue = loadQueueLocked(accountId, "");
                    boolean changed = false;
                    for (int i = 0; i < queue.jobs().size(); i++) {
                        ScheduledTweetJob job = queue.jobs().get(i);
                        if (job.status() == ScheduledTweetStatus.SENDING) {
                            boolean uncertain = job.postRequestDispatched();
                            String err = uncertain
                                    ? "服务重启前推文请求已发出但未收到回执，请先查看 X 主页确认未发出后再点击确认补发"
                                    : "服务重启中断了发送流程，二次确认后将自动补发";
                            queue.jobs().set(i, job.markOverdueConfirmRequired(
                                    OverdueReason.INTERRUPTED_SENDING,
                                    uncertain,
                                    UUID.randomUUID().toString(),
                                    err,
                                    nowMs
                            ));
                            changed = true;
                        }
                    }
                    if (promoteOverdueLocked(queue, nowMs)) {
                        changed = true;
                    }
                    if (changed) {
                        saveQueueLocked(queue);
                    }
                }
            } catch (RuntimeException ex) {
                log.warn("Scheduled queue recovery stopped for account {}: {}", accountId, ex.getMessage());
            }
        }
        if (assistantStore != null) reconcileOwnerDeliveryGuards(assistantStore.boundOwnerId(), true);
        bootRecovered.set(true);
    }

    public long latestAccountPublishEpochMs(String accountId, String username) {
        if (accountId == null || accountId.isBlank() || !SAFE_ID_PATTERN.matcher(accountId).matches()) {
            return 0L;
        }
        long latest = 0L;
        synchronized (accountLock(accountId)) {
            QueueStorage queue = loadQueueLocked(accountId, username);
            for (ScheduledTweetJob job : queue.jobs()) {
                if (job.status() == ScheduledTweetStatus.PUBLISHED && job.publishedAtEpochMs() > 0L) {
                    latest = Math.max(latest, job.publishedAtEpochMs());
                } else if (job.status() == ScheduledTweetStatus.SENDING && job.updatedAtEpochMs() > 0L) {
                    latest = Math.max(latest, job.updatedAtEpochMs());
                } else if (job.uncertainDelivery() || job.overdueReason() == OverdueReason.UNCERTAIN_DELIVERY) {
                    long sentAt = job.postRequestDispatchedAtEpochMs() > 0L ? job.postRequestDispatchedAtEpochMs() : job.updatedAtEpochMs();
                    latest = Math.max(latest, sentAt);
                }
            }
        }
        return latest;
    }

    public ScheduledTweetListResponse listJobs(String accountId, String username) {
        validateSafeId(accountId, "accountId");
        long nowMs = clock.millis();
        List<ScheduledTweetJob> items;
        int pendingCount = 0;
        int overdueConfirmCount = 0;
        long latestRevokedGrantMs = 0L;
        synchronized (accountLock(accountId)) {
            QueueStorage queue = loadQueueLocked(accountId, username);
            if (promoteOverdueLocked(queue, nowMs)) {
                saveQueueLocked(queue);
            }
            items = new ArrayList<>(queue.jobs());
            items.sort(Comparator.comparingLong(ScheduledTweetJob::createdAtEpochMs).reversed());
            for (ScheduledTweetJob job : items) {
                if (job.status() == ScheduledTweetStatus.SCHEDULED
                        || job.status() == ScheduledTweetStatus.RESEND_QUEUED
                        || job.status() == ScheduledTweetStatus.SENDING) {
                    pendingCount++;
                } else if (job.status() == ScheduledTweetStatus.OVERDUE_CONFIRM_REQUIRED) {
                    overdueConfirmCount++;
                }
                if ((job.status() == ScheduledTweetStatus.FAILED
                        || job.status() == ScheduledTweetStatus.OVERDUE_CONFIRM_REQUIRED)
                        && XOAuthService.isRejectedGrantMessage(job.lastError())) {
                    latestRevokedGrantMs = Math.max(latestRevokedGrantMs, job.updatedAtEpochMs());
                }
            }
        }
        if (latestRevokedGrantMs > 0L) {
            oauthService.evictStaleTokenBefore(accountId, latestRevokedGrantMs);
        }
        XOAuthService.TokenCapability cap = oauthService.getTokenCapability(accountId);
        CommunityHubService.ScheduledPublishAccess access = evaluateAccess(accountId, username);
        return new ScheduledTweetListResponse(
                List.copyOf(items),
                pendingCount,
                overdueConfirmCount,
                cap.writeAuthorized(),
                cap.mediaWriteAuthorized(),
                nowMs,
                properties.getTimezone(),
                access.allowed(),
                access.followsAuthor(),
                access.authorInteractionRank(),
                access.reason(),
                cap.offlineRefreshable()
        );
    }

    public ScheduledTweetJob createScheduledTweet(
            String accountId,
            String username,
            String text,
            long scheduledAtEpochMs,
            String clientRequestId,
            List<MediaUploadInput> uploads
    ) {
        return createScheduledTweet(accountId,username,text,scheduledAtEpochMs,clientRequestId,uploads,null);
    }

    public ScheduledTweetJob createScheduledTweet(
            String accountId,
            String username,
            String text,
            long scheduledAtEpochMs,
            String clientRequestId,
            List<MediaUploadInput> uploads, AssistantSource source
    ) {
        return createScheduledTweet(accountId, username, text, scheduledAtEpochMs, clientRequestId, uploads, source, null, null);
    }

    public ScheduledTweetJob createScheduledTweet(
            String accountId,
            String username,
            String text,
            long scheduledAtEpochMs,
            String clientRequestId,
            List<MediaUploadInput> uploads, AssistantSource source,
            String quoteTweetId, String quoteTweetUrl
    ) {
        validateSafeId(accountId, "accountId");
        if (username == null || username.isBlank()) {
            throw new XDataProviderException(401, "缺少当前登录用户名，请重新登录");
        }
        boolean accountOwner = requireScheduledPublishEligible(accountId, username);
        String rawText = text == null ? "" : text;
        String trimmedText = rawText.trim();
        if (rawText.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("推文正文长度不能超过 " + MAX_TEXT_LENGTH + " 个字符");
        }
        List<MediaUploadInput> mediaInputs = uploads == null ? List.of() : uploads;
        if (trimmedText.isEmpty() && mediaInputs.isEmpty()) {
            throw new IllegalArgumentException("请输入推文内容或至少添加 1 张图片");
        }
        String effectiveQuoteId = normalizeQuoteTweetId(
                (quoteTweetId != null && !quoteTweetId.isBlank()) ? quoteTweetId : (source != null ? source.quoteTweetId() : null));
        String effectiveQuoteUrl = normalizeQuoteTweetUrl(quoteTweetUrl, effectiveQuoteId);

        List<DetectedImageFormat> detectedFormats = validateMediaUploads(mediaInputs);
        XOAuthService.TokenCapability cap = oauthService.getTokenCapability(accountId);
        if (!cap.writeAuthorized()) {
            throw new XDataProviderException(403, "当前账号尚未开通定时发布二次授权，请先点击「前往 X 二次授权」开通权限");
        }
        if (!mediaInputs.isEmpty() && !cap.mediaWriteAuthorized()) {
            throw new XDataProviderException(403, "当前账号尚未授予图片上传权限（media.write），请先点击「前往 X 二次授权」补充授权");
        }

        String normalizedReqId = clientRequestId == null ? "" : clientRequestId.trim();
        if (normalizedReqId.length() > 128) throw new IllegalArgumentException("请求标识不能超过 128 个字符");
        List<String> mediaShaList = new ArrayList<>(mediaInputs.size());
        for (MediaUploadInput input : mediaInputs) {
            mediaShaList.add(sha256Hex(input.bytes()));
        }
        String fingerprint = computeContentFingerprint(trimmedText, mediaShaList, effectiveQuoteId);
        checkDeliveryBeforeEnqueue(accountId, normalizedReqId, source, effectiveQuoteId, trimmedText, mediaShaList);

        synchronized (accountLock(accountId)) {
            QueueStorage queue = loadQueueLocked(accountId, username);
            long nowMs = clock.millis();
            boolean overduePromoted = promoteOverdueLocked(queue, nowMs);
            if (!normalizedReqId.isEmpty()) {
                checkAssistantReplay(queue,normalizedReqId,source);
                String existingJobId = queue.clientRequestMap().get(normalizedReqId);
                if(source!=null && existingJobId==null) {
                    for(var job:queue.jobs()) if(normalizedReqId.equals(job.clientRequestId())) return job;
                }
                if (existingJobId != null) {
                    ScheduledTweetJob existing = findJob(queue, existingJobId);
                    if (existing != null) {
                        if (overduePromoted) {
                            saveQueueLocked(queue);
                        }
                        return existing;
                    }
                }
            }

            if (scheduledAtEpochMs < nowMs + MIN_SCHEDULE_LEAD_MS) {
                throw new IllegalArgumentException("定时发布时间需晚于当前时间至少 15 秒");
            }
            assertActiveQueueCapacityLocked(queue, 1, accountOwner);
            long mediaBytes = mediaInputs.stream().mapToLong(input -> input.bytes().length).sum();
            assertStoredQueueCapacityLocked(queue, 1, mediaBytes, accountOwner);
            if (!accountOwner
                    && localDateStringFor(scheduledAtEpochMs).equals(localDateStringFor(nowMs))) {
                assertDailyPublishQuotaAvailableLocked(queue, nowMs, 1, accountOwner);
            }

            for (ScheduledTweetJob active : queue.jobs()) {
                if (isActiveStatus(active.status())
                        && fingerprint.equals(active.contentFingerprint())
                        && Math.abs(active.scheduledAtEpochMs() - scheduledAtEpochMs) < DUPLICATE_WINDOW_MS) {
                    throw new XDataProviderException(409, "已存在相同内容与相近时间的待发定时推文，已阻止重复创建");
                }
            }
            assertMinOneHourIntervalLocked(queue, scheduledAtEpochMs, null, "", accountOwner);

            String jobId = UUID.randomUUID().toString();
            List<ScheduledMediaAsset> assets = new ArrayList<>(mediaInputs.size());
            reserveMediaSpace(mediaBytes);
            try {
                for (int i = 0; i < mediaInputs.size(); i++) {
                    MediaUploadInput input = mediaInputs.get(i);
                    DetectedImageFormat fmt = detectedFormats.get(i);
                    String assetId = UUID.randomUUID().toString();
                    String safeName = sanitizeFileName(input.fileName(), "image-" + (i + 1) + fmt.extension());
                    Path target = resolveMediaAssetPath(accountId, jobId, assetId, fmt.extension());
                    Files.createDirectories(target.getParent());
                    Files.write(target, input.bytes());
                    assets.add(new ScheduledMediaAsset(
                            assetId,
                            safeName,
                            fmt.mimeType(),
                            input.bytes().length,
                            mediaShaList.get(i),
                            null,
                            0L
                    ));
                }

                ScheduledTweetJob job = new ScheduledTweetJob(
                        jobId,
                        accountId,
                        username,
                        rawText,
                        assets,
                        scheduledAtEpochMs,
                        ScheduledTweetStatus.SCHEDULED,
                        OverdueReason.NONE,
                        false,
                        false,
                        null,
                        normalizedReqId.isEmpty() ? null : normalizedReqId,
                        fingerprint,
                        0,
                        nowMs,
                        nowMs,
                        0L,
                        0L,
                        0L,
                        null,
                        null,
                        null, source, effectiveQuoteId, effectiveQuoteUrl
                );
                queue.jobs().add(job);
                if (!normalizedReqId.isEmpty()) {
                    recordClientRequestId(queue.clientRequestMap(), normalizedReqId, jobId);
                }
                saveQueueLocked(queue);
                return job;
            } catch (RuntimeException ex) {
                deleteDirectoryQuietly(resolveJobMediaDir(accountId, jobId));
                throw ex;
            } catch (Exception ex) {
                deleteDirectoryQuietly(resolveJobMediaDir(accountId, jobId));
                throw new IllegalStateException("保存定时推文媒体文件失败：" + ex.getMessage(), ex);
            } finally {
                releaseMediaSpace(mediaBytes);
            }
        }
    }

    private record PreparedDraftItem(
            String rawText,
            String trimmedText,
            long scheduledAtEpochMs,
            String clientRequestId,
            List<MediaUploadInput> mediaInputs,
            List<DetectedImageFormat> detectedFormats,
            List<String> mediaShaList,
            String fingerprint,
            String quoteTweetId,
            String quoteTweetUrl
    ) {
    }

    public BatchCreateResponse createBatchScheduledTweets(
            String accountId,
            String username,
            List<BatchTweetDraftInput> drafts
    ) {
        validateSafeId(accountId, "accountId");
        if (username == null || username.isBlank()) {
            throw new XDataProviderException(401, "缺少当前登录用户名，请重新登录");
        }
        if (drafts == null || drafts.isEmpty()) {
            throw new IllegalArgumentException("请至少添加 1 条定时推文");
        }
        if (drafts.size() > MAX_BATCH_CREATE_ITEMS) {
            throw new IllegalArgumentException("单次最多同时预设 " + MAX_BATCH_CREATE_ITEMS + " 条定时推文");
        }
        boolean accountOwner = requireScheduledPublishEligible(accountId, username);

        boolean anyHasMedia = false;
        List<PreparedDraftItem> prepared = new ArrayList<>(drafts.size());
        Set<String> seenReqIds = new HashSet<>();
        for (int i = 0; i < drafts.size(); i++) {
            BatchTweetDraftInput d = drafts.get(i);
            if (d == null) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 条定时推文参数为空");
            }
            String rawText = d.text() == null ? "" : d.text();
            String trimmedText = rawText.trim();
            if (rawText.length() > MAX_TEXT_LENGTH) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 条推文正文长度不能超过 " + MAX_TEXT_LENGTH + " 个字符");
            }
            List<MediaUploadInput> mediaInputs = d.uploads() == null ? List.of() : d.uploads();
            if (trimmedText.isEmpty() && mediaInputs.isEmpty()) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 条推文请输入正文或至少添加 1 张图片");
            }
            if (!mediaInputs.isEmpty()) {
                anyHasMedia = true;
            }
            List<DetectedImageFormat> detectedFormats = validateMediaUploads(mediaInputs);
            String reqId = d.clientRequestId() == null ? "" : d.clientRequestId().trim();
            if (reqId.length() > 128) throw new IllegalArgumentException("请求标识不能超过 128 个字符");
            if (!reqId.isEmpty() && !seenReqIds.add(reqId)) {
                throw new IllegalArgumentException("批量提交中包含重复的请求标识");
            }
            List<String> mediaShaList = new ArrayList<>(mediaInputs.size());
            for (MediaUploadInput input : mediaInputs) {
                mediaShaList.add(sha256Hex(input.bytes()));
            }
            String effectiveQuoteId = normalizeQuoteTweetId(
                    (d.quoteTweetId() != null && !d.quoteTweetId().isBlank())
                            ? d.quoteTweetId()
                            : (d.assistantSource() != null ? d.assistantSource().quoteTweetId() : null));
            String effectiveQuoteUrl = normalizeQuoteTweetUrl(d.quoteTweetUrl(), effectiveQuoteId);
            String fingerprint = computeContentFingerprint(trimmedText, mediaShaList, effectiveQuoteId);
            prepared.add(new PreparedDraftItem(
                    rawText,
                    trimmedText,
                    d.scheduledAtEpochMs(),
                    reqId,
                    mediaInputs,
                    detectedFormats,
                    mediaShaList,
                    fingerprint,
                    effectiveQuoteId,
                    effectiveQuoteUrl
            ));
        }

        XOAuthService.TokenCapability cap = oauthService.getTokenCapability(accountId);
        if (!cap.writeAuthorized()) {
            throw new XDataProviderException(403, "当前账号尚未开通定时发布二次授权，请先点击「前往 X 授权」开通权限");
        }
        if (anyHasMedia && !cap.mediaWriteAuthorized()) {
            throw new XDataProviderException(403, "当前账号尚未授予图片上传权限（media.write），请先点击「前往 X 授权」补充授权");
        }

        for (int i = 0; i < prepared.size(); i++) {
            var item = prepared.get(i);
            checkDeliveryBeforeEnqueue(accountId, item.clientRequestId(), drafts.get(i).assistantSource(),
                    item.quoteTweetId(), item.trimmedText(), item.mediaShaList());
        }

        synchronized (accountLock(accountId)) {
            QueueStorage queue = loadQueueLocked(accountId, username);
            long nowMs = clock.millis();
            boolean overduePromoted = promoteOverdueLocked(queue, nowMs);

            // 整批幂等重放：若全部 clientRequestId 均已存在，直接返回原已创建的记录列表
            List<ScheduledTweetJob> idempotentHits = new ArrayList<>(prepared.size());
            int hitCount = 0;
            for (int index=0; index<prepared.size(); index++) {
                var item=prepared.get(index);
                var source=drafts.get(index).assistantSource();
                if (!item.clientRequestId().isEmpty()) {
                    checkAssistantReplay(queue,item.clientRequestId(),source);
                    String existingId = queue.clientRequestMap().get(item.clientRequestId());
                    if(existingId==null && source!=null) {
                        for(var job:queue.jobs()) if(item.clientRequestId().equals(job.clientRequestId())) existingId=job.id();
                    }
                    if (existingId != null) {
                        ScheduledTweetJob existingJob = findJob(queue, existingId);
                        if (existingJob != null) {
                            idempotentHits.add(existingJob);
                            hitCount++;
                        }
                    }
                }
            }
            if (hitCount == prepared.size()) {
                if (overduePromoted) {
                    saveQueueLocked(queue);
                }
                return new BatchCreateResponse(List.copyOf(idempotentHits), idempotentHits.size());
            }
            if (hitCount > 0) {
                throw new XDataProviderException(409, "批次中包含已提交过的请求记录，请刷新队列后重试");
            }
            for (int i = 0; i < prepared.size(); i++) {
                var item = prepared.get(i);
                for (int j = 0; j < i; j++) {
                    var other = prepared.get(j);
                    long gap = Math.abs(item.scheduledAtEpochMs() - other.scheduledAtEpochMs());
                    if (gap < DUPLICATE_WINDOW_MS && item.fingerprint().equals(other.fingerprint())) {
                        throw new XDataProviderException(409, "第 " + (i + 1) + " 条与第 " + (j + 1) + " 条内容相同且时间相近，已阻止重复创建");
                    }
                    if (!accountOwner && gap < MIN_TWEET_INTERVAL_MS) {
                        throw new XDataProviderException(409, "第 " + (j + 1) + " 条与第 " + (i + 1) + " 条定时推文间隔不足 1 小时，每条定时推文之间必须至少间隔 1 小时");
                    }
                }
            }
            assertActiveQueueCapacityLocked(queue, prepared.size(), accountOwner);
            long mediaBytes = prepared.stream().flatMap(item -> item.mediaInputs().stream())
                    .mapToLong(input -> input.bytes().length).sum();
            assertStoredQueueCapacityLocked(queue, prepared.size(), mediaBytes, accountOwner);
            String todayDate = localDateStringFor(nowMs);
            int scheduledForToday = 0;
            for (PreparedDraftItem item : prepared) {
                if (todayDate.equals(localDateStringFor(item.scheduledAtEpochMs()))) {
                    scheduledForToday++;
                }
            }
            if (scheduledForToday > 0) {
                assertDailyPublishQuotaAvailableLocked(queue, nowMs, scheduledForToday, accountOwner);
            }

            for (int i = 0; i < prepared.size(); i++) {
                PreparedDraftItem item = prepared.get(i);
                String prefix = prepared.size() > 1 ? ("第 " + (i + 1) + " 条") : "";
                if (item.scheduledAtEpochMs() < nowMs + MIN_SCHEDULE_LEAD_MS) {
                    throw new IllegalArgumentException(
                            (prefix.isEmpty() ? "" : prefix) + "定时发布时间需晚于当前时间至少 15 秒"
                    );
                }
                for (ScheduledTweetJob active : queue.jobs()) {
                    if (isActiveStatus(active.status())
                            && item.fingerprint().equals(active.contentFingerprint())
                            && Math.abs(active.scheduledAtEpochMs() - item.scheduledAtEpochMs()) < DUPLICATE_WINDOW_MS) {
                        throw new XDataProviderException(409, "已存在相同内容与相近时间的待发定时推文，已阻止重复创建");
                    }
                }
                assertMinOneHourIntervalLocked(queue, item.scheduledAtEpochMs(), null, prefix, accountOwner);
            }

            List<Path> createdDirs = new ArrayList<>(prepared.size());
            List<ScheduledTweetJob> createdJobs = new ArrayList<>(prepared.size());
            reserveMediaSpace(mediaBytes);
            try {
                for (int idx = 0; idx < prepared.size(); idx++) {
                    PreparedDraftItem item = prepared.get(idx);
                    String jobId = UUID.randomUUID().toString();
                    createdDirs.add(resolveJobMediaDir(accountId, jobId));
                    List<ScheduledMediaAsset> assets = new ArrayList<>(item.mediaInputs().size());
                    for (int m = 0; m < item.mediaInputs().size(); m++) {
                        MediaUploadInput input = item.mediaInputs().get(m);
                        DetectedImageFormat fmt = item.detectedFormats().get(m);
                        String assetId = UUID.randomUUID().toString();
                        String safeName = sanitizeFileName(input.fileName(), "image-" + (m + 1) + fmt.extension());
                        Path target = resolveMediaAssetPath(accountId, jobId, assetId, fmt.extension());
                        Files.createDirectories(target.getParent());
                        Files.write(target, input.bytes());
                        assets.add(new ScheduledMediaAsset(
                                assetId,
                                safeName,
                                fmt.mimeType(),
                                input.bytes().length,
                                item.mediaShaList().get(m),
                                null,
                                0L
                        ));
                    }
                    long itemCreatedAt = nowMs + idx;
                    ScheduledTweetJob job = new ScheduledTweetJob(
                            jobId,
                            accountId,
                            username,
                            item.rawText(),
                            assets,
                            item.scheduledAtEpochMs(),
                            ScheduledTweetStatus.SCHEDULED,
                            OverdueReason.NONE,
                            false,
                            false,
                            null,
                            item.clientRequestId().isEmpty() ? null : item.clientRequestId(),
                            item.fingerprint(),
                            0,
                            itemCreatedAt,
                            itemCreatedAt,
                            0L,
                            0L,
                            0L,
                            null,
                            null,
                            null, drafts.get(idx).assistantSource(),
                            item.quoteTweetId(),
                            item.quoteTweetUrl()
                    );
                    createdJobs.add(job);
                }

                queue.jobs().addAll(createdJobs);
                for (ScheduledTweetJob job : createdJobs) {
                    if (job.clientRequestId() != null && !job.clientRequestId().isBlank()) {
                        recordClientRequestId(queue.clientRequestMap(), job.clientRequestId(), job.id());
                    }
                }
                saveQueueLocked(queue);
                return new BatchCreateResponse(List.copyOf(createdJobs), createdJobs.size());
            } catch (RuntimeException ex) {
                for (Path dir : createdDirs) {
                    deleteDirectoryQuietly(dir);
                }
                throw ex;
            } catch (Exception ex) {
                for (Path dir : createdDirs) {
                    deleteDirectoryQuietly(dir);
                }
                throw new IllegalStateException("保存批量定时推文媒体文件失败：" + ex.getMessage(), ex);
            } finally {
                releaseMediaSpace(mediaBytes);
            }
        }
    }

    public ScheduledTweetJob confirmAndResend(String accountId, String jobId, String confirmToken) {
        return confirmAndResend(accountId, "", jobId, confirmToken);
    }

    /** Register the owner's timeline check without submitting a post or holding a database transaction. */
    public AssistantQueueEntry registerDelivery(String accountId, String jobId, int attempt, String tweetId) {
        validateSafeId(accountId, "accountId");
        validateSafeId(jobId, "jobId");
        synchronized (accountLock(accountId)) {
            QueueStorage queue = loadQueueLocked(accountId, "");
            int index = findJobIndex(queue, jobId);
            var archived = index < 0 ? queue.assistantReceipts().entrySet().stream()
                    .filter(entry -> jobId.equals(entry.getValue().id())).findFirst().orElse(null) : null;
            if (index < 0 && archived == null) return null; // Older deleted ordinary jobs survive only in the delivery guard.
            ScheduledTweetJob job = index >= 0 ? queue.jobs().get(index) : archived.getValue();
            if (job.attemptCount() != attempt) throw new XDataProviderException(409, "排期发送次数已更新，请刷新后核实");
            if (job.overdueReason() == OverdueReason.AUTHOR_VERIFIED && !job.uncertainDelivery()
                    && (job.status() == ScheduledTweetStatus.PUBLISHED || job.status() == ScheduledTweetStatus.FAILED || job.status() == ScheduledTweetStatus.CANCELLED)
                    && Objects.equals(job.publishedTweetId(), tweetId)) return new AssistantQueueEntry(job, index < 0);
            if (!job.uncertainDelivery() || (job.status() != ScheduledTweetStatus.OVERDUE_CONFIRM_REQUIRED && job.status() != ScheduledTweetStatus.CANCELLED))
                throw new XDataProviderException(409, "该排期当前无需核实，请刷新");
            ScheduledTweetJob updated = job.registerDelivery(tweetId, UUID.randomUUID().toString(), clock.millis());
            if (index >= 0) queue.jobs().set(index, updated);
            else queue.assistantReceipts().put(archived.getKey(), updated);
            saveQueueLocked(queue);
            return new AssistantQueueEntry(updated, index < 0);
        }
    }

    public ScheduledTweetJob confirmAndResend(String accountId, String username, String jobId, String confirmToken) {
        validateSafeId(accountId, "accountId");
        validateSafeId(jobId, "jobId");
        boolean accountOwner = requireScheduledPublishEligible(accountId, username);
        if (assistantStore != null) {
            ScheduledTweetJob pending;
            synchronized (accountLock(accountId)) { pending = findJob(loadQueueLocked(accountId, ""), jobId); }
            if (pending != null && pending.attemptCount() > 0) reconcileDelivery(pending);
        }
        long nowMs = clock.millis();
        ScheduledTweetJob updated;
        synchronized (accountLock(accountId)) {
            QueueStorage queue = loadQueueLocked(accountId, "");
            promoteOverdueLocked(queue, nowMs);
            int idx = findJobIndex(queue, jobId);
            if (idx < 0) {
                throw new XDataProviderException(404, "未找到对应的定时推文记录");
            }
            ScheduledTweetJob current = queue.jobs().get(idx);
            if (current.status() == ScheduledTweetStatus.PUBLISHED || current.publishedTweetId() != null) {
                throw new XDataProviderException(409, "该推文已发布成功，系统已拦截重复发送");
            }
            if (current.status() == ScheduledTweetStatus.SENDING || current.status() == ScheduledTweetStatus.RESEND_QUEUED) {
                return current;
            }
            if (current.status() != ScheduledTweetStatus.OVERDUE_CONFIRM_REQUIRED
                    && current.status() != ScheduledTweetStatus.FAILED) {
                throw new XDataProviderException(409, "当前推文状态无需二次确认（" + current.status() + "）");
            }
            if (confirmToken == null || confirmToken.isBlank() || !confirmToken.equals(current.confirmToken())) {
                throw new XDataProviderException(409, "二次确认凭证已更新或已在其他页面确认，请刷新后查看");
            }
            assertDailyPublishQuotaAvailableLocked(queue, nowMs, 1, accountOwner);
            if (!isActiveStatus(current.status())) assertActiveQueueCapacityLocked(queue, 1, accountOwner);
            updated = current.markResendQueued(nowMs);
            queue.jobs().set(idx, updated);
            saveQueueLocked(queue);
        }
        triggerAsyncDispatch(accountId);
        return updated;
    }

    public ScheduledTweetListResponse batchConfirmOverdue(
            String accountId,
            String username,
            List<BatchConfirmItem> items
    ) {
        validateSafeId(accountId, "accountId");
        boolean accountOwner = requireScheduledPublishEligible(accountId, username);
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("请选择需要批量确认补发的逾期推文");
        }
        long nowMs = clock.millis();
        boolean anyQueued = false;
        synchronized (accountLock(accountId)) {
            QueueStorage queue = loadQueueLocked(accountId, username);
            boolean promoted = promoteOverdueLocked(queue, nowMs);
            List<Integer> targetIndices = new ArrayList<>();
            for (BatchConfirmItem reqItem : items) {
                if (reqItem == null || reqItem.id() == null || reqItem.id().isBlank()) {
                    throw new IllegalArgumentException("批量确认条目缺少推文 ID");
                }
                validateSafeId(reqItem.id(), "jobId");
                int idx = findJobIndex(queue, reqItem.id());
                if (idx < 0) {
                    throw new XDataProviderException(404, "未找到对应的定时推文记录：" + reqItem.id());
                }
                ScheduledTweetJob job = queue.jobs().get(idx);
                if (job.status() == ScheduledTweetStatus.PUBLISHED
                        || job.status() == ScheduledTweetStatus.SENDING
                        || job.status() == ScheduledTweetStatus.RESEND_QUEUED) {
                    continue;
                }
                if (job.uncertainDelivery()) {
                    throw new XDataProviderException(400, "存在状态存疑的推文，请逐条核对 X 主页后单独确认补发");
                }
                if (job.status() != ScheduledTweetStatus.OVERDUE_CONFIRM_REQUIRED) {
                    throw new XDataProviderException(409, "仅逾期待确认状态的推文支持批量补发：" + job.id());
                }
                if (reqItem.confirmToken() == null || !reqItem.confirmToken().equals(job.confirmToken())) {
                    throw new XDataProviderException(409, "二次确认凭证已更新或已在其他页面确认，请刷新后查看");
                }
                if (!targetIndices.contains(idx)) {
                    targetIndices.add(idx);
                }
            }
            if (!targetIndices.isEmpty()) {
                assertDailyPublishQuotaAvailableLocked(queue, nowMs, targetIndices.size(), accountOwner);
            }
            for (int idx : targetIndices) {
                ScheduledTweetJob job = queue.jobs().get(idx);
                queue.jobs().set(idx, job.markResendQueued(nowMs));
                anyQueued = true;
            }
            if (promoted || anyQueued) {
                saveQueueLocked(queue);
            }
        }
        if (anyQueued) {
            triggerAsyncDispatch(accountId);
        }
        return listJobs(accountId, username);
    }

    public ScheduledTweetJob cancelJob(String accountId, String jobId) {
        validateSafeId(accountId, "accountId");
        validateSafeId(jobId, "jobId");
        long nowMs = clock.millis();
        synchronized (accountLock(accountId)) {
            QueueStorage queue = loadQueueLocked(accountId, "");
            int idx = findJobIndex(queue, jobId);
            if (idx < 0) {
                throw new XDataProviderException(404, "未找到对应的定时推文记录");
            }
            ScheduledTweetJob current = queue.jobs().get(idx);
            if (current.status() == ScheduledTweetStatus.PUBLISHED || current.publishedTweetId() != null) {
                throw new XDataProviderException(409, "已发布的推文不可取消");
            }
            if (current.status() == ScheduledTweetStatus.SENDING || current.status() == ScheduledTweetStatus.RESEND_QUEUED) {
                throw new XDataProviderException(409, "推文正在发送中，暂不可取消");
            }
            if (current.status() == ScheduledTweetStatus.CANCELLED) {
                return current;
            }
            ScheduledTweetJob cancelled = current.markCancelled(nowMs);
            queue.jobs().set(idx, cancelled);
            saveQueueLocked(queue);
            return cancelled;
        }
    }

    public ScheduledTweetJob rescheduleJob(String accountId, String jobId, long scheduledAtEpochMs, String text) {
        return rescheduleJob(accountId, "", jobId, scheduledAtEpochMs, text);
    }

    public ScheduledTweetJob rescheduleJob(
            String accountId,
            String username,
            String jobId,
            long scheduledAtEpochMs,
            String text
    ) {
        validateSafeId(accountId, "accountId");
        validateSafeId(jobId, "jobId");
        boolean accountOwner = requireScheduledPublishEligible(accountId, username);
        synchronized (accountLock(accountId)) {
            return rescheduleLocked(loadQueueLocked(accountId, username), jobId, scheduledAtEpochMs, text, null, null, accountOwner);
        }
    }

    private ScheduledTweetJob rescheduleLocked(QueueStorage queue, String jobId, long scheduledAtEpochMs,
                                               String text, AssistantSource source, String sourceUrl, boolean accountOwner) {
        long nowMs = clock.millis();
        if (scheduledAtEpochMs < nowMs + MIN_SCHEDULE_LEAD_MS) {
            throw new IllegalArgumentException("定时发布时间需晚于当前时间至少 15 秒");
        }
        promoteOverdueLocked(queue, nowMs);
        int idx = findJobIndex(queue, jobId);
        if (idx < 0) {
            throw new XDataProviderException(404, "未找到对应的定时推文记录");
        }
        ScheduledTweetJob current = queue.jobs().get(idx);
        if (current.uncertainDelivery()) {
            throw new XDataProviderException(409, "发布结果待核对，请先查看 X 主页，确认未发出后再使用逐条确认补发");
        }
        if (current.status() == ScheduledTweetStatus.PUBLISHED || current.publishedTweetId() != null) {
            throw new XDataProviderException(409, "已发布的推文不可修改");
        }
        if (current.status() == ScheduledTweetStatus.SENDING || current.status() == ScheduledTweetStatus.RESEND_QUEUED) {
            throw new XDataProviderException(409, "推文正在发送中，暂不可修改");
        }

        String nextText = text != null ? text : current.text();
        if (nextText.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("推文正文长度不能超过 " + MAX_TEXT_LENGTH + " 个字符");
        }
        if (nextText.trim().isEmpty() && current.media().isEmpty()) {
            throw new IllegalArgumentException("推文内容与图片不可同时为空");
        }

        List<String> shaList = current.media().stream().map(ScheduledMediaAsset::sha256).toList();
        String nextQuoteId = source == null ? current.quoteTweetId() : normalizeQuoteTweetId(source.quoteTweetId());
        String nextFingerprint = computeContentFingerprint(nextText.trim(), shaList, nextQuoteId);
        for (ScheduledTweetJob other : queue.jobs()) {
            if (!other.id().equals(jobId)
                    && isActiveStatus(other.status())
                    && nextFingerprint.equals(other.contentFingerprint())
                    && Math.abs(other.scheduledAtEpochMs() - scheduledAtEpochMs) < DUPLICATE_WINDOW_MS) {
                throw new XDataProviderException(409, "已存在相同内容与相近时间的待发定时推文，已阻止重复改期");
            }
        }
        assertMinOneHourIntervalLocked(queue, scheduledAtEpochMs, jobId, "", accountOwner);

        String todayDate = localDateStringFor(nowMs);
        if (todayDate.equals(localDateStringFor(scheduledAtEpochMs))
                && (current.status() != ScheduledTweetStatus.SCHEDULED
                || !todayDate.equals(localDateStringFor(current.scheduledAtEpochMs())))) {
            // Reuse the create-time allowance check only when admitting a task into today.
            // Editing an existing task for today must not reserve or count it a second time.
            assertDailyPublishQuotaAvailableLocked(queue, nowMs, 1, accountOwner);
        }
        if (!isActiveStatus(current.status())) assertActiveQueueCapacityLocked(queue, 1, accountOwner);
        ScheduledTweetJob updated = current.reschedule(scheduledAtEpochMs, nextText, nextFingerprint, nowMs);
        if (source != null) {
            String quoteUrl = normalizeQuoteTweetUrl(sourceUrl == null ? current.quoteTweetUrl() : sourceUrl, nextQuoteId);
            updated = updated.withPublicationSource(source, nextQuoteId, quoteUrl, nextFingerprint);
        }
        queue.jobs().set(idx, updated);
        saveQueueLocked(queue);
        return updated;
    }

    public void deleteJob(String accountId, String jobId) {
        validateSafeId(accountId, "accountId");
        validateSafeId(jobId, "jobId");
        synchronized (accountLock(accountId)) {
            QueueStorage queue = loadQueueLocked(accountId, "");
            int idx = findJobIndex(queue, jobId);
            if (idx < 0) {
                throw new XDataProviderException(404, "未找到对应的定时推文记录");
            }
            ScheduledTweetJob current = queue.jobs().get(idx);
            if (current.status() == ScheduledTweetStatus.SENDING || current.status() == ScheduledTweetStatus.RESEND_QUEUED) {
                throw new XDataProviderException(409, "推文正在发送中，暂不可删除");
            }
            Path accountDir = resolveAccountDir(accountId);
            writeDurableJson(accountDir.resolve("scheduled_publish_budget_v1.json"),
                    new DailyPublishCounter(accountId, localDateStringFor(clock.millis()),
                            dailyPublishUsedLocked(queue, clock.millis())));
            if (current.assistantSource() != null) queue.assistantReceipts().put(current.clientRequestId(), current);
            else if (current.uncertainDelivery() || current.overdueReason() == OverdueReason.AUTHOR_VERIFIED)
                queue.assistantReceipts().put(current.id(), current);
            queue.jobs().remove(idx);
            saveQueueLocked(queue);
            deleteDirectoryQuietly(resolveJobMediaDir(accountId, jobId));
        }
    }

    private static void checkAssistantReplay(QueueStorage queue,String key,AssistantSource source) {
        if(queue.assistantReceipts().containsKey(key)) throw new XDataProviderException(409,"该发布意图已有删除回执");
        for(var job:queue.jobs()) {
            if(!key.equals(job.clientRequestId())) continue;
            var stored=job.assistantSource();
            if(source==null && stored==null) return;
            if(source==null || stored==null || !Objects.equals(source.requestDigest(),stored.requestDigest()))
                throw new XDataProviderException(409,"该发布意图与原请求不符");
            return;
        }
    }

    /** Read queue and durable deletion receipts together, without dispatching. */
    public record AssistantQueueEntry(ScheduledTweetJob job,boolean deleted) {}

    public List<AssistantQueueEntry> assistantJobs(String accountId) {
        validateSafeId(accountId,"accountId");
        synchronized(accountLock(accountId)) {
            var queue=loadQueueLocked(accountId,"");
            var records=new ArrayList<AssistantQueueEntry>();
            queue.assistantReceipts().values().forEach(j->records.add(new AssistantQueueEntry(j,true)));
            queue.jobs().stream().filter(j->j.assistantSource()!=null).forEach(j->records.add(new AssistantQueueEntry(j,false)));
            return records;
        }
    }

    public ScheduledTweetJob updateAssistantJob(String accountId,String username,String jobId,long expectedTime,
                                                String text,AssistantSource source) {
        return updateAssistantJob(accountId, username, jobId, expectedTime, text, source, null);
    }

    public ScheduledTweetJob updateAssistantJob(String accountId,String username,String jobId,long expectedTime,
                                                String text,AssistantSource source,String sourceUrl) {
        validateSafeId(accountId, "accountId");
        validateSafeId(jobId, "jobId");
        boolean accountOwner = requireScheduledPublishEligible(accountId, username);
        synchronized(accountLock(accountId)) {
            var queue=loadQueueLocked(accountId,username);
            var old=findJob(queue,jobId);
            if(old==null || old.assistantSource()==null || !source.intentId().equals(old.assistantSource().intentId()))
                throw new XDataProviderException(409,"排期来源已变更");
            if(Objects.equals(old.assistantSource().updateOperationId(),source.updateOperationId())) {
                if(old.assistantSource().equals(source) && old.text().equals(text)) return old;
                throw new XDataProviderException(409,"更新请求标识已用于其他版本");
            }
            if(old.status()!=ScheduledTweetStatus.SCHEDULED || old.updatedAtEpochMs()!=expectedTime)
                throw new XDataProviderException(409,"排期状态或版本已变化");
            return rescheduleLocked(queue, jobId, old.scheduledAtEpochMs(), text, source, sourceUrl, accountOwner);
        }
    }

    public MediaFileContent loadMediaAssetBytes(String accountId, String jobId, String assetId) {
        validateSafeId(accountId, "accountId");
        validateSafeId(jobId, "jobId");
        validateSafeId(assetId, "assetId");
        ScheduledMediaAsset found = null;
        synchronized (accountLock(accountId)) {
            QueueStorage queue = loadQueueLocked(accountId, "");
            ScheduledTweetJob job = findJob(queue, jobId);
            if (job == null) {
                throw new XDataProviderException(404, "未找到对应的定时推文记录");
            }
            for (ScheduledMediaAsset asset : job.media()) {
                if (assetId.equals(asset.assetId())) {
                    found = asset;
                    break;
                }
            }
        }
        if (found == null) {
            throw new XDataProviderException(404, "未找到对应的媒体附件");
        }
        Path file = resolveMediaAssetPath(accountId, jobId, assetId, extensionForMime(found.mimeType()));
        if (!Files.exists(file)) {
            throw new XDataProviderException(404, "媒体附件文件不存在或已被清理");
        }
        try {
            return new MediaFileContent(Files.readAllBytes(file), found.mimeType(), found.fileName());
        } catch (IOException ex) {
            throw new IllegalStateException("读取媒体附件失败：" + ex.getMessage(), ex);
        }
    }

    public void processDueJobsForAccount(String accountId) {
        validateSafeId(accountId, "accountId");
        ensureBootRecovery();
        while (!Thread.currentThread().isInterrupted()) {
            ScheduledTweetJob claimed = claimNextExecutableJob(accountId);
            if (claimed == null) {
                return;
            }
            dispatchClaimedJob(accountId, claimed);
        }
    }

    private void triggerAsyncDispatch(String accountId) {
        if (environment != null && !isScheduledPublishRuntimeEnabled()) {
            log.info("Blocked scheduled tweet background dispatch in local/non-production runtime for account {}", accountId);
            return;
        }
        if (!dispatchingAccounts.add(accountId)) return;
        try {
            dispatchExecutor.execute(() -> {
                try {
                    processDueJobsForAccount(accountId);
                } catch (Exception ex) {
                    log.warn("Async scheduled tweet dispatch failed for account {}: {}", accountId, ex.getMessage());
                } finally {
                    dispatchingAccounts.remove(accountId);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            dispatchingAccounts.remove(accountId);
            // The durable queued jobs remain available to the next scheduler tick.
            log.debug("Scheduled dispatch queue is busy; account {} will be retried by the next tick", accountId);
        }
    }

    private ScheduledTweetJob claimNextExecutableJob(String accountId) {
        long graceMs = Math.max(10_000L, properties.getScheduleGraceWindowSeconds() * 1000L);
        synchronized (accountLock(accountId)) {
            QueueStorage queue = loadQueueLocked(accountId, "");
            long nowMs = clock.millis();
            boolean changed = promoteOverdueLocked(queue, nowMs);
            if (queue.jobs().stream().anyMatch(job -> job.status() == ScheduledTweetStatus.SENDING)) {
                if (changed) saveQueueLocked(queue);
                return null;
            }
            int candidateIdx = -1;
            long bestSortTime = Long.MAX_VALUE;
            for (int i = 0; i < queue.jobs().size(); i++) {
                ScheduledTweetJob job = queue.jobs().get(i);
                if (job.publishedTweetId() != null) {
                    continue;
                }
                boolean dueScheduled = job.status() == ScheduledTweetStatus.SCHEDULED
                        && job.scheduledAtEpochMs() <= nowMs
                        && nowMs - job.scheduledAtEpochMs() <= graceMs;
                boolean confirmedResend = job.status() == ScheduledTweetStatus.RESEND_QUEUED;
                if (dueScheduled || confirmedResend) {
                    long rankTime = confirmedResend ? job.confirmedAtEpochMs() : job.scheduledAtEpochMs();
                    if (rankTime < bestSortTime) {
                        bestSortTime = rankTime;
                        candidateIdx = i;
                    }
                }
            }
            if (candidateIdx < 0) {
                if (changed) {
                    saveQueueLocked(queue);
                }
                return null;
            }
            ScheduledTweetJob claimed = queue.jobs().get(candidateIdx).markSending(nowMs);
            queue.jobs().set(candidateIdx, claimed);
            saveQueueLocked(queue);
            return claimed;
        }
    }

    private void dispatchClaimedJob(String accountId, ScheduledTweetJob claimed) {
        AtomicBoolean dispatchedToTweetEndpoint = new AtomicBoolean();
        String usedAccessToken = null;
        AcceptedDelivery accepted = null;
        try {
            CommunityHubService.ScheduledPublishAccess access = evaluateAccess(accountId, claimed.username());
            boolean accountOwner = owner(accountId, claimed.username());
            boolean assistantOwnerLost = claimed.assistantSource() != null && !accountOwner;
            if (!access.allowed() || assistantOwnerLost) {
                long failMs = clock.millis();
                synchronized (accountLock(accountId)) {
                    QueueStorage queue = loadQueueLocked(accountId, "");
                    int idx = findJobIndex(queue, claimed.id());
                    if (idx >= 0) {
                        ScheduledTweetJob latest = queue.jobs().get(idx);
                        if (latest.status() == ScheduledTweetStatus.SENDING && latest.publishedTweetId() == null) {
                            String reason = assistantOwnerLost ? "内容助手发布任务的作者身份已失效" : access.reason();
                            queue.jobs().set(idx, latest.markFailed(UUID.randomUUID().toString(), reason, failMs));
                            saveQueueLocked(queue);
                        }
                    }
                }
                return;
            }
            if (!accountOwner) {
                synchronized (accountLock(accountId)) {
                    QueueStorage quotaQueue = loadQueueLocked(accountId, "");
                    reserveDailyPublishQuotaLocked(quotaQueue, clock.millis());
                }
            }
            boolean hasMedia = !claimed.media().isEmpty();
            String effectiveCaller = accountOwner ? com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME : claimed.username();
            boolean bindPaidScope = communityHubService != null || accountOwner;
            String accessToken = bindPaidScope
                    ? com.xops.hub.provider.PaidCapabilityGate.withCaller(
                            effectiveCaller, accountId, accountOwner, () -> oauthService.getValidWriteAccessToken(accountId, hasMedia))
                    : oauthService.getValidWriteAccessToken(accountId, hasMedia);
            usedAccessToken = accessToken;

            List<ScheduledMediaAsset> workingAssets = new ArrayList<>(claimed.media());
            List<String> remoteMediaIds = new ArrayList<>(workingAssets.size());
            for (int i = 0; i < workingAssets.size(); i++) {
                ScheduledMediaAsset asset = workingAssets.get(i);
                long nowMs = clock.millis();
                boolean reusable = asset.remoteMediaId() != null
                        && !asset.remoteMediaId().isBlank()
                        && asset.remoteUploadedAtEpochMs() > 0
                        && (nowMs - asset.remoteUploadedAtEpochMs()) < MEDIA_REUSE_TTL_MS;
                String mediaId = asset.remoteMediaId();
                if (!reusable) {
                    byte[] bytes = readMediaBytesForUpload(accountId, claimed.id(), asset);
                    mediaId = bindPaidScope
                            ? com.xops.hub.provider.PaidCapabilityGate.withCaller(
                                    effectiveCaller, accountId, accountOwner,
                                    () -> publishClient.uploadMedia(accessToken, bytes, asset.mimeType(), asset.fileName()))
                            : publishClient.uploadMedia(accessToken, bytes, asset.mimeType(), asset.fileName());
                    ScheduledMediaAsset updatedAsset = asset.withRemoteMedia(mediaId, clock.millis());
                    workingAssets.set(i, updatedAsset);
                    checkpointMediaAsset(accountId, claimed.id(), workingAssets);
                }
                remoteMediaIds.add(mediaId);
            }

            String textToSend;
            String latestQuoteTweetId;
            String latestQuoteTweetUrl;
            synchronized (accountLock(accountId)) {
                QueueStorage queue = loadQueueLocked(accountId, "");
                int idx = findJobIndex(queue, claimed.id());
                if (idx < 0) {
                    return;
                }
                ScheduledTweetJob latest = queue.jobs().get(idx);
                if (latest.status() != ScheduledTweetStatus.SENDING || latest.publishedTweetId() != null) {
                    return;
                }
                textToSend = latest.text();
                latestQuoteTweetId = latest.quoteTweetId();
                latestQuoteTweetUrl = latest.quoteTweetUrl();
            }

            ResolvedQuoteSend resolvedSend = latestQuoteTweetId == null
                    ? new ResolvedQuoteSend(textToSend, null) : resolveEffectiveQuoteSend(textToSend, latestQuoteTweetId);
            ScheduledPublicationGuard.claim(assistantStore, claimed, resolvedSend.quoteTweetId(), resolvedSend.textToSend());
            String effectiveQuoteUrl = resolveEffectiveQuoteUrl(textToSend, resolvedSend.quoteTweetId(), latestQuoteTweetUrl);
            Supplier<String> create = () -> resolvedSend.quoteTweetId() != null
                    ? XPublishClient.withQuoteTweetUrl(effectiveQuoteUrl, () -> publishClient.createTweet(
                            accessToken, resolvedSend.textToSend(), remoteMediaIds, resolvedSend.quoteTweetId()))
                    : publishClient.createTweet(accessToken, resolvedSend.textToSend(), remoteMediaIds);
            var receipt = XPublishClient.withDispatchedPayload(resolvedSend.textToSend(), resolvedSend.quoteTweetId(),
                    payload -> {
                        try {
                            ScheduledPublicationGuard.beforeDispatch(assistantStore, claimed, resolvedSend.quoteTweetId(), resolvedSend.textToSend(), payload);
                        } catch (XDataProviderException conflict) {
                            throw new XPublishException(conflict.getStatusCode(), conflict.getMessage(), conflict, false);
                        }
                        checkpointTweetDispatch(accountId, claimed.id(), payload, dispatchedToTweetEndpoint);
                    },
                    () -> bindPaidScope ? com.xops.hub.provider.PaidCapabilityGate.withCaller(effectiveCaller, accountId, accountOwner, create) : create.get());
            dispatchedToTweetEndpoint.set(true);
            accepted = new AcceptedDelivery(claimed, receipt, clock.millis());
            synchronized (accountLock(accountId)) {
                Path file = resolveAccountDir(accountId).resolve("scheduled-receipts").resolve(claimed.id() + ".json");
                try {
                    writeDurableJson(file, accepted);
                } catch (RuntimeException journalFailure) {
                    // The queue can still be writable when a separate journal file cannot be created.
                    QueueStorage queue = loadQueueLocked(accountId, "");
                    applyAcceptedDelivery(queue, accepted);
                    saveQueueLocked(queue);
                    return;
                }
                loadQueueLocked(accountId, ""); // merges the journal and removes it after the durable queue write
            }
        } catch (Exception ex) {
            if (accepted != null) {
                // The platform supplied an ID. A local write error must never reopen the send or allow a negative decision.
                reconcileDelivery(accepted.published(claimed));
                log.warn("Could not finish saving the accepted scheduled post {}", claimed.id(), ex);
                return;
            }
            String invalidatedAuthMsg = null;
            if (ex instanceof XPublishException pex && pex.getStatusCode() == 401) {
                invalidatedAuthMsg = oauthService.invalidateRejectedWriteToken(accountId, usedAccessToken);
            }
            long failMs = clock.millis();
            boolean uncertain = ex instanceof XPublishException pex ? pex.isDeliveryUncertain() : dispatchedToTweetEndpoint.get();
            synchronized (accountLock(accountId)) {
                QueueStorage queue = loadQueueLocked(accountId, "");
                int idx = findJobIndex(queue, claimed.id());
                if (idx < 0) {
                    return;
                }
                ScheduledTweetJob latest = queue.jobs().get(idx);
                if (latest.status() == ScheduledTweetStatus.PUBLISHED || latest.publishedTweetId() != null) {
                    return;
                }
                String newToken = UUID.randomUUID().toString();
                String msg = (invalidatedAuthMsg != null && !invalidatedAuthMsg.isBlank())
                        ? invalidatedAuthMsg
                        : (ex.getMessage() != null && !ex.getMessage().isBlank()) ? ex.getMessage() : "定时推文发送失败";
                if (uncertain) {
                    queue.jobs().set(idx, latest.markOverdueConfirmRequired(
                            OverdueReason.UNCERTAIN_DELIVERY,
                            true,
                            newToken,
                            msg,
                            failMs
                    ));
                } else if (isTransientRetryablePublishError(ex, msg)
                        && latest.attemptCount() <= com.xops.hub.provider.PaidCapabilityGate.MAX_IDEMPOTENT_RETRIES) {
                    long retryAt = failMs + com.xops.hub.provider.PaidCapabilityGate.RETRY_INTERVAL_MS;
                    String retryNote = String.format("发送暂未成功（%s），已排入 10 分钟后幂等重试（第 %d/%d 次）",
                            msg, latest.attemptCount(), com.xops.hub.provider.PaidCapabilityGate.MAX_IDEMPOTENT_RETRIES);
                    queue.jobs().set(idx, latest.scheduleIdempotentRetry(retryAt, retryNote, failMs));
                } else {
                    queue.jobs().set(idx, latest.markFailed(newToken, msg, failMs));
                }
                saveQueueLocked(queue);
            }
        } finally {
            if (assistantStore != null) {
                ScheduledTweetJob finished;
                synchronized (accountLock(accountId)) {
                    finished = findJob(loadQueueLocked(accountId, ""), claimed.id());
                }
                if (finished != null) reconcileDelivery(finished);
            }
        }
    }

    private void reconcileDelivery(ScheduledTweetJob job) {
        ResolvedQuoteSend send = job.quoteTweetId() == null ? new ResolvedQuoteSend(job.text(), null)
                : resolveEffectiveQuoteSend(job.text(), job.quoteTweetId());
        var confirmed = ScheduledPublicationGuard.reconcile(assistantStore, job, send.quoteTweetId(), send.textToSend());
        if (confirmed == null) return;
        synchronized (accountLock(job.accountId())) {
            var queue = loadQueueLocked(job.accountId(), "");
            int index = findJobIndex(queue, job.id());
            if (index >= 0) queue.jobs().set(index, confirmed);
            else if (queue.assistantReceipts().containsKey(job.id())) queue.assistantReceipts().put(job.id(), confirmed);
            else return;
            saveQueueLocked(queue);
        }
    }

    /** Restore the send fence from the queue without dispatching a job or holding its lock during SQLite access. */
    public void reconcileOwnerDeliveryGuards(String accountId) {
        reconcileOwnerDeliveryGuards(accountId, false);
    }

    private void reconcileOwnerDeliveryGuards(String accountId, boolean restoreHistorical) {
        if (assistantStore == null || accountId == null || accountId.isBlank() || !assistantStore.isOwner(accountId)) return;
        Set<String> pending = assistantStore.pendingScheduledJobs(accountId);
        if (!restoreHistorical && pending.isEmpty()) return;
        List<ScheduledTweetJob> jobs;
        synchronized (accountLock(accountId)) {
            var queue = loadQueueLocked(accountId, "");
            jobs = new ArrayList<>(queue.jobs());
            jobs.addAll(queue.assistantReceipts().values());
        }
        jobs.stream().filter(job -> pending.contains(job.id()) || (restoreHistorical
                && (job.uncertainDelivery() || job.status() == ScheduledTweetStatus.SENDING || job.status() == ScheduledTweetStatus.PUBLISHED))).forEach(this::reconcileDelivery);
    }

    private void checkDeliveryBeforeEnqueue(String accountId, String requestId, AssistantSource source,
                                            String quoteId, String text, List<String> media) {
        if (assistantStore == null) return;
        if (!requestId.isEmpty()) {
            synchronized (accountLock(accountId)) {
                QueueStorage queue = loadQueueLocked(accountId, "");
                if (queue.jobs().stream().anyMatch(job -> requestId.equals(job.clientRequestId()))) return;
            }
        }
        // Existing intents are validated again under the queue lock. Never acquire SQLite while holding that lock.
        ScheduledPublicationGuard.checkEnqueue(assistantStore, accountId, source, quoteId, text, media);
    }

    private void checkpointTweetDispatch(String accountId, String jobId, PublicationPayload payload, AtomicBoolean dispatched) {
        synchronized (accountLock(accountId)) {
            QueueStorage queue = loadQueueLocked(accountId, "");
            int idx = findJobIndex(queue, jobId);
            if (idx < 0 || queue.jobs().get(idx).status() != ScheduledTweetStatus.SENDING) {
                throw new XPublishException(409, "任务已结束，本次请求已停止", false);
            }
            queue.jobs().set(idx, queue.jobs().get(idx).withPostRequestDispatched(payload, clock.millis()));
            saveQueueLocked(queue);
            dispatched.set(true);
        }
    }

    private static boolean isTransientRetryablePublishError(Exception ex, String message) {
        if (isPublishAuthExpiredMessage(message)) {
            return false;
        }
        if (ex instanceof XPublishException pex) {
            int code = pex.getStatusCode();
            return code == 429 || code == 500 || code == 502 || code == 503 || code == 504;
        }
        if (ex instanceof XDataProviderException xde) {
            int code = xde.getStatusCode();
            return code == 429 || code == 502 || code == 503 || code == 504;
        }
        return false;
    }

    private static boolean isPublishAuthExpiredMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return message.contains("二次授权")
                || message.contains("发推授权已过期")
                || message.contains("访问令牌已过期")
                || message.contains("授权范围已变更");
    }

    private String resolveUsernameForAccount(String accountId, String username) {
        if (username != null && !username.isBlank()) {
            return username;
        }
        synchronized (accountLock(accountId)) {
            QueueStorage queue = loadQueueLocked(accountId, "");
            return queue.username();
        }
    }

    private CommunityHubService.ScheduledPublishAccess evaluateAccess(String accountId, String username) {
        if (communityHubService == null) {
            return new CommunityHubService.ScheduledPublishAccess(true, true, null, null);
        }
        return communityHubService.evaluateScheduledPublishAccess(
                accountId,
                resolveUsernameForAccount(accountId, username)
        );
    }

    // Resolve assistant ownership before entering the queue lock. Assistant transactions also read this queue.
    private boolean requireScheduledPublishEligible(String accountId, String username) {
        String resolvedUsername = resolveUsernameForAccount(accountId, username);
        CommunityHubService.ScheduledPublishAccess access = evaluateAccess(accountId, resolvedUsername);
        if (!access.allowed()) {
            throw new XDataProviderException(403, access.reason());
        }
        return owner(accountId, resolvedUsername);
    }

    private void checkpointMediaAsset(String accountId, String jobId, List<ScheduledMediaAsset> assets) {
        long nowMs = clock.millis();
        synchronized (accountLock(accountId)) {
            QueueStorage queue = loadQueueLocked(accountId, "");
            int idx = findJobIndex(queue, jobId);
            if (idx < 0) {
                return;
            }
            queue.jobs().set(idx, queue.jobs().get(idx).withMedia(assets, nowMs));
            saveQueueLocked(queue);
        }
    }

    private byte[] readMediaBytesForUpload(String accountId, String jobId, ScheduledMediaAsset asset) {
        Path file = resolveMediaAssetPath(accountId, jobId, asset.assetId(), extensionForMime(asset.mimeType()));
        if (!Files.exists(file)) {
            throw new XPublishException(404, "本地暂存的媒体图片缺失：" + asset.fileName(), false);
        }
        try {
            long limit = "image/gif".equals(asset.mimeType()) ? MAX_GIF_BYTES : MAX_STATIC_IMAGE_BYTES;
            if (Files.size(file) != asset.sizeBytes() || asset.sizeBytes() <= 0 || asset.sizeBytes() > limit) {
                throw new IOException("媒体文件大小与保存记录不一致");
            }
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length != asset.sizeBytes() || !sha256Hex(bytes).equals(asset.sha256())) {
                throw new IOException("媒体文件校验失败");
            }
            return bytes;
        } catch (IOException ex) {
            throw new XPublishException(500, "读取本地暂存图片失败：" + ex.getMessage(), ex, false);
        }
    }

    private boolean promoteOverdueLocked(QueueStorage queue, long nowMs) {
        long graceMs = Math.max(10_000L, properties.getScheduleGraceWindowSeconds() * 1000L);
        boolean changed = false;
        for (int i = 0; i < queue.jobs().size(); i++) {
            ScheduledTweetJob job = queue.jobs().get(i);
            if (job.status() == ScheduledTweetStatus.SCHEDULED && nowMs - job.scheduledAtEpochMs() > graceMs) {
                queue.jobs().set(i, job.markOverdueConfirmRequired(
                        OverdueReason.MISSED_WINDOW,
                        false,
                        UUID.randomUUID().toString(),
                        "已错过原定发布时间窗口，二次确认后将立即自动补发",
                        nowMs
                ));
                changed = true;
            } else if (job.status() == ScheduledTweetStatus.RESEND_QUEUED
                    && job.confirmedAtEpochMs() > 0
                    && nowMs - job.confirmedAtEpochMs() > graceMs) {
                queue.jobs().set(i, job.markOverdueConfirmRequired(
                        OverdueReason.MISSED_WINDOW,
                        job.uncertainDelivery(),
                        UUID.randomUUID().toString(),
                        "二次确认后的补发窗口已过期，请重新确认后再补发",
                        nowMs
                ));
                changed = true;
            }
        }
        return changed;
    }

    private List<DetectedImageFormat> validateMediaUploads(List<MediaUploadInput> uploads) {
        if (uploads.size() > MAX_STATIC_IMAGES) {
            throw new IllegalArgumentException("单条推文最多附带 4 张静态图片");
        }
        List<DetectedImageFormat> result = new ArrayList<>(uploads.size());
        boolean hasGif = false;
        for (MediaUploadInput upload : uploads) {
            if (upload == null || upload.bytes() == null || upload.bytes().length == 0) {
                throw new IllegalArgumentException("存在空的图片文件，请重新选择");
            }
            DetectedImageFormat fmt = detectImageFormat(upload.bytes());
            if ("image/gif".equals(fmt.mimeType())) {
                hasGif = true;
            }
            if (upload.bytes().length > fmt.maxBytes()) {
                long maxMb = fmt.maxBytes() / (1024 * 1024);
                throw new IllegalArgumentException("图片体积超出限制（该格式单张最大 " + maxMb + "MB）");
            }
            result.add(fmt);
        }
        if (hasGif && uploads.size() > 1) {
            throw new IllegalArgumentException("GIF 动图每次仅支持上传 1 张，且不可与其他图片混发");
        }
        return result;
    }

    private static DetectedImageFormat detectImageFormat(byte[] b) {
        if (b.length >= 8
                && (b[0] & 0xFF) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return new DetectedImageFormat("image/png", ".png", MAX_STATIC_IMAGE_BYTES);
        }
        if (b.length >= 3
                && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return new DetectedImageFormat("image/jpeg", ".jpg", MAX_STATIC_IMAGE_BYTES);
        }
        if (b.length >= 6
                && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8'
                && (b[4] == '7' || b[4] == '9') && b[5] == 'a') {
            return new DetectedImageFormat("image/gif", ".gif", MAX_GIF_BYTES);
        }
        if (b.length >= 12
                && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return new DetectedImageFormat("image/webp", ".webp", MAX_STATIC_IMAGE_BYTES);
        }
        throw new IllegalArgumentException("仅支持 PNG、JPG、WEBP 或 GIF 格式图片");
    }

    private static String extensionForMime(String mimeType) {
        if (mimeType == null) {
            return ".png";
        }
        return switch (mimeType) {
            case "image/jpeg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> ".png";
        };
    }

    private QueueStorage loadQueueLocked(String accountId, String fallbackUsername) {
        Path file = resolveQueueFile(accountId);
        if (file == null) return QueueStorage.empty(accountId, fallbackUsername);
        try {
            if (!Files.exists(file)) {
                var empty = QueueStorage.empty(accountId, fallbackUsername);
                restoreAcceptedDeliveries(empty);
                return empty;
            }
            QueueStorage raw = objectMapper.readValue(file.toFile(), QueueStorage.class);
            if (raw == null || (raw.version() < 1 || raw.version() > QUEUE_VERSION) || !accountId.equals(raw.accountId())
                    || raw.jobs() == null || raw.jobs().stream().anyMatch(job -> job == null
                    || !accountId.equals(job.accountId()) || job.id() == null
                    || !SAFE_ID_PATTERN.matcher(job.id()).matches())) {
                throw new IOException("Invalid scheduled queue ownership or format");
            }
            QueueStorage queue = raw.mutableCopy(fallbackUsername);
            if (raw.version() == 1) {
                // v1 cancellation and manual rescheduling erased the outcome of earlier attempts.
                // An automatic retry retains its failure message and can still proceed normally.
                for (int i = 0; i < queue.jobs().size(); i++) {
                    ScheduledTweetJob job = queue.jobs().get(i);
                    boolean cancelled = job.status() == ScheduledTweetStatus.CANCELLED;
                    if (job.attemptCount() > 0 && job.publishedTweetId() == null && job.lastError() == null
                            && (cancelled || job.status() == ScheduledTweetStatus.SCHEDULED)) {
                        var uncertain = job.markOverdueConfirmRequired(OverdueReason.UNCERTAIN_DELIVERY, true,
                                UUID.randomUUID().toString(), "旧记录缺少上次发送回执，请核对 X 主页后再补发",
                                job.updatedAtEpochMs());
                        queue.jobs().set(i, cancelled ? uncertain.markCancelled(job.updatedAtEpochMs()) : uncertain);
                    }
                }
            }
            if (raw.version() < 3) {
                // Unstructured v1/v2 jobs encoded a native quote as the last line of the body.
                for (int i = 0; i < queue.jobs().size(); i++) {
                    ScheduledTweetJob old = queue.jobs().get(i);
                    if (old.assistantSource() != null || old.quoteTweetId() != null || old.status() == ScheduledTweetStatus.PUBLISHED) continue;
                    var send = resolveEffectiveQuoteSend(old.text(), null);
                    if (send.quoteTweetId() == null) continue;
                    String url = resolveEffectiveQuoteUrl(old.text(), send.quoteTweetId(), null);
                    var hashes = old.media().stream().map(ScheduledMediaAsset::sha256).toList();
                    String fingerprint = computeContentFingerprint(old.text().trim(), hashes, send.quoteTweetId());
                    queue.jobs().set(i, old.withPublicationSource(null, send.quoteTweetId(), url, fingerprint));
                }
                saveQueueLocked(queue);
            }
            restoreAcceptedDeliveries(queue);
            return queue;
        } catch (IOException ex) {
            throw new IllegalStateException("定时推文队列读取失败，已停止操作以保护现有记录", ex);
        }
    }

    private static boolean applyAcceptedDelivery(QueueStorage queue, AcceptedDelivery accepted) {
        String id = accepted.job().id();
        int index = findJobIndex(queue, id);
        ScheduledTweetJob current = index >= 0 ? queue.jobs().get(index) : queue.assistantReceipts().getOrDefault(id, accepted.job());
        if (current.publishedTweetId() != null && !current.publishedTweetId().equals(accepted.receipt().tweetId()))
            throw new IllegalStateException("排期回执包含两个不同的帖子 ID，请核实");
        if (current.status() == ScheduledTweetStatus.PUBLISHED && accepted.receipt().equals(current.publicationReceipt())) return false;
        ScheduledTweetJob restored = current.overdueReason() == OverdueReason.AUTHOR_VERIFIED && current.publishedTweetId() != null
                ? current.restorePublished(current.publishedTweetId(), current.publishedTweetUrl(), accepted.acceptedAt(),
                        current.confirmedAtEpochMs(), accepted.receipt())
                : accepted.published(current);
        if (index >= 0) queue.jobs().set(index, restored);
        else queue.assistantReceipts().put(id, restored);
        return true;
    }

    private void restoreAcceptedDeliveries(QueueStorage queue) throws IOException {
        Path directory = resolveAccountDir(queue.accountId()).resolve("scheduled-receipts");
        if (!Files.isDirectory(directory)) return;
        List<Path> consumed = new ArrayList<>();
        boolean changed = false;
        try (var files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) {
                var accepted = objectMapper.readValue(file.toFile(), AcceptedDelivery.class);
                if (accepted.job() == null || !queue.accountId().equals(accepted.job().accountId())
                        || !file.getFileName().toString().equals(accepted.job().id() + ".json")
                        || accepted.receipt() == null || accepted.receipt().tweetId() == null)
                    throw new IOException("定时推文成功回执与排期不匹配");
                changed |= applyAcceptedDelivery(queue, accepted);
                consumed.add(file);
            }
        }
        if (changed) saveQueueLocked(queue);
        for (Path file : consumed) {
            try { Files.deleteIfExists(file); }
            catch (IOException ex) { log.warn("Could not remove an applied scheduled receipt {}", file.getFileName(), ex); }
        }
    }

    private void saveQueueLocked(QueueStorage queue) {
        Path file = resolveQueueFile(queue.accountId());
        if (file == null) {
            throw new IllegalStateException("未配置磁盘缓存目录，无法保存定时推文队列");
        }
        writeDurableJson(file, queue);
    }

    private void writeDurableJson(Path file, Object value) {
        Path tmp = null;
        try {
            Files.createDirectories(file.getParent());
            tmp = Files.createTempFile(file.getParent(), file.getFileName().toString() + ".", ".tmp");
            byte[] bytes = objectMapper.writeValueAsBytes(value);
            try (var channel = java.nio.channels.FileChannel.open(tmp, java.nio.file.StandardOpenOption.WRITE)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("持久化定时推文记录失败，已停止本次操作", ex);
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            }
        }
    }

    private List<String> discoverScheduledAccountIds() {
        String dir = properties.getDiskCacheDir();
        if (dir == null || dir.isBlank()) {
            return List.of();
        }
        Path byIdRoot = Path.of(dir).resolve("users").resolve("by-id");
        if (!Files.isDirectory(byIdRoot)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        try (Stream<Path> stream = Files.list(byIdRoot)) {
            stream.filter(Files::isDirectory).forEach(p -> {
                String name = p.getFileName().toString();
                if (SAFE_ID_PATTERN.matcher(name).matches()
                        && Files.exists(p.resolve("scheduled_tweets_v1.json"))) {
                    ids.add(name);
                }
            });
        } catch (IOException ex) {
            log.debug("Failed to list scheduled account dirs: {}", ex.getMessage());
        }
        return ids;
    }

    private Path resolveAccountDir(String accountId) {
        String dir = properties.getDiskCacheDir();
        if (dir == null || dir.isBlank()) {
            return null;
        }
        validateSafeId(accountId, "accountId");
        return Path.of(dir).resolve("users").resolve("by-id").resolve(accountId);
    }

    private Path resolveQueueFile(String accountId) {
        Path accDir = resolveAccountDir(accountId);
        return accDir == null ? null : accDir.resolve("scheduled_tweets_v1.json");
    }

    private Path resolveJobMediaDir(String accountId, String jobId) {
        Path accDir = resolveAccountDir(accountId);
        if (accDir == null) {
            throw new IllegalStateException("未配置磁盘缓存目录，无法存储定时推文图片");
        }
        validateSafeId(jobId, "jobId");
        return accDir.resolve("scheduled_media").resolve(jobId);
    }

    private Path resolveMediaAssetPath(String accountId, String jobId, String assetId, String ext) {
        validateSafeId(assetId, "assetId");
        return resolveJobMediaDir(accountId, jobId).resolve(assetId + ext);
    }

    private Object accountLock(String accountId) {
        return accountLocks[(accountId.hashCode() & 0x7fffffff) % accountLocks.length];
    }

    private static ScheduledTweetJob findJob(QueueStorage queue, String jobId) {
        for (ScheduledTweetJob job : queue.jobs()) {
            if (jobId.equals(job.id())) {
                return job;
            }
        }
        return null;
    }

    private static int findJobIndex(QueueStorage queue, String jobId) {
        for (int i = 0; i < queue.jobs().size(); i++) {
            if (jobId.equals(queue.jobs().get(i).id())) {
                return i;
            }
        }
        return -1;
    }

    private void assertMinOneHourIntervalLocked(
            QueueStorage queue,
            long candidateEpochMs,
            String excludeJobId,
            String itemPrefix,
            boolean accountOwner
    ) {
        if (accountOwner) return;
        for (ScheduledTweetJob existing : queue.jobs()) {
            if (existing.status() != ScheduledTweetStatus.SCHEDULED) {
                continue;
            }
            if (excludeJobId != null && excludeJobId.equals(existing.id())) {
                continue;
            }
            if (Math.abs(existing.scheduledAtEpochMs() - candidateEpochMs) < MIN_TWEET_INTERVAL_MS) {
                String conflictTime = formatScheduledTimeForMessage(existing.scheduledAtEpochMs());
                String prefix = (itemPrefix == null || itemPrefix.isBlank()) ? "定时时间" : (itemPrefix + "定时时间");
                throw new XDataProviderException(
                        409,
                        prefix + "与待发队列中的推文（" + conflictTime + "）间隔不足 1 小时，每条定时推文之间必须至少间隔 1 小时"
                );
            }
        }
    }

    private String formatScheduledTimeForMessage(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(resolveConfiguredZone()).format(CONFLICT_TIME_FMT);
    }

    private ZoneId resolveConfiguredZone() {
        try {
            String raw = properties.getTimezone();
            if (raw != null && !raw.isBlank()) {
                return ZoneId.of(raw.trim());
            }
        } catch (Exception ignored) {
        }
        return ZoneId.of("Asia/Shanghai");
    }

    private String localDateStringFor(long epochMs) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(epochMs), resolveConfiguredZone()).toString();
    }

    private void assertActiveQueueCapacityLocked(QueueStorage queue, int addingCount, boolean accountOwner) {
        if (accountOwner) return;
        int activeCount = 0;
        for (ScheduledTweetJob job : queue.jobs()) {
            if (isActiveStatus(job.status())) {
                activeCount++;
            }
        }
        if (activeCount + addingCount > MAX_ACTIVE_JOBS_PER_ACCOUNT) {
            throw new XDataProviderException(
                    429,
                    "待发定时推文队列已达上限（最多保留 " + MAX_ACTIVE_JOBS_PER_ACCOUNT + " 条待发任务），请等待部分发送完成后再添加"
            );
        }
    }

    private void assertStoredQueueCapacityLocked(QueueStorage queue, int adding, long mediaBytes, boolean accountOwner) {
        long storedBytes = queue.jobs().stream().flatMap(job -> job.media().stream())
                .mapToLong(asset -> Math.max(0L, asset.sizeBytes())).sum();
        if ((!accountOwner && (long) queue.jobs().size() + adding > MAX_STORED_JOBS_PER_ACCOUNT)
                || storedBytes + mediaBytes > MAX_STORED_MEDIA_BYTES_PER_ACCOUNT) {
            throw new XDataProviderException(429, "定时推文保存空间已满，请删除不再需要的历史记录后重试");
        }
    }

    private void reserveMediaSpace(long bytes) {
        if (bytes == 0L) return;
        synchronized (mediaSpaceLock) {
            try {
                Path root = Path.of(properties.getDiskCacheDir());
                Files.createDirectories(root);
                if (Files.getFileStore(root).getUsableSpace() - reservedMediaBytes - bytes < MIN_FREE_DISK_BYTES) {
                    throw new XDataProviderException(503, "服务器保存空间不足，请稍后再试");
                }
                reservedMediaBytes += bytes;
            } catch (IOException ex) {
                throw new IllegalStateException("无法检查媒体保存空间，已停止上传", ex);
            }
        }
    }

    private void releaseMediaSpace(long bytes) {
        synchronized (mediaSpaceLock) { reservedMediaBytes -= bytes; }
    }

    private int dailyPublishUsedLocked(QueueStorage queue, long nowMs) {
        String todayDate = localDateStringFor(nowMs);
        int publishedInQueueToday = 0;
        for (ScheduledTweetJob job : queue.jobs()) {
            if (job.status() == ScheduledTweetStatus.PUBLISHED && job.publishedAtEpochMs() > 0L
                    && todayDate.equals(localDateStringFor(job.publishedAtEpochMs()))) {
                publishedInQueueToday++;
            }
        }
        Path accountDir = resolveAccountDir(queue.accountId());
        if (accountDir == null) return publishedInQueueToday;
        Path file = accountDir.resolve("scheduled_publish_budget_v1.json");
        if (!Files.exists(file)) return publishedInQueueToday;
        try {
            DailyPublishCounter counter = objectMapper.readValue(file.toFile(), DailyPublishCounter.class);
            if (counter == null || !queue.accountId().equals(counter.accountId()) || counter.count() < 0
                    || counter.localDate() == null || counter.localDate().compareTo(todayDate) > 0) {
                throw new IOException("Invalid daily publish budget");
            }
            return todayDate.equals(counter.localDate())
                    ? Math.max(publishedInQueueToday, counter.count()) : publishedInQueueToday;
        } catch (IOException ex) {
            throw new IllegalStateException("定时发布额度记录读取失败，已停止发送以保护预算", ex);
        }
    }

    private void assertDailyPublishQuotaAvailableLocked(QueueStorage queue, long nowMs, int addingToday, boolean accountOwner) {
        if (accountOwner) return;
        int limit = properties.getDailyTweetPublishLimitPerUser();
        if (limit <= 0) throw new XDataProviderException(429, "定时发布额度已关闭");
        int used = dailyPublishUsedLocked(queue, nowMs);
        if ((long) used + Math.max(1, addingToday) > limit) {
            throw new XDataProviderException(429,
                    "今日定时发布额度已达上限（每日最多 " + limit + " 次发送尝试），请改期至明日");
        }
    }

    private void reserveDailyPublishQuotaLocked(QueueStorage queue, long nowMs) {
        assertDailyPublishQuotaAvailableLocked(queue, nowMs, 1, false);
        int used = dailyPublishUsedLocked(queue, nowMs);
        Path accountDir = resolveAccountDir(queue.accountId());
        if (accountDir == null) throw new IllegalStateException("未配置定时发布持久化目录");
        // Reserve before token refresh, media upload or publishing. Failures and uncertain delivery
        // remain charged; deleting jobs or restarting must never restore a paid-call allowance.
        writeDurableJson(accountDir.resolve("scheduled_publish_budget_v1.json"),
                new DailyPublishCounter(queue.accountId(), localDateStringFor(nowMs), Math.addExact(used, 1)));
    }

    private static boolean isActiveStatus(ScheduledTweetStatus status) {
        return status == ScheduledTweetStatus.SCHEDULED
                || status == ScheduledTweetStatus.OVERDUE_CONFIRM_REQUIRED
                || status == ScheduledTweetStatus.RESEND_QUEUED
                || status == ScheduledTweetStatus.SENDING;
    }

    private static void recordClientRequestId(Map<String, String> map, String reqId, String jobId) {
        map.put(reqId, jobId);
        while (map.size() > MAX_CLIENT_REQUEST_CACHE) {
            Iterator<String> it = map.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            } else {
                break;
            }
        }
    }

    private static String computeContentFingerprint(String normalizedText, List<String> mediaSha256List) {
        return computeContentFingerprint(normalizedText, mediaSha256List, null);
    }

    private static String computeContentFingerprint(String normalizedText, List<String> mediaSha256List, String quoteTweetId) {
        String joined = normalizedText + "|" + String.join(",", mediaSha256List)
                + (quoteTweetId != null && !quoteTweetId.isBlank() ? ("|quote:" + quoteTweetId.trim()) : "");
        return sha256Hex(joined.getBytes(StandardCharsets.UTF_8));
    }

    static String normalizeQuoteTweetId(String rawQuoteTweetId) {
        if (rawQuoteTweetId == null) {
            return null;
        }
        String trimmed = rawQuoteTweetId.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (!QUOTE_TWEET_ID_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("被引用推文 ID 格式无效");
        }
        return trimmed;
    }

    static String normalizeQuoteTweetUrl(String rawQuoteTweetUrl, String normalizedQuoteTweetId) {
        if (normalizedQuoteTweetId == null) return null;
        XPostLink supplied = XPostLink.parse(rawQuoteTweetUrl);
        return supplied != null && normalizedQuoteTweetId.equals(supplied.tweetId())
                ? supplied.url() : new XPostLink(normalizedQuoteTweetId, null).url();
    }

    record ResolvedQuoteSend(String textToSend, String quoteTweetId) { }

    static ResolvedQuoteSend resolveEffectiveQuoteSend(String text, String explicitQuoteTweetId) {
        String rawText = text == null ? "" : text;
        String normalizedId = normalizeQuoteTweetId(explicitQuoteTweetId);
        if (normalizedId != null) {
            Pattern sameIdTrailing = Pattern.compile(
                    "(?is)^(.*?)(?:\\r?\\n)+https?://(?:(?:www|mobile)\\.)?(?:x|twitter)\\.com/[A-Za-z0-9_]{1,15}/status/"
                            + Pattern.quote(normalizedId) + "/?\\s*$");
            var matcher = sameIdTrailing.matcher(rawText);
            if (matcher.matches() && !matcher.group(1).strip().isEmpty()) {
                return new ResolvedQuoteSend(matcher.group(1).stripTrailing(), normalizedId);
            }
            return new ResolvedQuoteSend(rawText, normalizedId);
        }
        var matcher = TRAILING_STATUS_URL_PATTERN.matcher(rawText);
        if (matcher.matches() && !matcher.group(1).strip().isEmpty()) {
            return new ResolvedQuoteSend(matcher.group(1).stripTrailing(), matcher.group(2));
        }
        return new ResolvedQuoteSend(rawText, null);
    }

    private static String resolveEffectiveQuoteUrl(String rawText, String quoteTweetId, String explicitQuoteTweetUrl) {
        if (quoteTweetId == null || quoteTweetId.isBlank()) {
            return null;
        }
        XPostLink supplied = XPostLink.parse(explicitQuoteTweetUrl);
        if (supplied != null && quoteTweetId.equals(supplied.tweetId()) && supplied.username() != null
                && !"i".equalsIgnoreCase(supplied.username())) return supplied.url();
        XPostLink generic = new XPostLink(quoteTweetId, null);
        XPostLink embedded = generic.findIn(rawText);
        return (embedded == null ? generic : embedded).url();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String sanitizeFileName(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String base = Path.of(raw).getFileName().toString().replaceAll("[\\r\\n\"\\\\/]+", "_").trim();
        if (base.isEmpty()) {
            return fallback;
        }
        return base.length() > 80 ? base.substring(0, 80) : base;
    }

    private static void validateSafeId(String value, String fieldName) {
        if (value == null || value.isBlank() || !SAFE_ID_PATTERN.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException("非法的 " + fieldName + " 参数");
        }
    }

    private static void deleteDirectoryQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
