package com.novel.system.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.system.entity.ModelProfile;
import com.novel.system.repository.ModelProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelProfileServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    PythonClientService pythonClientService;

    @Mock
    ModelProfileRepository modelProfileRepository;

    private final Map<String, ModelProfile> profiles = new LinkedHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ModelProfileService modelProfileService;

    @BeforeEach
    void setUp() {
        modelProfileService = new ModelProfileService(pythonClientService, modelProfileRepository);
        ReflectionTestUtils.setField(modelProfileService, "basePath", tempDir.toString());
        ReflectionTestUtils.setField(modelProfileService, "encryptionKey", "unit-test-encryption-key");

        when(modelProfileRepository.count()).thenAnswer(invocation -> (long) profiles.size());
        when(modelProfileRepository.existsById(any())).thenAnswer(invocation -> profiles.containsKey(invocation.getArgument(0)));
        when(modelProfileRepository.findById(any())).thenAnswer(invocation -> Optional.ofNullable(profiles.get(invocation.getArgument(0))));
        when(modelProfileRepository.findAll()).thenAnswer(invocation -> orderedProfiles());
        when(modelProfileRepository.findAllByOrderByCreatedAtAsc()).thenAnswer(invocation -> orderedProfiles());
        when(modelProfileRepository.findByDefaultProfileTrue()).thenAnswer(invocation ->
            profiles.values().stream()
                .filter(profile -> Boolean.TRUE.equals(profile.getDefaultProfile()))
                .findFirst()
        );
        when(modelProfileRepository.save(any(ModelProfile.class))).thenAnswer(invocation -> {
            ModelProfile profile = invocation.getArgument(0);
            if (profile.getCreatedAt() == null) {
                profile.setCreatedAt(LocalDateTime.now());
            }
            if (profile.getUpdatedAt() == null) {
                profile.setUpdatedAt(LocalDateTime.now());
            }
            profiles.put(profile.getProfileId(), profile);
            return profile;
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            profiles.clear();
            return null;
        }).when(modelProfileRepository).deleteAll();
        org.mockito.Mockito.doAnswer(invocation -> {
            ModelProfile profile = invocation.getArgument(0);
            profiles.remove(profile.getProfileId());
            return null;
        }).when(modelProfileRepository).delete(any(ModelProfile.class));
    }

    @Test
    void restoresModelProfilesFromSnapshotVersion() throws Exception {
        writeLegacyStore(profileRequest("alpha", "Alpha", "secret-alpha", true), "alpha");
        modelProfileService.listProfiles();
        modelProfileService.createProfile(profileRequest("beta", "Beta", "secret-beta", false));

        List<Map<String, Object>> versions = modelProfileService.listVersions();
        assertThat(versions).isNotEmpty();
        String versionId = versions.get(0).get("versionId").toString();

        modelProfileService.updateProfile("alpha", Map.of(
            "profileName", "Alpha Changed",
            "mainModel", modelConfig("mock", "mock-changed", "secret-new"),
            "setDefault", false
        ));
        modelProfileService.deleteProfile("beta");

        Map<String, Object> detail = modelProfileService.getVersion(versionId);
        assertThat(detail.get("profileCount")).isEqualTo(1);
        assertThat(detail.get("store")).isInstanceOf(Map.class);
        Map<String, Object> alphaSnapshot = profileList(detail).stream()
            .filter(profile -> "alpha".equals(profile.get("profileId")))
            .findFirst()
            .orElseThrow();
        Map<String, Object> mainModel = modelMap(alphaSnapshot, "mainModel");
        assertThat(mainModel.get("apiKey")).isNotEqualTo("secret-alpha");
        assertThat(mainModel.get("hasApiKey")).isEqualTo(true);

        Map<String, Object> restored = modelProfileService.restoreVersion(versionId, Map.of("actor", "test"));

        assertThat(restored.get("status")).isEqualTo("restored");
        assertThat(restored.get("restoredProfileCount")).isEqualTo(1);
        assertThat(restored.get("defaultProfileId")).isEqualTo("alpha");
        assertThat(profiles).containsOnlyKeys("alpha");
        assertThat(profiles.get("alpha").getProfileName()).isEqualTo("Alpha");
        assertThat(profiles.get("alpha").getMainModel().get("apiKey").toString()).startsWith("enc:v1:");
        assertThat(profiles.get("alpha").getDefaultProfile()).isTrue();
        assertThat(Files.exists(tempDir.resolve("config").resolve("model_profiles.json"))).isTrue();
        assertThat(modelProfileService.listVersions()).hasSizeGreaterThanOrEqualTo(3);
    }

    private void writeLegacyStore(Map<String, Object> profile, String defaultProfileId) throws Exception {
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        Map<String, Object> store = new LinkedHashMap<>();
        store.put("version", "1.0.0");
        store.put("defaultProfileId", defaultProfileId);
        store.put("profiles", List.of(profile));
        store.put("updatedAt", LocalDateTime.now().toString());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(configDir.resolve("model_profiles.json").toFile(), store);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> profileList(Map<String, Object> response) {
        return (List<Map<String, Object>>) response.get("profiles");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> modelMap(Map<String, Object> profile, String key) {
        return (Map<String, Object>) profile.get(key);
    }

    private List<ModelProfile> orderedProfiles() {
        return new ArrayList<>(profiles.values()).stream()
            .sorted(Comparator.comparing(profile -> profile.getCreatedAt() == null ? LocalDateTime.MIN : profile.getCreatedAt()))
            .toList();
    }

    private Map<String, Object> profileRequest(String profileId, String profileName, String apiKey, boolean setDefault) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("profileId", profileId);
        request.put("profileName", profileName);
        request.put("enabled", true);
        request.put("setDefault", setDefault);
        request.put("mainModel", modelConfig("mock", "mock-local", apiKey));
        request.put("fastModel", modelConfig("mock", "mock-fast", ""));
        return request;
    }

    private Map<String, Object> modelConfig(String provider, String model, String apiKey) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("provider", provider);
        config.put("model", model);
        config.put("temperature", 0.7);
        config.put("maxTokens", 4000);
        config.put("timeout", 60);
        config.put("mock", true);
        if (!apiKey.isBlank()) {
            config.put("apiKey", apiKey);
        }
        return config;
    }
}
