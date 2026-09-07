package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.NetState
import kr.co.securance.secuhub.domain.entity.NetStateId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

/** `tb_net_state` 리포지토리 — `NetCheckJob`(계획서 3.6절)이 연결 생존 상태를 갱신할 때 사용. */
interface NetStateRepository : JpaRepository<NetState, NetStateId> {
    fun findByIdDtlIpAndIdDtlLaneNo(dtlIp: String, dtlLaneNo: Int): List<NetState>

    /** 대시보드 온라인/오프라인 게이트 수 위젯(계획서 5.2절)에 사용. */
    fun countByDtlState(dtlState: String): Long

    /** #3 GateReset 검색 그리드 — 그룹 내 레인들의 현재 연결 상태를 한 번에 조회. */
    fun findByIdGrpId(grpId: Long): List<NetState>

    /**
     * 코드 리뷰 지적(2026-08-28) — [kr.co.securance.secuhub.web.gate.GateTreeService.buildTreeUncached]가
     * 대시보드 트리 재조립 때마다 이 테이블을 `findAll()`로 조건 없이 전량 스캔했다. 위치/그룹/게이트는
     * `useYn` 필터가 이미 적용된 반면 온라인 상태만 필터 없이 전체를 읽어, 비활성화되거나 삭제된
     * 그룹의 잔여 행까지 매번 스캔 대상에 포함시켰다. 트리에 실제로 표시되는 활성 그룹 ID로만
     * 좁혀 조회한다(현재 표시 대상과 동일한 범위로 스캔을 제한).
     */
    fun findByIdGrpIdIn(grpIds: Collection<Long>): List<NetState>

