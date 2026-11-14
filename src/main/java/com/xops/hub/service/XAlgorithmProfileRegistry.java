package com.xops.hub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.XAlgorithmProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * X 算法规则单一事实来源与热加载注册表。
 * - 默认加载 classpath:/x-algorithm-profile.json；
 * - 配置本地覆盖文件时按 mtime + size 零轮询自动热加载；
 * - 支持 API 热保存、从磁盘重载及恢复内置基线，并生成内容指纹供内存缓存零 API 重算。
 */
@Service
public class XAlgorithmProfileRegistry {

    private static final Logger log = LoggerFactory.getLogger(XAlgorithmProfileRegistry.class);
    private static final String CLASSPATH_RESOURCE = "/x-algorithm-profile.json";
    private static final ObjectMapper STATIC_MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final XAlgorithmProfile BUILTIN_PROFILE = loadBuiltinProfile(STATIC_MAPPER);

    public record ProfileState(
            XAlgorithmProfile profile,
            String source,
            String fingerprint,
            Instant loadedAt,
            String overridePath
    ) {
    }

    private final XOpsProperties properties;
    private final ObjectMapper objectMapper;
    private final Object lock = new Object();

    private volatile ProfileState cachedState;
    private long lastObservedMtimeMs = -1L;
    private long lastObservedSize = -1L;
    private boolean lastObservedExists = false;

    public XAlgorithmProfileRegistry(XOpsProperties properties, ObjectMapper objectMapper) {
        this.properties = properties != null ? properties : new XOpsProperties();
        this.objectMapper = objectMapper != null ? objectMapper : STATIC_MAPPER;
        this.cachedState = loadInitialState();
    }

    public static XAlgorithmProfile defaultProfile() {
        return BUILTIN_PROFILE;
    }

    public ProfileState currentState() {
        Path overrideFile = resolveOverridePath();
        boolean existsNow = overrideFile != null && Files.isRegularFile(overrideFile);
        if (!existsNow && !lastObservedExists && cachedState != null) {
            return cachedState;
        }
        synchronized (lock) {
            return refreshIfChangedLocked(false);
        }
    }

    public XAlgorithmProfile currentProfile() {
        return currentState().profile();
    }

