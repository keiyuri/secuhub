package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.DataSend
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

/**
 * `tb_data_snd` 리포지토리. `SendControlJob`(계획서 3.6/5.5절, QUEUED 경로)이
 * 미전송/미확인 명령을 폴링할 때 사용하고, [JpaSpecificationExecutor]는 `/control/history`
 * 조회 화면(2026-08-11, B5 죽은 링크 해소)이 동적 필터 조합에 사용한다.
 */
interface DataSendRepository : JpaRepository<DataSend, Long>, JpaSpecificationExecutor<DataSend> {

    /**
     * 전송 대기(`snd_yn='N' AND chk_yn='N'`) 명령을 오래된 순으로 읽는다.
     *
     * [Pageable]로 상한을 두는 이유: 장비가 장시간 끊겨 있어 대기열이 수만 건 쌓인 상태에서
     * 전건을 한 번에 읽으면 폴링 주기(기본 1초)마다 대량 조회가 반복되어 DB가 먼저 무너진다.
     * 레거시 `SelectSendDataServer`에는 상한이 없었다.
     */
    @Query(
        """
        SELECT s FROM DataSend s
        WHERE s.sndYn = 'N' AND s.chkYn = 'N'
        ORDER BY s.sndId ASC
        """,
    )
    fun findPendingCommands(pageable: Pageable): List<DataSend>

    /** 전송은 됐으나 장비 ACK를 아직 못 받은 명령(`snd_yn='Y' AND chk_yn='N'`). */
    @Query(
        """
        SELECT s FROM DataSend s
        WHERE s.sndYn = 'Y' AND s.chkYn = 'N'
        ORDER BY s.sndId ASC
        """,
    )
    fun findAwaitingAck(pageable: Pageable): List<DataSend>

    /**
     * 물리 전송 **전에** 이 인스턴스가 명령을 선점한다(Codex 리뷰 P1 — "DB에서 명령을 선점한
     * 뒤 물리 전송하세요" 대응).
     *
     * 조회 시점의 [version]과 `snd_yn='N'`을 조건으로 건 원자적 UPDATE다. 두 인스턴스가 재접속
     * 전환 시점에 같은 대기 행을 동시에 집어가도, DB가 이 UPDATE 자체를 직렬화하므로 단 한
     * 인스턴스만 영향받은 행 수(1)를 돌려받는다 — 그 인스턴스만 물리 전송을 수행해야 한다.
     * 이전 구조(먼저 [GateConnectionRegistryImpl.sendToLane]로 보내고 그 다음 낙관적 잠금으로
     * 저장)는 두 인스턴스가 모두 전송까지 마친 뒤에야 경합이 드러나 중복 물리 전송을 막지
     * 못했다.
     *
     * 같은 (`dtl_ip`, `dtl_lane_no`)에 이미 ACK 대기 중인 행(`snd_yn='Y' AND chk_yn='N'`)이
     * 있으면 선점 자체를 실패시킨다(Codex 어드버서리얼 리뷰 대응 — "레인당 동시 in-flight 명령을
     * 1건으로 제한하라"). ACK 프레임에 명령 식별자가 없어 [GateConnectionState]의 FIFO 추정으로
     * 상관시키는 이상, 같은 레인에 2건 이상이 동시에 대기 중이면 ACK 1건이 아직 수행되지 않은
     * 명령까지 확인 처리할 위험이 있다(예: OPEN 확정 후 대기 중이던 RESET까지 함께 확정되어
     * 장애 기록이 잘못 해제됨) — 이를 애초에 발생 불가능하게 만드는 것이 유일한 안전한 방법이다.
     * 이 조건은 인스턴스 로컬이 아니라 DB 레벨(`NOT EXISTS` 서브쿼리)이라 다중 인스턴스에서도
     * 동일 레인에 대해 동시에 2건이 선점되지 않는다.
     *
     * @return 이번 호출이 선점에 성공했으면 1, 다른 인스턴스가 이미 처리했거나(버전 불일치)
     *   이미 전송된 행이거나 같은 레인에 ACK 대기 중인 다른 행이 있으면 0.
     */
    @Modifying
    @Transactional
    @Query(
        """
        UPDATE DataSend s SET s.sndYn = 'Y', s.sndServer = :server, s.version = s.version + 1
        WHERE s.sndId = :id AND s.version = :version AND s.sndYn = 'N'
          AND NOT EXISTS (
              SELECT 1 FROM DataSend o
              WHERE o.dtlIp = s.dtlIp AND o.dtlLaneNo = s.dtlLaneNo
                AND o.sndYn = 'Y' AND o.chkYn = 'N'
          )
        """,
    )
    fun claimForSend(@Param("id") id: Long, @Param("version") version: Long, @Param("server") server: String): Int

