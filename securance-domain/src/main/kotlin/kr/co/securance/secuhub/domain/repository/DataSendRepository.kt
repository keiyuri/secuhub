package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.DataSend
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
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
}
