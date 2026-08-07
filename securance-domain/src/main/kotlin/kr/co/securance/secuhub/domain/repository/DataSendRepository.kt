package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.DataSend
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * `tb_data_snd` 리포지토리. `SendControlJob`(계획서 3.6/5.5절, QUEUED 경로)이
 * 미전송 명령을 폴링할 때 사용한다.
 */
interface DataSendRepository : JpaRepository<DataSend, Long> {
    // Opus 전체 리뷰 지적: 무제한 전체 조회 대신 Pageable로 배치 상한을 둔다
    // (경로는 idx_data_snd_poll(snd_yn, chk_yn, snd_id) 인덱스를 그대로 탄다 — V4 마이그레이션).
    //
    // [Codex 적대적 리뷰 지적] 단순히 (snd_yn, chk_yn) ORDER BY snd_id LIMIT batchSize만으로는
    // 계속 실패하는 큐 앞쪽 행이 매 폴링마다 다시 뽑혀 뒤쪽 정상 행을 영구히 가릴 수 있다
    // (헤드 오브 라인 차단). next_attempt_at이 아직 도래하지 않은 행은 조회 대상에서 제외해,
    // 실패한 행을 건너뛰고 다음 행을 처리할 수 있게 한다.
    @Query(
        "SELECT d FROM DataSend d WHERE d.sndYn = :sndYn AND d.chkYn = :chkYn " +
            "AND (d.nextAttemptAt IS NULL OR d.nextAttemptAt <= :now) ORDER BY d.sndId",
    )
    fun findEligiblePending(
        @Param("sndYn") sndYn: String,
        @Param("chkYn") chkYn: String,
        @Param("now") now: LocalDateTime,
        pageable: Pageable,
    ): List<DataSend>

    /**
     * [Codex 어드버서리얼 리뷰 지적] `findEligiblePending`으로 뽑은 행을 그대로 처리하면, 같은 행을
     * 두 스케줄러 인스턴스(또는 우발적 이중 기동)가 동시에 조회해 같은 제어 명령을 중복 물리 전송할
     * 수 있다 — `@DisallowConcurrentExecution`은 JVM 하나 안에서만 유효하고, RAMJobStore는 여러
     * JVM을 조정하지 못한다. 이 메서드는 `next_attempt_at`을 조건부 UPDATE(WHERE에 현재 상태를 명시)로
     * 미래(리스 만료 시각)로 밀어 그 행을 원자적으로 "선점(claim)"한다 — UPDATE된 행 수(0 또는 1)로
     * 선점 성공 여부를 판단할 수 있어, 두 인스턴스가 동시에 호출해도 DB 락이 한쪽만 통과시킨다.
     * 처리가 끝나면 `SendControlJob`이 최종 상태(성공 시 null, 실패 시 재시도 쿨다운)로 다시 저장해
     * 리스 값을 덮어쓴다.
     */
    @Modifying
    @Transactional
    @Query(
        "UPDATE DataSend d SET d.nextAttemptAt = :leaseUntil WHERE d.sndId = :sndId " +
            "AND d.sndYn = :sndYn AND d.chkYn = :chkYn " +
            "AND (d.nextAttemptAt IS NULL OR d.nextAttemptAt <= :now)",
    )
    fun claim(
        // 테스트에서 Mockito any() 매처(null 반환)로 스텁할 때 primitive long 언박싱 NPE를 피하기
        // 위해 nullable로 둔다 — 실사용처(SendControlJob)는 항상 non-null 값만 넘긴다.
        @Param("sndId") sndId: Long?,
        @Param("sndYn") sndYn: String,
        @Param("chkYn") chkYn: String,
        @Param("now") now: LocalDateTime,
        @Param("leaseUntil") leaseUntil: LocalDateTime,
    ): Int
}
