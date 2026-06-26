package com.novel.system.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.system.entity.ModelProfile;
import com.novel.system.exception.ResourceNotFoundException;
import com.novel.system.repository.ModelProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelProfileService {

    private static final Pattern SAFE_PROFILE_ID = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final Pattern SAFE_VERSION_ID = Pattern.compile("^model_profiles_\\d{17}$");
    private static final DateTimeFormatter SNAPSHOT_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final String STORE_FILENAME = "model_profiles.json";
    private static final String RUNTIME_SECRETS_FILENAME = "model_profile_secrets.json";
    private static final String SNAPSHOT_PREFIX = "model_profiles_";
    private static final String JSON_SUFFIX = ".json";
    private static final List<String> MODEL_KEYS = List.of(
        "mainModel",
        "fastModel",
        "embeddingModel",
        "rerankModel"
    );
    private static final String ENCRYPTED_PREFIX = "enc:v1:";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private final PythonClientService pythonClientService;
    private final ModelProfileRepository modelProfileRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${file.storage.base-path:/workspace}")
    private String basePath;

    @Value("${model.profile.encryption-key:${MODEL_PROFILE_ENCRYPTION_KEY:novel-system-local-dev-key}}")
    private String encryptionKey;

    @Transactional
    public List<Map<String, Object>> listProfiles() {
        ensureInitialized();
        return modelProfileRepository.findAllByOrderByCreatedAtAsc().stream()
            .map(profile -> sanitizeProfile(toMap(profile, true), false))
            .toList();
    }

    @Transactional
    public Map<String, Object> getProfile(String profileId) {
        ensureInitialized();
        validateProfileId(profileId);
        return sanitizeProfile(toMap(getEntity(profileId), true), false);
    }

    @Transactional
    public Map<String, Object> getDefaultProfile() {
        ensureInitialized();
        ModelProfile profile = defaultEntityOrNull();
        return profile == null ? null : sanitizeProfile(toMap(profile, true), false);
    }

    @Transactional
    public String getDefaultProfileId() {
        ensureInitialized();
        ModelProfile profile = defaultEntityOrNull();
        return profile == null ? null : profile.getProfileId();
    }

    @Transactional
    public Map<String, Object> createProfile(Map<String, Object> request) {
        ensureInitialized();
        Map<String, Object> options = request == null ? Map.of() : request;
        String profileId = asString(options.get("profileId"), asString(options.get("id"), ""));
        if (profileId.isBlank()) {
            profileId = "profile_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        validateProfileId(profileId);
        if (modelProfileRepository.existsById(profileId)) {
            throw new IllegalArgumentException("模型配置已存在: " + profileId);
        }

        ModelProfile profile = buildEntity(profileId, options, null);
        profile.setDefaultProfile(asBooleanFlexible(options.get("setDefault"), modelProfileRepository.count() == 0));
        if (Boolean.TRUE.equals(profile.getDefaultProfile())) {
            clearDefaultProfile();
        }
        ModelProfile saved = modelProfileRepository.save(profile);
        exportStoreSnapshot(true);
        return sanitizeProfile(toMap(saved, true), false);
    }

    @Transactional
    public Map<String, Object> updateProfile(String profileId, Map<String, Object> request) {
        ensureInitialized();
        validateProfileId(profileId);
        Map<String, Object> options = request == null ? Map.of() : request;
        ModelProfile existing = getEntity(profileId);
        ModelProfile updated = buildEntity(profileId, options, existing);
        if (asBooleanFlexible(options.get("setDefault"), Boolean.TRUE.equals(existing.getDefaultProfile()))) {
            clearDefaultProfile();
            updated.setDefaultProfile(true);
        }
        ModelProfile saved = modelProfileRepository.save(updated);
        exportStoreSnapshot(true);
        return sanitizeProfile(toMap(saved, true), false);
    }

    @Transactional
    public Map<String, Object> deleteProfile(String profileId) {
        ensureInitialized();
        validateProfileId(profileId);
        ModelProfile removed = getEntity(profileId);
        boolean wasDefault = Boolean.TRUE.equals(removed.getDefaultProfile());
        modelProfileRepository.delete(removed);
        if (wasDefault) {
            modelProfileRepository.findAllByOrderByCreatedAtAsc().stream()
                .findFirst()
                .ifPresent(profile -> {
                    profile.setDefaultProfile(true);
                    modelProfileRepository.save(profile);
                });
        }
        exportStoreSnapshot(true);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("deleted", true);
        response.put("profileId", profileId);
        response.put("defaultProfileId", getDefaultProfileId());
        return response;
    }

    @Transactional
    public Map<String, Object> setDefaultProfile(String profileId) {
        ensureInitialized();
        validateProfileId(profileId);
        ModelProfile profile = getEntity(profileId);
        clearDefaultProfile();
        profile.setDefaultProfile(true);
        profile = modelProfileRepository.save(profile);
        exportStoreSnapshot(true);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("defaultProfileId", profileId);
        response.put("updatedAt", profile.getUpdatedAt().toString());
        return response;
    }

    @Transactional
    public Map<String, Object> testProfile(String profileId, Map<String, Object> request) {
        ensureInitialized();
        validateProfileId(profileId);
        Map<String, Object> profile = toMap(getEntity(profileId), true);
        Map<String, Object> options = request == null ? Map.of() : request;
        String taskType = asString(options.get("taskType"), "chapter_writing");
        Map<String, Object> model = selectModel(profile, taskType);
        List<Map<String, Object>> checks = new ArrayList<>();
        addCheck(checks, "profile_exists", true, "模型配置存在");
        addCheck(checks, "db_persisted", modelProfileRepository.existsById(profileId), "模型配置已存入数据库");
        addCheck(checks, "main_model", profile.get("mainModel") instanceof Map<?, ?>, "配置了主模型");
        addCheck(checks, "selected_model", model != null, "任务可选择模型");
        boolean mock = asBooleanFlexible(model != null ? model.get("mock") : null, false)
            || "mock".equalsIgnoreCase(asString(model != null ? model.get("provider") : null, ""));
        boolean hasCredential = model != null && (
            mock
                || !asString(model.get("apiKey"), "").isBlank()
                || !asString(model.get("api_key"), "").isBlank()
                || !asString(model.get("endpoint"), "").isBlank()
        );
        addCheck(checks, "credential", hasCredential, "mock 模型或真实模型凭据存在");
        boolean pythonHealthy = pythonClientService.checkHealth();
        addCheck(checks, "python_health", pythonHealthy, "Python AI 服务可访问");

        boolean passed = checks.stream().allMatch(check -> Boolean.TRUE.equals(check.get("passed")));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("profileId", profileId);
        response.put("status", passed ? "passed" : "failed");
        response.put("taskType", taskType);
        response.put("selectedModel", sanitizeModel(model, false));
        response.put("checks", checks);
        response.put("testedAt", LocalDateTime.now().toString());
        response.put("message", passed ? "模型配置结构、数据库持久化和服务连通性检查通过" : "模型配置存在未通过项");
        return response;
    }

    @Transactional
    public List<Map<String, Object>> listVersions() {
        ensureInitialized();
        Path configDir = storeFile().getParent();
        if (!Files.exists(configDir)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(configDir)) {
            return paths
                .filter(this::isVersionFile)
                .sorted(Comparator.comparing(this::versionIdFromFile).reversed())
                .map(file -> versionSummary(file, readStore(file)))
                .toList();
        } catch (IOException e) {
            throw new RuntimeException("读取模型配置版本列表失败", e);
        }
    }

    @Transactional
    public Map<String, Object> getVersion(String versionId) {
        ensureInitialized();
        Path file = versionFile(versionId);
        if (!Files.exists(file)) {
            throw new ResourceNotFoundException("模型配置版本不存在: " + versionId);
        }
        Map<String, Object> store = readStore(file);
        Map<String, Object> response = versionSummary(file, store);
        response.put("profiles", sanitizeProfileList(store));
        response.put("store", sanitizeStore(store));
        return response;
    }

    @Transactional
    public Map<String, Object> restoreVersion(String versionId, Map<String, Object> request) {
        ensureInitialized();
        Path file = versionFile(versionId);
        if (!Files.exists(file)) {
            throw new ResourceNotFoundException("模型配置版本不存在: " + versionId);
        }

        Map<String, Object> store = readStore(file);
        List<Map<String, Object>> profiles = profileList(store);
        if (profiles.isEmpty()) {
            throw new IllegalArgumentException("模型配置版本不包含可恢复的配置: " + versionId);
        }

        Path previousSnapshot = exportStoreSnapshot(true);
        modelProfileRepository.deleteAll();
        for (Map<String, Object> profile : profiles) {
            String profileId = asString(profile.get("profileId"), "");
            validateProfileId(profileId);
            modelProfileRepository.save(buildEntity(profileId, profile, null));
        }

        String defaultProfileId = asString(store.get("defaultProfileId"), "");
        if (defaultProfileId.isBlank() || !modelProfileRepository.existsById(defaultProfileId)) {
            defaultProfileId = profiles.stream()
                .map(profile -> asString(profile.get("profileId"), ""))
                .filter(profileId -> !profileId.isBlank())
                .findFirst()
                .orElse("");
        }
        if (!defaultProfileId.isBlank()) {
            clearDefaultProfile();
            ModelProfile defaultProfile = getEntity(defaultProfileId);
            defaultProfile.setDefaultProfile(true);
            modelProfileRepository.save(defaultProfile);
        }

        exportStoreSnapshot(false);

        Map<String, Object> options = request == null ? Map.of() : request;
        Map<String, Object> response = versionSummary(file, store);
        response.put("status", "restored");
        response.put("restoredProfileCount", profiles.size());
        response.put("defaultProfileId", defaultProfileId);
        response.put("previousSnapshotPath", previousSnapshot == null ? "" : relativeConfigPath(previousSnapshot));
        response.put("actor", asString(options.get("actor"), "system"));
        response.put("note", asString(options.get("note"), ""));
        response.put("restoredAt", LocalDateTime.now().toString());
        return response;
    }

    private void ensureInitialized() {
        if (modelProfileRepository.count() > 0) {
            return;
        }
        Map<String, Object> store = readLegacyStoreOrDefault();
        for (Map<String, Object> profile : profileList(store)) {
            String profileId = asString(profile.get("profileId"), "");
            if (profileId.isBlank()) {
                continue;
            }
            modelProfileRepository.save(buildEntity(profileId, profile, null));
        }
        String defaultProfileId = asString(store.get("defaultProfileId"), "");
        if (!defaultProfileId.isBlank() && modelProfileRepository.existsById(defaultProfileId)) {
            clearDefaultProfile();
            ModelProfile profile = getEntity(defaultProfileId);
            profile.setDefaultProfile(true);
            modelProfileRepository.save(profile);
        } else {
            modelProfileRepository.findAllByOrderByCreatedAtAsc().stream()
                .findFirst()
                .ifPresent(profile -> {
                    profile.setDefaultProfile(true);
                    modelProfileRepository.save(profile);
                });
        }
        exportStoreSnapshot(false);
    }

    private Map<String, Object> readLegacyStoreOrDefault() {
        Path file = storeFile();
        if (!Files.exists(file)) {
            return defaultStore();
        }
        return readStore(file);
    }

    private Map<String, Object> readStore(Path file) {
        try {
            return objectMapper.readValue(file.toFile(), new TypeReference<>() {});
        } catch (IOException e) {
            throw new RuntimeException("读取模型配置失败", e);
        }
    }

    private Path exportStoreSnapshot(boolean snapshotBeforeWrite) {
        Path file = storeFile();
        Path snapshot = null;
        try {
            Files.createDirectories(file.getParent());
            if (snapshotBeforeWrite && Files.exists(file)) {
                snapshot = nextSnapshotFile(file.getParent());
                Files.copy(file, snapshot);
            }
            Map<String, Object> runtimeSecrets = new LinkedHashMap<>();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), exportStore(runtimeSecrets));
            writeRuntimeSecrets(runtimeSecrets);
            return snapshot;
        } catch (IOException e) {
            throw new RuntimeException("写入模型配置兼容文件失败", e);
        }
    }

    private void writeRuntimeSecrets(Map<String, Object> runtimeSecrets) throws IOException {
        Path secretsFile = runtimeSecretsFile();
        Files.createDirectories(secretsFile.getParent());
        Map<String, Object> mergedSecrets = readRuntimeSecrets(secretsFile);
        mergedSecrets.putAll(runtimeSecrets);
        Map<String, Object> store = new LinkedHashMap<>();
        store.put("version", "1.0.0");
        store.put("storage", "runtime-secrets");
        store.put("secrets", mergedSecrets);
        store.put("updatedAt", LocalDateTime.now().toString());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(secretsFile.toFile(), store);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readRuntimeSecrets(Path secretsFile) {
        if (!Files.exists(secretsFile)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> store = objectMapper.readValue(secretsFile.toFile(), new TypeReference<>() {});
            Object secrets = store.getOrDefault("secrets", store);
            if (secrets instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((key, value) -> result.put(String.valueOf(key), value));
                return result;
            }
            return new LinkedHashMap<>();
        } catch (IOException e) {
            throw new RuntimeException("读取模型运行时密钥文件失败", e);
        }
    }

    private Path nextSnapshotFile(Path configDir) {
        LocalDateTime timestamp = LocalDateTime.now();
        Path snapshot = configDir.resolve(SNAPSHOT_PREFIX + timestamp.format(SNAPSHOT_TIMESTAMP) + JSON_SUFFIX);
        while (Files.exists(snapshot)) {
            timestamp = timestamp.plusNanos(1_000_000);
            snapshot = configDir.resolve(SNAPSHOT_PREFIX + timestamp.format(SNAPSHOT_TIMESTAMP) + JSON_SUFFIX);
        }
        return snapshot;
    }

    private boolean isVersionFile(Path path) {
        String filename = path.getFileName().toString();
        return Files.isRegularFile(path)
            && filename.startsWith(SNAPSHOT_PREFIX)
            && filename.endsWith(JSON_SUFFIX)
            && !STORE_FILENAME.equals(filename)
            && SAFE_VERSION_ID.matcher(stripSuffix(filename, JSON_SUFFIX)).matches();
    }

    private Path versionFile(String versionId) {
        validateVersionId(versionId);
        return storeFile().getParent().resolve(versionId + JSON_SUFFIX).normalize();
    }

    private Map<String, Object> versionSummary(Path file, Map<String, Object> store) {
        List<Map<String, Object>> profiles = profileList(store);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("versionId", versionIdFromFile(file));
        summary.put("path", relativeConfigPath(file));
        summary.put("storeVersion", asString(store.get("version"), ""));
        summary.put("storage", asString(store.get("storage"), ""));
        summary.put("defaultProfileId", asString(store.get("defaultProfileId"), ""));
        summary.put("profileCount", profiles.size());
        summary.put("profileIds", profiles.stream().map(profile -> asString(profile.get("profileId"), "")).filter(id -> !id.isBlank()).toList());
        summary.put("createdAt", createdAtFromVersionFile(file));
        summary.put("updatedAt", asString(store.get("updatedAt"), ""));
        return summary;
    }

    private List<Map<String, Object>> sanitizeProfileList(Map<String, Object> store) {
        return profileList(store).stream()
            .map(profile -> sanitizeProfile(profile, false))
            .toList();
    }

    private Map<String, Object> sanitizeStore(Map<String, Object> store) {
        Map<String, Object> sanitized = new LinkedHashMap<>(store);
        sanitized.put("profiles", sanitizeProfileList(store));
        sanitized.put("secretsSanitized", true);
        return sanitized;
    }

    private String versionIdFromFile(Path file) {
        return stripSuffix(file.getFileName().toString(), JSON_SUFFIX);
    }

    private String createdAtFromVersionFile(Path file) {
        String timestamp = stripSuffix(stripPrefix(file.getFileName().toString(), SNAPSHOT_PREFIX), JSON_SUFFIX);
        try {
            return LocalDateTime.parse(timestamp, SNAPSHOT_TIMESTAMP).toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String relativeConfigPath(Path path) {
        Path base = Paths.get(basePath).normalize();
        Path normalized = path.normalize();
        try {
            return base.relativize(normalized).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return normalized.toString();
        }
    }

    private String stripPrefix(String value, String prefix) {
        return value != null && value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    private String stripSuffix(String value, String suffix) {
        return value != null && value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
    }

    private Map<String, Object> exportStore(Map<String, Object> runtimeSecrets) {
        List<ModelProfile> profiles = modelProfileRepository.findAllByOrderByCreatedAtAsc();
        String defaultProfileId = profiles.stream()
            .filter(profile -> Boolean.TRUE.equals(profile.getDefaultProfile()))
            .map(ModelProfile::getProfileId)
            .findFirst()
            .orElse(profiles.isEmpty() ? "" : profiles.get(0).getProfileId());
        Map<String, Object> store = new LinkedHashMap<>();
        store.put("version", "2.0.0");
        store.put("storage", "database");
        store.put("secrets", "runtime-ref");
        store.put("secretsFile", "config/" + RUNTIME_SECRETS_FILENAME);
        store.put("defaultProfileId", defaultProfileId);
        store.put("profiles", profiles.stream().map(profile -> toRuntimeExportMap(profile, runtimeSecrets)).toList());
        store.put("updatedAt", LocalDateTime.now().toString());
        return store;
    }

    private Map<String, Object> defaultStore() {
        Map<String, Object> mainModel = new LinkedHashMap<>();
        mainModel.put("provider", "mock");
        mainModel.put("model", "mock-local");
        mainModel.put("temperature", 0.7);
        mainModel.put("maxTokens", 4000);
        mainModel.put("timeout", 60);
        mainModel.put("mock", true);

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("profileId", "default_mock");
        profile.put("profileName", "默认Mock模型");
        profile.put("description", "离线/mock 环境使用的默认模型配置");
        profile.put("mainModel", mainModel);
        profile.put("fastModel", new LinkedHashMap<>(mainModel));
        profile.put("fallbackModels", List.of(new LinkedHashMap<>(mainModel)));
        profile.put("createdAt", LocalDateTime.now().toString());
        profile.put("updatedAt", LocalDateTime.now().toString());
        profile.put("enabled", true);

        Map<String, Object> store = new LinkedHashMap<>();
        store.put("version", "1.0.0");
        store.put("defaultProfileId", "default_mock");
        store.put("profiles", List.of(profile));
        store.put("createdAt", LocalDateTime.now().toString());
        store.put("updatedAt", LocalDateTime.now().toString());
        return store;
    }

    private ModelProfile buildEntity(String profileId, Map<String, Object> request, ModelProfile existing) {
        ModelProfile profile = existing == null ? new ModelProfile() : existing;
        LocalDateTime now = LocalDateTime.now();
        profile.setProfileId(profileId);
        profile.setProfileName(asString(request.get("profileName"), asString(request.get("name"), existing != null ? existing.getProfileName() : profileId)));
        profile.setDescription(asString(request.get("description"), existing != null ? existing.getDescription() : ""));
        profile.setEnabled(asBooleanFlexible(request.get("enabled"), existing == null || Boolean.TRUE.equals(existing.getEnabled())));
        profile.setUpdatedAt(now);
        if (profile.getCreatedAt() == null) {
            profile.setCreatedAt(parseDateTime(request.get("createdAt"), now));
        }

        Map<String, Object> mainModel = modelValue("mainModel", request, existing != null ? existing.getMainModel() : null);
        validateModel(mainModel, "mainModel");
        profile.setMainModel(mainModel);
        profile.setFastModel(modelValue("fastModel", request, existing != null ? existing.getFastModel() : null));
        profile.setEmbeddingModel(modelValue("embeddingModel", request, existing != null ? existing.getEmbeddingModel() : null));
        profile.setRerankModel(modelValue("rerankModel", request, existing != null ? existing.getRerankModel() : null));
        if (request.containsKey("fallbackModels")) {
            profile.setFallbackModels(modelList(request.get("fallbackModels")));
        } else if (existing == null || existing.getFallbackModels() == null) {
            profile.setFallbackModels(List.of());
        }
        if (existing == null) {
            profile.setDefaultProfile(false);
        }
        return profile;
    }

    private Map<String, Object> modelValue(String key, Map<String, Object> request, Map<String, Object> existing) {
        if (request.containsKey(key)) {
            return mergeModel(existing, asMap(request.get(key)));
        }
        if (existing != null) {
            return existing;
        }
        return "mainModel".equals(key) ? Map.of() : null;
    }

    private Map<String, Object> mergeModel(Map<String, Object> existing, Map<String, Object> update) {
        Map<String, Object> model = existing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(existing);
        update.forEach((key, value) -> {
            if ("apiKey".equals(key) || "api_key".equals(key)) {
                String incoming = asString(value, "");
                if (!incoming.isBlank()) {
                    model.put("apiKey", encryptIfNeeded(incoming));
                }
            } else if ("apiKeyRef".equals(key) || "api_key_ref".equals(key)) {
                String secret = runtimeSecret(asString(value, ""));
                if (!secret.isBlank()) {
                    model.put("apiKey", encryptIfNeeded(secret));
                }
            } else {
                model.put(key, value);
            }
        });
        model.put("provider", asString(model.get("provider"), "mock"));
        model.put("model", asString(model.get("model"), "mock-local"));
        model.put("temperature", asDouble(model.get("temperature"), 0.7));
        model.put("maxTokens", asInteger(model.get("maxTokens"), asInteger(model.get("max_tokens"), 4000)));
        model.put("topP", asDouble(model.get("topP"), asDouble(model.get("top_p"), 1.0)));
        model.put("timeout", asInteger(model.get("timeout"), 60));
        return model;
    }

    private void validateModel(Map<String, Object> model, String label) {
        if (model == null || model.isEmpty()) {
            throw new IllegalArgumentException(label + " 不能为空");
        }
        if (asString(model.get("provider"), "").isBlank()) {
            throw new IllegalArgumentException(label + ".provider 不能为空");
        }
        if (asString(model.get("model"), "").isBlank()) {
            throw new IllegalArgumentException(label + ".model 不能为空");
        }
    }

    private List<Map<String, Object>> modelList(Object value) {
        List<Map<String, Object>> models = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> model = mergeModel(Map.of(), asMap(item));
                validateModel(model, "fallbackModel");
                models.add(model);
            }
        }
        return models;
    }

    private Map<String, Object> toMap(ModelProfile profile, boolean includeSecrets) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("profileId", profile.getProfileId());
        map.put("profileName", profile.getProfileName());
        map.put("description", profile.getDescription());
        map.put("enabled", profile.getEnabled());
        map.put("defaultProfile", profile.getDefaultProfile());
        map.put("mainModel", includeSecrets ? profile.getMainModel() : sanitizeModel(profile.getMainModel(), false));
        map.put("fastModel", includeSecrets ? profile.getFastModel() : sanitizeModel(profile.getFastModel(), false));
        map.put("embeddingModel", includeSecrets ? profile.getEmbeddingModel() : sanitizeModel(profile.getEmbeddingModel(), false));
        map.put("rerankModel", includeSecrets ? profile.getRerankModel() : sanitizeModel(profile.getRerankModel(), false));
        map.put("fallbackModels", includeSecrets ? profile.getFallbackModels() : sanitizeModelList(profile.getFallbackModels(), false));
        map.put("createdAt", profile.getCreatedAt() != null ? profile.getCreatedAt().toString() : "");
        map.put("updatedAt", profile.getUpdatedAt() != null ? profile.getUpdatedAt().toString() : "");
        map.put("storage", "database");
        return map;
    }

    private Map<String, Object> toRuntimeExportMap(ModelProfile profile, Map<String, Object> runtimeSecrets) {
        Map<String, Object> map = toMap(profile, true);
        for (String key : MODEL_KEYS) {
            map.put(key, exportModelWithSecretRef(profile.getProfileId(), key, asMap(map.get(key)), runtimeSecrets));
        }
        map.put("fallbackModels", exportFallbackModelsWithSecretRefs(profile.getProfileId(), map.get("fallbackModels"), runtimeSecrets));
        map.put("storage", "database-runtime-export");
        return map;
    }

    private List<Map<String, Object>> exportFallbackModelsWithSecretRefs(
            String profileId,
            Object value,
            Map<String, Object> runtimeSecrets) {
        List<Map<String, Object>> models = new ArrayList<>();
        if (value instanceof List<?> list) {
            int index = 0;
            for (Object item : list) {
                models.add(exportModelWithSecretRef(profileId, "fallbackModels." + index, asMap(item), runtimeSecrets));
                index++;
            }
        }
        return models;
    }

    private Map<String, Object> exportModelWithSecretRef(
            String profileId,
            String modelKey,
            Map<String, Object> model,
            Map<String, Object> runtimeSecrets) {
        if (model == null || model.isEmpty()) {
            return model;
        }
        Map<String, Object> exported = new LinkedHashMap<>(model);
        String apiKey = asString(exported.get("apiKey"), asString(exported.get("api_key"), ""));
        if (!apiKey.isBlank()) {
            String secret = decryptIfNeeded(apiKey);
            String ref = "secret://model-profiles/" + profileId + "/" + modelKey + "/apiKey/" + secretFingerprint(secret);
            runtimeSecrets.put(ref, secret);
            exported.remove("apiKey");
            exported.put("apiKeyRef", ref);
        }
        exported.remove("api_key");
        return exported;
    }

    private Map<String, Object> sanitizeProfile(Map<String, Object> profile, boolean includeSecrets) {
        if (profile == null) {
            return null;
        }
        Map<String, Object> sanitized = new LinkedHashMap<>(profile);
        for (String key : MODEL_KEYS) {
            sanitized.put(key, sanitizeModel(asMap(profile.get(key)), includeSecrets));
        }
        sanitized.put("fallbackModels", sanitizeModelList(profile.get("fallbackModels"), includeSecrets));
        sanitized.put("secretsEncrypted", true);
        return sanitized;
    }

    private List<Map<String, Object>> sanitizeModelList(Object value, boolean includeSecrets) {
        List<Map<String, Object>> models = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                models.add(sanitizeModel(asMap(item), includeSecrets));
            }
        }
        return models;
    }

    private Map<String, Object> sanitizeModel(Map<String, Object> model, boolean includeSecrets) {
        if (model == null || model.isEmpty()) {
            return null;
        }
        Map<String, Object> sanitized = new LinkedHashMap<>(model);
        String apiKey = asString(sanitized.get("apiKey"), asString(sanitized.get("api_key"), ""));
        String apiKeyRef = asString(sanitized.get("apiKeyRef"), asString(sanitized.get("api_key_ref"), ""));
        sanitized.remove("api_key");
        sanitized.remove("api_key_ref");
        if (!includeSecrets) {
            boolean hasApiKey = !apiKey.isBlank() || !apiKeyRef.isBlank();
            sanitized.put("apiKey", apiKey.isBlank() ? "" : maskSecret(apiKey));
            sanitized.put("hasApiKey", hasApiKey);
            sanitized.put("apiKeyEncrypted", isEncrypted(apiKey));
            sanitized.put("apiKeyExternalized", !apiKeyRef.isBlank());
        }
        return sanitized;
    }

    private Map<String, Object> selectModel(Map<String, Object> profile, String taskType) {
        String key = switch (taskType) {
            case "chapter_review", "memory_ingest", "memory_extraction", "summary_generation" -> "fastModel";
            case "embedding" -> "embeddingModel";
            case "rerank" -> "rerankModel";
            default -> "mainModel";
        };
        Map<String, Object> selected = asMap(profile.get(key));
        return selected.isEmpty() ? asMap(profile.get("mainModel")) : selected;
    }

    private void clearDefaultProfile() {
        for (ModelProfile profile : modelProfileRepository.findAll()) {
            if (Boolean.TRUE.equals(profile.getDefaultProfile())) {
                profile.setDefaultProfile(false);
                modelProfileRepository.save(profile);
            }
        }
    }

    private ModelProfile defaultEntityOrNull() {
        return modelProfileRepository.findByDefaultProfileTrue()
            .orElseGet(() -> modelProfileRepository.findAllByOrderByCreatedAtAsc().stream().findFirst().orElse(null));
    }

    private ModelProfile getEntity(String profileId) {
        return modelProfileRepository.findById(profileId)
            .orElseThrow(() -> new ResourceNotFoundException("模型配置不存在: " + profileId));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> profileList(Map<String, Object> store) {
        Object profilesObject = store.get("profiles");
        List<Map<String, Object>> profiles = new ArrayList<>();
        if (profilesObject instanceof List<?> rawList) {
            for (Object item : rawList) {
                if (item instanceof Map<?, ?> raw) {
                    Map<String, Object> profile = new LinkedHashMap<>();
                    raw.forEach((key, value) -> profile.put(String.valueOf(key), value));
                    profiles.add(profile);
                }
            }
        }
        profiles.sort(Comparator.comparing(profile -> asString(profile.get("createdAt"), "")));
        return profiles;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = new LinkedHashMap<>();
            raw.forEach((key, item) -> map.put(String.valueOf(key), item));
            return map;
        }
        return new LinkedHashMap<>();
    }

    private void addCheck(List<Map<String, Object>> checks, String id, boolean passed, String message) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("id", id);
        check.put("passed", passed);
        check.put("message", message);
        checks.add(check);
    }

    private void validateProfileId(String profileId) {
        if (profileId == null || !SAFE_PROFILE_ID.matcher(profileId).matches()) {
            throw new IllegalArgumentException("非法模型配置ID: " + profileId);
        }
    }

    private void validateVersionId(String versionId) {
        if (versionId == null || !SAFE_VERSION_ID.matcher(versionId).matches()) {
            throw new IllegalArgumentException("非法模型配置版本ID: " + versionId);
        }
    }

    private Path storeFile() {
        return Paths.get(basePath, "config", STORE_FILENAME).normalize();
    }

    private Path runtimeSecretsFile() {
        return Paths.get(basePath, "config", RUNTIME_SECRETS_FILENAME).normalize();
    }

    private String runtimeSecret(String ref) {
        if (ref == null || ref.isBlank()) {
            return "";
        }
        Object value = readRuntimeSecrets(runtimeSecretsFile()).get(ref);
        return value == null ? "" : String.valueOf(value);
    }

    private String encryptIfNeeded(String secret) {
        if (secret.isBlank() || isEncrypted(secret)) {
            return secret;
        }
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(deriveKey(), "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return ENCRYPTED_PREFIX
                + Base64.getUrlEncoder().withoutPadding().encodeToString(iv)
                + ":"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext);
        } catch (Exception e) {
            throw new RuntimeException("API Key 加密失败", e);
        }
    }

    private boolean isEncrypted(String secret) {
        return secret != null && secret.startsWith(ENCRYPTED_PREFIX);
    }

    private String decryptIfNeeded(String secret) {
        if (secret.isBlank() || !isEncrypted(secret)) {
            return secret;
        }
        try {
            String payload = secret.substring(ENCRYPTED_PREFIX.length());
            String[] parts = payload.split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("invalid encrypted secret payload");
            }
            byte[] iv = Base64.getUrlDecoder().decode(parts[0]);
            byte[] ciphertext = Base64.getUrlDecoder().decode(parts[1]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(deriveKey(), "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("API Key 解密失败", e);
        }
    }

    private byte[] deriveKey() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("模型配置加密密钥初始化失败", e);
        }
    }

    private String secretFingerprint(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed).substring(0, 16);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate model secret fingerprint", e);
        }
    }

    private String maskSecret(String secret) {
        if (secret.isBlank()) {
            return "";
        }
        if (isEncrypted(secret)) {
            return "enc:****";
        }
        if (secret.length() <= 8) {
            return "****";
        }
        return secret.substring(0, 4) + "****" + secret.substring(secret.length() - 4);
    }

    private LocalDateTime parseDateTime(Object value, LocalDateTime fallback) {
        if (value == null || value.toString().isBlank()) {
            return fallback;
        }
        try {
            return LocalDateTime.parse(value.toString());
        } catch (Exception e) {
            return fallback;
        }
    }

    private String asString(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private Boolean asBooleanFlexible(Object value, Boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return fallback;
    }

    private Integer asInteger(Object value, Integer fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private Double asDouble(Object value, Double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
