package com.xops.hub.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.XOpsProperties;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 持久化单账号 X OAuth 2.0 访问令牌与刷新令牌（AES-GCM 加密落盘，绝不进入 HttpSession）。
 * 内置基于 (mtime, size) 校验的内存热缓存与密钥派生缓存，消除高频状态轮询时的重复磁盘读取与解密开销。
 */
public class XOAuthTokenStore {

    private static final String CIPHER_ALGO = "AES/GCM/NoPadding";
    private static final String CIPHER_PREFIX = "v1:";
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int MAX_HOT_CACHE_ENTRIES = 1024;
    private static final Pattern SAFE_ACCOUNT_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Set<PosixFilePermission> OWNER_RW_ONLY = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );

    public record StoredOAuthToken(
            String accountId,
            String username,
            String accessToken,
            String refreshToken,
            long expiresAtEpochSec,
            String scope,
            long updatedAtEpochSec
    ) {
        public boolean hasScope(String target) {
            if (target == null || target.isBlank() || scope == null || scope.isBlank()) {
                return false;
            }
            for (String token : scope.trim().split("\\s+")) {
                if (target.equals(token)) {
                    return true;
                }
            }
            return false;
        }

        public boolean hasWriteScope() {
            return hasScope("tweet.write");
        }

        public boolean hasMediaWriteScope() {
            return hasScope("media.write");
        }

        public boolean hasRefreshToken() {
            return refreshToken != null && !refreshToken.isBlank();
        }
    }

    private record CachedTokenRecord(Path path, FileTime modifiedAt, long size, Object fileKey, StoredOAuthToken token) {
    }

    private record CachedSecretKeyHolder(String sourceKey, SecretKey secretKey) {
    }

    private static final Object MASTER_KEY_LOCK = new Object();

    private final XOpsProperties properties;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, CachedTokenRecord> hotCache = new ConcurrentHashMap<>();
    private volatile CachedSecretKeyHolder cachedSecretKey;

    public XOAuthTokenStore(XOpsProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public StoredOAuthToken load(String accountId) {
        Path file = resolveTokenFile(accountId);
        if (file == null) {
            return null;
        }
        String cacheKey = accountId.trim();
        try {
            if (!Files.exists(file)) {
                hotCache.remove(cacheKey);
                return null;
            }
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            long size = attrs.size();
            CachedTokenRecord hit = hotCache.get(cacheKey);
            if (hit != null && hit.modifiedAt().equals(attrs.lastModifiedTime()) && hit.size() == size
                    && Objects.equals(hit.fileKey(), attrs.fileKey()) && Objects.equals(hit.path(), file)) {
                return hit.token();
            }
            String raw = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (raw.isEmpty()) {
                hotCache.remove(cacheKey);
                return null;
            }
            String json = decrypt(raw);
            StoredOAuthToken parsed = objectMapper.readValue(json, StoredOAuthToken.class);
            if (parsed == null || !cacheKey.equals(parsed.accountId())) {
                hotCache.remove(cacheKey);
                return null;
            }
            putHotCache(cacheKey, new CachedTokenRecord(file, attrs.lastModifiedTime(), size, attrs.fileKey(), parsed));
            return parsed;
        } catch (Exception ex) {
            hotCache.remove(cacheKey);
            return null;
        }
    }

    public void save(StoredOAuthToken token) {
        if (token == null) {
            return;
        }
        Path file = resolveTokenFile(token.accountId());
        if (file == null) {
            return;
        }
        String cacheKey = token.accountId().trim();
        Path tmp = null;
        try {
            Files.createDirectories(file.getParent());
            String json = objectMapper.writeValueAsString(token);
            String encrypted = encrypt(json);
            tmp = Files.createTempFile(file.getParent(), "oauth_token_v1.", ".tmp");
            setOwnerOnlyPermissions(tmp);
            Files.writeString(tmp, encrypted, StandardCharsets.UTF_8);
            atomicReplace(tmp, file);
            setOwnerOnlyPermissions(file);
            hotCache.remove(cacheKey);
        } catch (Exception ex) {
            hotCache.remove(cacheKey);
            throw new IllegalStateException("持久化 X OAuth 授权令牌失败：" + ex.getMessage(), ex);
        } finally {
            deleteQuietly(tmp);
        }
    }

    public void delete(String accountId) {
        if (accountId != null) {
            hotCache.remove(accountId.trim());
        }
        Path file = resolveTokenFile(accountId);
        if (file == null) {
            return;
        }
        deleteQuietly(file);
    }

    private void putHotCache(String key, CachedTokenRecord record) {
        if (hotCache.size() >= MAX_HOT_CACHE_ENTRIES && !hotCache.containsKey(key)) {
            Iterator<String> it = hotCache.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        hotCache.put(key, record);
    }

    Path resolveTokenFile(String accountId) {
        String dir = properties.getDiskCacheDir();
        if (dir == null || dir.isBlank() || accountId == null || accountId.isBlank()) {
            return null;
        }
        String trimmed = accountId.trim();
        if (!SAFE_ACCOUNT_ID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("非法的 X 账号 ID：" + accountId);
        }
        return Path.of(dir).resolve("users").resolve("by-id").resolve(trimmed).resolve("oauth_token_v1.enc");
    }

    private String encrypt(String plaintext) throws Exception {
        return encryptWithPrefix(CIPHER_PREFIX, plaintext);
    }

    private String decrypt(String payload) throws Exception {
        return decryptWithPrefix(CIPHER_PREFIX, payload);
    }

    String encryptWithPrefix(String prefix, String plaintext) throws Exception {
        byte[] iv = new byte[GCM_IV_LEN];
        secureRandom.nextBytes(iv);
        Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
        cipher.init(Cipher.ENCRYPT_MODE, resolveSecretKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] cipherBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        return prefix + enc.encodeToString(iv) + ":" + enc.encodeToString(cipherBytes);
    }

    String decryptWithPrefix(String prefix, String payload) throws Exception {
        if (payload == null || !payload.startsWith(prefix)) {
            throw new IllegalArgumentException("Unsupported token cipher format");
        }
        String rest = payload.substring(prefix.length());
        int sep = rest.indexOf(':');
        if (sep <= 0 || sep >= rest.length() - 1) {
            throw new IllegalArgumentException("Corrupted token cipher payload");
        }
        Base64.Decoder dec = Base64.getUrlDecoder();
        byte[] iv = dec.decode(rest.substring(0, sep));
        byte[] cipherBytes = dec.decode(rest.substring(sep + 1));
        Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
        cipher.init(Cipher.DECRYPT_MODE, resolveSecretKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] plainBytes = cipher.doFinal(cipherBytes);
        return new String(plainBytes, StandardCharsets.UTF_8);
    }

    private SecretKey resolveSecretKey() throws Exception {
        String configuredSecret = properties.getTokenEncryptionSecret();
        String clientSecret = properties.getOauthClientSecret();
        String diskDir = properties.getDiskCacheDir();
        String sourceTag;
        if (configuredSecret != null && !configuredSecret.isBlank()) {
            sourceTag = "cfg:" + configuredSecret.trim();
        } else if (clientSecret != null && !clientSecret.isBlank()) {
            sourceTag = "oauth:" + clientSecret.trim();
        } else {
            sourceTag = "file:" + (diskDir != null ? diskDir.trim() : "");
        }

        CachedSecretKeyHolder holder = this.cachedSecretKey;
        if (holder != null && sourceTag.equals(holder.sourceKey())) {
            return holder.secretKey();
        }

        byte[] material;
        if (configuredSecret != null && !configuredSecret.isBlank()) {
            material = configuredSecret.trim().getBytes(StandardCharsets.UTF_8);
        } else if (clientSecret != null && !clientSecret.isBlank()) {
            material = ("xops-oauth-token-v1:" + clientSecret.trim()).getBytes(StandardCharsets.UTF_8);
        } else {
            material = loadOrCreateMasterKeyBytes();
        }
        byte[] keyBytes = MessageDigest.getInstance("SHA-256").digest(material);
        SecretKey derived = new SecretKeySpec(keyBytes, "AES");
        this.cachedSecretKey = new CachedSecretKeyHolder(sourceTag, derived);
        return derived;
    }

    private byte[] loadOrCreateMasterKeyBytes() throws IOException {
        String dir = properties.getDiskCacheDir();
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException("未配置磁盘缓存目录且未配置令牌加密密钥");
        }
        Path keyFile = Path.of(dir).resolve(".token_master_key");
        synchronized (MASTER_KEY_LOCK) {
            if (Files.exists(keyFile)) {
                byte[] existing = Files.readAllBytes(keyFile);
                if (existing.length >= 16) {
                    return existing;
                }
            }
            Files.createDirectories(keyFile.getParent());
            byte[] generated = new byte[32];
            secureRandom.nextBytes(generated);
            Path tmp = keyFile.resolveSibling(
                    ".token_master_key." + ThreadLocalRandom.current().nextLong(1_000_000_000L) + ".tmp"
            );
            try {
                Files.write(tmp, generated);
                setOwnerOnlyPermissions(tmp);
                atomicReplace(tmp, keyFile);
                setOwnerOnlyPermissions(keyFile);
                return generated;
            } finally {
                deleteQuietly(tmp);
            }
        }
    }

    static void atomicReplace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void setOwnerOnlyPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, OWNER_RW_ONLY);
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