    public ProfileState saveOverride(XAlgorithmProfile incoming) {
        if (incoming == null) {
            throw new IllegalArgumentException("算法配置不能为空");
        }
        incoming.validateStrict();
        XAlgorithmProfile normalized = incoming.normalizedWithDefaults(BUILTIN_PROFILE);
        normalized.validateStrict();

        Path overrideFile = resolveOverridePath();
        if (overrideFile == null) {
            throw new IllegalArgumentException("未配置算法覆盖文件路径 xops.algo-profile-path");
        }
        synchronized (lock) {
            try {
                Path parent = overrideFile.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(normalized) + "\n";
                Path tmpFile = overrideFile.resolveSibling(overrideFile.getFileName() + ".tmp");
                Files.writeString(tmpFile, json, StandardCharsets.UTF_8);
                Files.move(tmpFile, overrideFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicEx) {
                try {
                    String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(normalized) + "\n";
                    Path tmpFile = overrideFile.resolveSibling(overrideFile.getFileName() + ".tmp");
                    Files.writeString(tmpFile, json, StandardCharsets.UTF_8);
                    Files.move(tmpFile, overrideFile, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ex) {
                    throw new IllegalStateException("写入算法规则覆盖文件失败：" + ex.getMessage(), ex);
                }
            }
            recordFileStat(overrideFile);
            cachedState = buildState(normalized, "LOCAL_OVERRIDE", overrideFile);
            return cachedState;
        }
    }

    public ProfileState reloadFromDisk() {
        synchronized (lock) {
            return refreshIfChangedLocked(true);
        }
    }

    public ProfileState resetToBuiltin() {
        Path overrideFile = resolveOverridePath();
        synchronized (lock) {
            if (overrideFile != null) {
                try {
                    Files.deleteIfExists(overrideFile);
                } catch (Exception ex) {
                    throw new IllegalStateException("删除算法覆盖文件失败：" + ex.getMessage(), ex);
                }
            }
            lastObservedExists = false;
            lastObservedMtimeMs = -1L;
            lastObservedSize = -1L;
            cachedState = buildState(BUILTIN_PROFILE, "BUILTIN", overrideFile);
            return cachedState;
        }
    }

    private ProfileState loadInitialState() {
        synchronized (lock) {
            try {
                return refreshIfChangedLocked(true);
            } catch (IllegalArgumentException ex) {
                log.warn("Initial external algo profile invalid, falling back to builtin: {}", ex.getMessage());
                Path overrideFile = resolveOverridePath();
                cachedState = buildState(BUILTIN_PROFILE, "BUILTIN", overrideFile);
                return cachedState;
            }
        }
    }

    private ProfileState refreshIfChangedLocked(boolean forceReload) {
        Path overrideFile = resolveOverridePath();
        boolean existsNow = overrideFile != null && Files.isRegularFile(overrideFile);
        if (!existsNow) {
            if (forceReload || lastObservedExists || cachedState == null) {
                lastObservedExists = false;
                lastObservedMtimeMs = -1L;
                lastObservedSize = -1L;
                cachedState = buildState(BUILTIN_PROFILE, "BUILTIN", overrideFile);
            }
            return cachedState;
        }
        try {
            long mtime = Files.getLastModifiedTime(overrideFile).toMillis();
            long size = Files.size(overrideFile);
            boolean changed = forceReload || !lastObservedExists || mtime != lastObservedMtimeMs || size != lastObservedSize;
            if (!changed && cachedState != null) {
                return cachedState;
            }
            String raw = Files.readString(overrideFile, StandardCharsets.UTF_8);
            XAlgorithmProfile parsed = objectMapper.readValue(raw, XAlgorithmProfile.class);
            parsed.validateStrict();
            XAlgorithmProfile normalized = parsed.normalizedWithDefaults(BUILTIN_PROFILE);
            normalized.validateStrict();

            lastObservedExists = true;
            lastObservedMtimeMs = mtime;
            lastObservedSize = size;
            cachedState = buildState(normalized, "LOCAL_OVERRIDE", overrideFile);
            return cachedState;
        } catch (IllegalArgumentException ex) {
            if (forceReload) {
                throw ex;
            }
            log.warn("Invalid algo profile override at {}, keeping active profile: {}", overrideFile, ex.getMessage());
            return cachedState != null ? cachedState : buildState(BUILTIN_PROFILE, "BUILTIN", overrideFile);
        } catch (Exception ex) {
            if (forceReload) {
                throw new IllegalArgumentException("解析本地算法覆盖文件失败：" + ex.getMessage(), ex);
            }
            log.warn("Failed reading algo profile override at {}, keeping active profile: {}", overrideFile, ex.getMessage());
            return cachedState != null ? cachedState : buildState(BUILTIN_PROFILE, "BUILTIN", overrideFile);
        }
    }

    private void recordFileStat(Path file) {
        try {
            if (file != null && Files.isRegularFile(file)) {
                lastObservedExists = true;
                lastObservedMtimeMs = Files.getLastModifiedTime(file).toMillis();
                lastObservedSize = Files.size(file);
            }
        } catch (Exception ignored) {
            lastObservedExists = true;
            lastObservedMtimeMs = -1L;
            lastObservedSize = -1L;
        }
    }

    private Path resolveOverridePath() {
        String raw = properties.getAlgoProfilePath();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Path.of(raw.trim());
    }

    private ProfileState buildState(XAlgorithmProfile profile, String source, Path overrideFile) {
        String fp = computeFingerprint(profile);
        String pathStr = overrideFile != null ? overrideFile.toString() : "";
        return new ProfileState(profile, source, fp, Instant.now(), pathStr);
    }

    private String computeFingerprint(XAlgorithmProfile profile) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(profile);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception ex) {
            return Integer.toHexString(Objects.hashCode(profile));
        }
    }

    private static XAlgorithmProfile loadBuiltinProfile(ObjectMapper mapper) {
        XAlgorithmProfile fallback = XAlgorithmProfile.builtinFallback();
        try (InputStream in = XAlgorithmProfileRegistry.class.getResourceAsStream(CLASSPATH_RESOURCE)) {
            if (in == null) {
                return fallback;
            }
            XAlgorithmProfile parsed = mapper.readValue(in, XAlgorithmProfile.class);
            return parsed.normalizedWithDefaults(fallback);
        } catch (Exception ex) {
            log.warn("Failed loading builtin {} resource, using code fallback: {}", CLASSPATH_RESOURCE, ex.getMessage());
            return fallback;
        }
    }
}
