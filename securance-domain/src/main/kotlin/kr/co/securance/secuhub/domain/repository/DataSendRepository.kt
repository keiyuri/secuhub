package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.DataSend
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

/**
 * `tb_data_snd` 리포지토리. `SendControlJob`(계획서 3.6/5.5절, QUEUED 경로)이
 * 미전송 명령을 폴링할 때 사용한다.
 */
interface DataSendRepository : JpaRepository<DataSend, Long> {
    // Opus 전체 리뷰 지적: 무제한 전체 조회 대신 Pageable로 배치 상한을 둔다
    // (경로는 idx_data_snd_poll(snd_yn, chk_yn, snd_id) 인덱스를 그대로 탄다 — V4 마이그레이션).
    fun findBySndYnAndChkYnOrderBySndId(sndYn: String, chkYn: String, pageable: Pageable): List<DataSend>
}
