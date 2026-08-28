package kr.co.securance.secuhub.server.config

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * [ServerModeConfig] 기동 시점 검증 — 코드 리뷰 지적(2026-08-28)에 대한 회귀 테스트.
 */
class ServerModeConfigTest {

    @Test
    fun `기본값은 정상 구성된다`() {
        ServerModeConfig()
    }

    @Test
    fun `dbWriterShards가 0이면 기동을 막는다`() {
        assertFailsWith<IllegalArgumentException> {
            ServerModeConfig(dbWriterShards = 0)
        }
    }

    @Test
    fun `dbWriterShards가 음수면 기동을 막는다`() {
        assertFailsWith<IllegalArgumentException> {
            ServerModeConfig(dbWriterShards = -1)
        }
    }

    @Test
    fun `actorQueueCapacity가 0이면 기동을 막는다`() {
        // kotlinx.coroutines Channel(capacity=0)은 RENDEZVOUS 특수값이라 대부분의 제출이
        // 조용히 실패한다 — GateTaskRejectedException 백프레셔 설계 의도가 무력화되므로 막는다.
        assertFailsWith<IllegalArgumentException> {
            ServerModeConfig(actorQueueCapacity = 0)
        }
    }

    @Test
    fun `actorQueueCapacity가 음수면 기동을 막는다`() {
        // -1(UNLIMITED)/-2(CONFLATED) 등 kotlinx.coroutines 특수값과 충돌한다.
        assertFailsWith<IllegalArgumentException> {
            ServerModeConfig(actorQueueCapacity = -1)
        }
    }
}
