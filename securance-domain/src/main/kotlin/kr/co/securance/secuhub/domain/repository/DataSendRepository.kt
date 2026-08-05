package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.DataSend
import org.springframework.data.jpa.repository.JpaRepository

/**
 * `tb_data_snd` 리포지토리. `SendControlJob`(계획서 3.6/5.5절, QUEUED 경로)이
 * 미전송 명령을 폴링할 때 사용한다.
 */
interface DataSendRepository : JpaRepository<DataSend, Long> {
    fun findBySndYnAndChkYnOrderBySndId(sndYn: String, chkYn: String): List<DataSend>
}
