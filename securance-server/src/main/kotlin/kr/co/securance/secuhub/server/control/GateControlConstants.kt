package kr.co.securance.secuhub.server.control

/**
 * `DEFAULT_GATE_TYPE`(코드 리뷰 지적, 2026-08-28) — [QueuedGateControlService]와
 * [DirectGateControlService] 둘 다 게이트 타입을 알 수 없을 때(레인 캐시 미스 등) 쓰던
 * 리터럴 `1`을 각자 따로 하드코딩하고 있었다. 기본 게이트 타입 값이 바뀌면 한쪽만 갱신되고
 * 다른 쪽은 놓치기 쉬운 구조라 공용 상수로 뽑는다.
 */
internal const val DEFAULT_GATE_TYPE = 1