    /**
     * 코드 리뷰 지적 R-8(2026-08-20) 대응 — `applied_seq`가 [seq] 이하인 행에만(=더 최신 쓰기가
     * 아직 적용되지 않았을 때만) [dtlState]/[dtlType]/[dtlId]/[checkTime]/[serverIp]/[serverCd]/
     * [sndRaw]/[applied_seq]를 반영하는 조건부 UPSERT.
     *
     * **컬럼 누락 수정(2026-08-26 dev DB 실측 검증)**: [NetState.serverIp]("이 상태를 보고한 백엔드
     * 인스턴스 IP")는 엔티티에 매핑돼 있었지만 이 메서드가 이 컬럼을 INSERT/UPDATE 절 어디에도
     * 포함하지 않아 실제로는 한 번도 쓰인 적이 없었다 — dev DB(`securance_gate`)에 남아 있던
     * `server_ip` 값은 이 앱이 아니라 예전 코드/레거시가 채운 잔존 데이터였다(2026-08-26 확인,
     * `applied_seq`가 최근에 갱신된 행조차 `server_ip`가 실제 접속 서버와 무관하게 고정돼 있었음).
     * 다중 인스턴스 배포에서 "어느 인스턴스가 이 레인의 연결 상태를 마지막으로 관측했는지" 추적할
     * 유일한 컬럼이므로, `dtl_state`/`check_time`과 동일한 `applied_seq` 가드로 함께 갱신한다.
     *
     * **컬럼 누락 수정 2(2026-09-07, `tb_net_state` 재점검 요청)**: 이 메서드가 `dtl_type`/`dtl_id`도
     * INSERT/UPDATE 절에 전혀 담지 않아, 신규 서버 경로가 최초로 만든 행은 두 컬럼이 영구히 NULL로
     * 남아 있었다 — 호출부([kr.co.securance.secuhub.server.connection.GateConnectionRegistryImpl])는
     * 이미 `GateLaneInfo`/`GateDetail`에서 값을 캐시해 들고 있었으므로, 그저 이 쿼리에 실어 보내지
     * 않은 것이 원인이었다. `server_cd`(이 값을 기록한 인스턴스가 SERVER/CLIENT 중 어느 연결
     * 방향으로 동작 중이었는지, [kr.co.securance.secuhub.server.config.GatewayMode].name)와
     * `snd_raw`(그 시점의 원시 수신 패킷 16진 문자열, 커넥션 종료로 인한 오프라인 전이처럼 관련
     * 패킷이 없으면 null)도 이번에 함께 채운다 — 레거시 `usp_net_check_data`가 이미 `snd_raw`는
     * 채우고 있었으므로(V1), 두 쓰기 경로의 컬럼 커버리지를 맞췄다(V3 마이그레이션에서 레거시
     * 프로시저에도 `dtl_type`/`server_cd`를 동일하게 추가).
     *
     * [kr.co.securance.secuhub.server.connection.GateConnectionRegistryImpl]이 예전에 쓰던
     * 인메모리 시퀀스 맵 + 락([ReentrantLock])을 이 한 문장으로 대체한다 — DB가 `INSERT ...
     * ON DUPLICATE KEY UPDATE`를 행 단위로 원자적으로 실행하므로, "클레임 후 조회/저장" 사이에
     * 다른 실행이 끼어드는 TOCTOU 창 자체가 존재하지 않는다. 인메모리 상태가 없으므로 다중
     * 인스턴스 배포에서도 그대로 정확하다(인메모리 락은 인스턴스 로컬이라 다중 인스턴스에서는
     * 애초에 순서 역전을 막지 못했다).
     *
     * `applied_seq <= VALUES(applied_seq)`(엄격한 `<`가 아니라 `<=`)인 이유: 같은 작업이
     * [kotlin.coroutines] 타임아웃 뒤 재시도되면 같은 `seq`로 다시 이 메서드를 호출한다 — 그
     * 재시도도 정상적으로 반영되어야 하므로(멱등성, [kr.co.securance.secuhub.server.db.GateDbWriteQueue]
     * 클래스 KDoc "주의(멱등성)" 참고) 동일 시퀀스의 재적용은 허용한다.
     *
     * **`mod_date` 누락 수정(2026-09-08, 사용자 요청 — 운영 DB 실측)**: 이 메서드가 `mod_date`를
     * INSERT/UPDATE 절 어디에도 담지 않아, 신규 서버 경로가 쓰는 행은 이 컬럼이 영구히 NULL로
     * 남아 있었다. [NetState.modDate]의 예전 KDoc은 "네이티브 UPSERT가 채운다"고 적어 뒀지만
     * 이는 잘못된 가정이었다 — 그 문장은 레거시 `usp_net_check_data`(V1)가 `mod_date = NOW()`를
     * 명시적으로 갱신하는 것만 확인하고, 이 메서드는 다시 검증하지 않은 채 넘어간 결과였다. 또한
     * 운영 DB(192.168.0.26:28031) 실측 결과 `tb_net_state.mod_date` 컬럼 자체가 이 저장소의 V1
     * 마이그레이션이 정의한 것과 달리 `ON UPDATE current_timestamp()`가 없는 정의였다 — DB
     * 트리거에 의존할 수 없으므로 여기서 직접 `NOW()`를 실어 보낸다(레거시 SP와 동일하게
     * `applied_seq` 가드 하에서만 갱신).
     *
     * **테스트 커버리지의 한계(2026-08-20)**: `ON DUPLICATE KEY UPDATE`는 MariaDB/MySQL 전용 문법이라
     * 이 프로젝트의 `@DataJpaTest`가 쓰는 H2(2.4.240, `MODE=MySQL` 포함)로는 파싱조차 되지 않는다
     * (실측 확인 — "Syntax error ... ON DUPLICATE KEY UPDATE"). 그래서 [DataSendRepository.claimForSend]
     * 처럼 실제 JPA 프로바이더 위에서 조건부 로직을 검증하는 리포지토리 테스트를 이 메서드에는 추가하지
     * 못했다 — Testcontainers로 실제 MariaDB를 띄우는 인프라가 이 프로젝트에 아직 없다(도입은 범위
     * 밖). 대신 [kr.co.securance.secuhub.server.connection.GateConnectionRegistryImplTest]가 이
     * 메서드에 어떤 인자가 전달되는지(mock)까지만 검증한다 — SQL 자체의 정확성은 수동 검증에
     * 의존한다는 뜻이므로, 이 쿼리를 고치는 사람은 반드시 실제 MariaDB에서 직접 실행해 확인할 것.
     */
    @Modifying
    @Transactional
    @Query(
        value = """
        INSERT INTO tb_net_state
            (dtl_ip, dtl_lane_no, loc_id, grp_id, dtl_state, dtl_type, dtl_id, check_time, applied_seq,
             server_ip, server_cd, snd_raw, mod_date)
        VALUES
            (:dtlIp, :dtlLaneNo, :locId, :grpId, :dtlState, :dtlType, :dtlId, :checkTime, :seq,
             :serverIp, :serverCd, :sndRaw, NOW())
        ON DUPLICATE KEY UPDATE
            dtl_state = IF(applied_seq <= VALUES(applied_seq), VALUES(dtl_state), dtl_state),
            dtl_type = IF(applied_seq <= VALUES(applied_seq), VALUES(dtl_type), dtl_type),
            dtl_id = IF(applied_seq <= VALUES(applied_seq), VALUES(dtl_id), dtl_id),
            check_time = IF(applied_seq <= VALUES(applied_seq), VALUES(check_time), check_time),
            server_ip = IF(applied_seq <= VALUES(applied_seq), VALUES(server_ip), server_ip),
            server_cd = IF(applied_seq <= VALUES(applied_seq), VALUES(server_cd), server_cd),
            snd_raw = IF(applied_seq <= VALUES(applied_seq), VALUES(snd_raw), snd_raw),
            mod_date = IF(applied_seq <= VALUES(applied_seq), VALUES(mod_date), mod_date),
            applied_seq = IF(applied_seq <= VALUES(applied_seq), VALUES(applied_seq), applied_seq)
        """,
        nativeQuery = true,
    )
    fun upsertIfNewer(
        @Param("dtlIp") dtlIp: String,
        @Param("dtlLaneNo") dtlLaneNo: Int,
        @Param("locId") locId: Long,
        @Param("grpId") grpId: Long,
        @Param("dtlState") dtlState: String,
        @Param("dtlType") dtlType: Int?,
        @Param("dtlId") dtlId: Long?,
        @Param("checkTime") checkTime: String,
        @Param("seq") seq: Long,
        @Param("serverIp") serverIp: String,
        @Param("serverCd") serverCd: String,
        @Param("sndRaw") sndRaw: String?,
    )

