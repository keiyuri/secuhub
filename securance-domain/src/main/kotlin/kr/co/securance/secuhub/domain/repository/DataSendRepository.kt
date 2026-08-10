package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.DataSend
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

/**
 * `tb_data_snd` 리포지토리. `SendControlJob`(계획서 3.6/5.5절, QUEUED 경로)이
 * 미전송/미확인 명령을 폴링할 때 사용한다.
 */
interface DataSendRepository : JpaRepository<DataSend, Long> {

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
}
