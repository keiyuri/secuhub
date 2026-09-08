package kr.co.securance.secuhub.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Lob
import jakarta.persistence.Table
import kr.co.securance.secuhub.domain.converter.YnConverter
import java.time.LocalDateTime

/**
 * `tb_time` — #7 SR_F_Schedule / #15 SR_P_Timezone의 타임존(요일별 시간대 4슬롯) 등록 정보.
 *
 * 클래스명은 레거시 컬럼/화면명("Timezone")을 그대로 따르되 `java.time.TimeZone`/`java.util.TimeZone`과의
 * 혼동을 피하기 위해 `GateTimeZone`으로 명명한다(계획서 Phase 5).
 *
 * [timezoneHexData]는 [kr.co.securance.secuhub.protocol.TimeZoneCommandBuilder.buildTimezoneHexData]가
 * 만든 26바이트를 16진 문자열로 저장한 값 — 저장 시점의 게이트 전송 페이로드를 그대로 보존해
 * 이후 "전체 동기화"(재전송) 시 슬롯을 다시 계산하지 않고 그대로 재사용한다(레거시
 * `SelectTimeZoneData`/`pbSync_MouseClick`과 동일한 접근).
 */
@Entity
@Table(name = "tb_time")
class GateTimeZone(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "timezone_id")
    val timezoneId: Long? = null,

    @Column(name = "timezone_name", nullable = false, length = 100)
    var timezoneName: String,

    @Column(name = "timezone_desc", length = 200)
    var timezoneDesc: String? = null,

    @Column(name = "timezone_fr1", length = 10) var timezoneFr1: String? = null,
    @Column(name = "timezone_to1", length = 10) var timezoneTo1: String? = null,
    @Column(name = "timezone_day1", length = 100) var timezoneDay1: String? = null,

    @Column(name = "timezone_fr2", length = 10) var timezoneFr2: String? = null,
    @Column(name = "timezone_to2", length = 10) var timezoneTo2: String? = null,
    @Column(name = "timezone_day2", length = 100) var timezoneDay2: String? = null,

    @Column(name = "timezone_fr3", length = 10) var timezoneFr3: String? = null,
    @Column(name = "timezone_to3", length = 10) var timezoneTo3: String? = null,
    @Column(name = "timezone_day3", length = 100) var timezoneDay3: String? = null,

    @Column(name = "timezone_fr4", length = 10) var timezoneFr4: String? = null,
    @Column(name = "timezone_to4", length = 10) var timezoneTo4: String? = null,
    @Column(name = "timezone_day4", length = 100) var timezoneDay4: String? = null,

    // 컬럼 타입 재점검(2026-09-09, 개발 DB 실측): 실제 DB는 `timezone_hex_data tinytext`
    // (최대 255바이트)인데 엔티티는 `@Lob`으로 선언돼 있었다 — `@Lob`은 Hibernate/MariaDB
    // 방언에서 통상 LONGTEXT로 매핑돼 실제 컬럼(TINYTEXT)과 타입 카테고리가 어긋난다. 현재
    // 페이로드(26바이트 16진 문자열, 52자)는 여유가 크지만 방언 매핑을 실제 컬럼에 맞춘다.
    @Column(name = "timezone_hex_data", nullable = false, length = 255, columnDefinition = "tinytext")
    var timezoneHexData: String,

    @Convert(converter = YnConverter::class)
    @Column(name = "use_yn", nullable = false)
    var useYn: Boolean = true,

    @Column(name = "reg_user", length = 20)
    var regUser: String? = null,

    @Column(name = "reg_date", nullable = false)
    var regDate: LocalDateTime = LocalDateTime.now(),
)