    /**
     * [claimForSend]로 선점한 뒤 물리 전송이 실패(대기열 포화/레인 불일치 등)했을 때 대기 상태로
     * 되돌린다 — 재접속/다음 폴링에서 자연히 재시도되게 한다.
     */
    @Modifying
    @Transactional
    @Query("UPDATE DataSend s SET s.sndYn = 'N', s.version = s.version + 1 WHERE s.sndId = :id")
    fun releaseClaim(@Param("id") id: Long): Int

    /**
     * [findPendingCommands]가 헤드 오브 라인 차단에 빠지지 않도록, 오래도록 전송조차 되지 못한
     * 대기 명령을 일괄 실패 확정한다(코드 리뷰 지적 R-1 대응).
     *
     * `findPendingCommands`는 `snd_id ASC LIMIT n`으로 가장 오래된 대기 행부터 읽는다. 대상
     * 게이트가 장시간 미접속이거나(레인 자체가 철거됨 등) 존재하지 않는 IP로 잘못 발행된 명령은
     * [GateControlDispatcher.sendPendingCommands]가 매 폴링마다 `skipped`로 건너뛸 뿐 상태를
     * 바꾸지 않는다 — 그 결과 그 행들이 폴링 창(`batchSize`)의 앞자리를 영구히 점유해, 이후
     * 발행된 정상 명령(더 큰 `snd_id`)이 조회 자체가 되지 않는다. 화면은 "명령 접수 완료"를
     * 보여주지만 실제로는 전송이 시도조차 되지 않는 상태로 굳는다.
     *
     * `sndDate`는 `yyyyMMddHHmmss` 고정 폭 숫자 문자열이라 사전식 비교가 시각 비교와 동일하다
     * ([kr.co.securance.secuhub.web.control.ControlHistoryController]가 이미 같은 방식으로 범위
     * 조회에 쓰고 있다). [cutoff]보다 오래된 미전송 대기 행만 `chk_yn='F'`로 실패 확정해 폴링
     * 대상에서 제거한다 — 게이트가 이후 재접속하더라도 이미 유효기간이 지난 명령을 뒤늦게
     * 실행하지 않는 편이 안전하다(예: 오래전 요청한 개방 명령이 지금 갑자기 실행되는 사고 방지).
     *
     * `snd_yn='N'`(전송 자체를 시도하지 않은) 행만 대상으로 한다 — 이미 전송되어 ACK를 기다리는
     * 행(`snd_yn='Y'`)은 [GateControlDispatcher.reapAckTimeouts]가 별도로 재시도/실패 확정한다.
     *
     * @return 이번 호출로 실패 확정된 행 수.
     */
    @Modifying
    @Transactional
    @Query(
        """
        UPDATE DataSend s SET s.chkYn = 'F', s.version = s.version + 1
        WHERE s.sndYn = 'N' AND s.chkYn = 'N' AND s.sndDate < :cutoff
        """,
    )
    fun expireStalePending(@Param("cutoff") cutoff: String): Int

    /**
     * 코드 리뷰 지적 D-3 대응: `tb_data_snd`는 제어 명령을 발행할 때마다 1행씩 쌓이는데도 D5 보관
     * 정책(2026-08-12) 대상에서 빠져 있었다. `chk_yn IN ('Y','F')`(확인 완료 또는 실패 확정 —
     * [DataSend]의 상태 전이표 참고)로 **종결된 행만** 대상으로 한다 — 아직 대기 중(N,N)이거나
     * 전송 후 ACK 대기 중(Y,N)인 행은 오래됐더라도 지우면 안 된다(전송 이력/재시도 근거가 사라짐).
     * `snd_date`는 `yyyyMMddHHmmss` 문자열이라 사전식 비교가 시간 비교와 일치한다.
     */
    @Modifying
    @Transactional
    @Query(
        value = "DELETE FROM tb_data_snd WHERE chk_yn IN ('Y', 'F') AND snd_date < :cutoff LIMIT :batchSize",
        nativeQuery = true,
    )
    fun deleteBatchOlderThan(@Param("cutoff") cutoff: String, @Param("batchSize") batchSize: Int): Int
}
