package com.gsim.api;

import com.gsim.app.AppConfig;
import com.gsim.app.ApplicationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SessionManager 测试。
 */
@DisplayName("SessionManager")
class SessionManagerTest {

    private SessionManager sessionManager;
    private ApplicationContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        AppConfig config = AppConfig.forTesting();
        ctx = new ApplicationContext(config);
        ctx.initialize();
        sessionManager = new SessionManager(ctx);
    }

    @Test
    @DisplayName("null sessionId 应使用 default")
    void shouldUseDefaultForNullSessionId() {
        var session = sessionManager.getOrCreateSession(null);
        assertNotNull(session);
        assertEquals(1, sessionManager.sessionCount());
        assertTrue(sessionManager.listSessions().contains("default"));
    }

    @Test
    @DisplayName("blank sessionId 应使用 default")
    void shouldUseDefaultForBlankSessionId() {
        var session = sessionManager.getOrCreateSession("");
        assertNotNull(session);
        assertTrue(sessionManager.listSessions().contains("default"));
    }

    @Test
    @DisplayName("getOrCreateSession 应创建新 session")
    void shouldCreateNewSession() {
        var session = sessionManager.getOrCreateSession("s1");
        assertNotNull(session);
        assertEquals("s1", sessionManager.listSessions().iterator().next());
    }

    @Test
    @DisplayName("两个 session 应不共享 InteractionContext")
    void twoSessionsShouldHaveIndependentContext() {
        var s1 = sessionManager.getOrCreateSession("s1");
        var s2 = sessionManager.getOrCreateSession("s2");

        assertNotSame(s1, s2);
        assertNotSame(s1.getContext(), s2.getContext());

        // 验证共享底层 services
        assertSame(s1.getCampaignService(), s2.getCampaignService());
        assertSame(s1.getTurnService(), s2.getTurnService());
        assertSame(s1.getPlayerActionService(), s2.getPlayerActionService());
    }

    @Test
    @DisplayName("相同 sessionId 应返回相同 session")
    void sameSessionIdShouldReturnSameSession() {
        var s1 = sessionManager.getOrCreateSession("s1");
        var s2 = sessionManager.getOrCreateSession("s1");

        assertSame(s1, s2);
    }

    @Test
    @DisplayName("getSession 不存在时应返回 null")
    void getSessionShouldReturnNullForUnknown() {
        assertNull(sessionManager.getSession("nonexistent"));
    }

    @Test
    @DisplayName("listSessions 应返回所有 session ID")
    void listSessionsShouldReturnAllIds() {
        sessionManager.getOrCreateSession("s1");
        sessionManager.getOrCreateSession("s2");
        sessionManager.getOrCreateSession("s3");

        var ids = sessionManager.listSessions();
        assertEquals(3, ids.size());
        assertTrue(ids.contains("s1"));
        assertTrue(ids.contains("s2"));
        assertTrue(ids.contains("s3"));
    }
}