    /**
     * `applied_seq`로 쓸 값을 DB의 전역 시퀀스(`tb_net_state_seq`, V32 마이그레이션)에서 하나
     * 발급받는다.
     *
     * **Codex 적대적 리뷰 재지적(2026-08-20, [P1]) 대응**: 예전에는
     * [kr.co.securance.secuhub.server.connection.GateConnectionRegistryImpl]이 이 값을 벽시계
     * (`System.currentTimeMillis()`) + 인스턴스 판별자(난수)로 프로세스 로컬 발급했다. 서로 다른
     * 인스턴스가 같은 밀리초에 같은 레인의 상태를 갱신하면, 판별자는 값 충돌만 피할 뿐 실제 이벤트
     * 발생 순서를 표현하지 못해 나중에 발생한 이벤트가 우연히 더 작은 seq를 받아 `upsertIfNewer`에서
     * 거부될 수 있었다. `NEXT VALUE FOR`는 MariaDB SEQUENCE 엔진이 원자적으로 직렬화해 발급하는
     * 단일 전역 카운터이므로, 어느 인스턴스가 먼저 이 메서드를 호출했는지가 곧 반환값의 크기 순서가
     * 된다 — NTP 동기화 여부와 무관하게 인스턴스 간 순서가 보장된다.
     */
    @Query(value = "SELECT NEXT VALUE FOR tb_net_state_seq", nativeQuery = true)
    fun nextSeq(): Long
}
