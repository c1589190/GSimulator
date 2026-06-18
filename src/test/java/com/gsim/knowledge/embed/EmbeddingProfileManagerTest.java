package com.gsim.knowledge.embed;

import com.gsim.knowledge.KnowledgeSettings;
import com.gsim.knowledge.store.SQLiteKnowledgeStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EmbeddingProfileManager 测试。
 */
@DisplayName("EmbeddingProfileManager")
class EmbeddingProfileManagerTest {

    private SQLiteKnowledgeStore store;
    private EmbeddingProfileManager pm;
    private FakeEmbeddingModel fakeModel;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        store = new SQLiteKnowledgeStore(tempDir.resolve("test.db").toString());
        store.initialize();
        fakeModel = new FakeEmbeddingModel();
        pm = new EmbeddingProfileManager(store, fakeModel);
    }

    @AfterEach
    void tearDown() { store.close(); }

    @Test
    @DisplayName("initialize 创建 profile 并设为 active")
    void initializeCreatesAndSetsActive() {
        pm.initialize();
        var active = pm.getActiveProfile();
        assertTrue(active.isPresent());
        assertEquals(fakeModel.profile().profileId(), active.get().profileId());
        assertEquals("fake", active.get().providerType());
        assertEquals(128, active.get().dimensions());
        assertTrue(active.get().isAvailable());
    }

    @Test
    @DisplayName("initialize 再次调用匹配已有 profile 不重复创建")
    void initializeMatchesExistingProfile() {
        pm.initialize();
        String firstId = pm.getActiveProfile().orElseThrow().profileId();

        // 创建新的 manager 模拟重启
        FakeEmbeddingModel newModel = new FakeEmbeddingModel();
        // 注意：newModel 的 profile 有新的 ID，但 fingerprint 相同
        EmbeddingProfileManager pm2 = new EmbeddingProfileManager(store, newModel);
        pm2.initialize();

        // 应该匹配到旧 profile
        assertEquals(firstId, pm2.getActiveProfile().orElseThrow().profileId());
    }

    @Test
    @DisplayName("无 embedding model 时 getActiveProfile 返回 empty")
    void noModelNoProfile(@TempDir Path tempDir) throws Exception {
        SQLiteKnowledgeStore freshStore = new SQLiteKnowledgeStore(
                tempDir.resolve("fresh.db").toString());
        freshStore.initialize();
        EmbeddingProfileManager emptyPm = new EmbeddingProfileManager(freshStore, null);
        emptyPm.initialize();
        assertTrue(emptyPm.getActiveProfile().isEmpty());
        freshStore.close();
    }

    @Test
    @DisplayName("listProfiles 返回所有 profile")
    void listProfilesWorks() {
        pm.initialize();
        List<EmbeddingProfile> profiles = pm.listProfiles();
        assertEquals(1, profiles.size());
        assertEquals("fake", profiles.get(0).providerType());
    }

    @Test
    @DisplayName("setActiveProfile 切换 active profile")
    void setActiveProfileSwitches() {
        pm.initialize();
        String oldId = pm.getActiveProfile().orElseThrow().profileId();

        // 手动保存另一个 profile
        EmbeddingProfile other = new EmbeddingProfile(
                "test-other", "fake", "fake", "other-model", 64,
                "cosine", 1, "test-fingerprint", "active",
                java.time.Instant.now().toString());
        store.saveProfile(other);
        pm.setActiveProfile("test-other");

        assertEquals("test-other", pm.getActiveProfile().orElseThrow().profileId());
    }

    @Test
    @DisplayName("computeFingerprint 相同配置产生相同指纹")
    void fingerprintIsDeterministic() {
        String fp1 = EmbeddingProfileManager.computeFingerprint("external", "text-embed-3", 1536, "https://api.example.com");
        String fp2 = EmbeddingProfileManager.computeFingerprint("external", "text-embed-3", 1536, "https://api.example.com");
        assertEquals(fp1, fp2);
    }

    @Test
    @DisplayName("computeFingerprint 不同配置产生不同指纹")
    void fingerprintDiffersForDifferentConfig() {
        String fp1 = EmbeddingProfileManager.computeFingerprint("external", "a", 100, "url1");
        String fp2 = EmbeddingProfileManager.computeFingerprint("external", "b", 100, "url1");
        assertNotEquals(fp1, fp2);
    }
}
