package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.DataReceive
import kr.co.securance.secuhub.domain.entity.DataReceiveAck
import kr.co.securance.secuhub.domain.entity.DataReceiveFail
import kr.co.securance.secuhub.domain.entity.DataReceiveLog
import org.springframework.data.jpa.repository.JpaRepository

interface DataReceiveRepository : JpaRepository<DataReceive, Long>

interface DataReceiveFailRepository : JpaRepository<DataReceiveFail, Long>

interface DataReceiveAckRepository : JpaRepository<DataReceiveAck, Long>

interface DataReceiveLogRepository : JpaRepository<DataReceiveLog, Long>
