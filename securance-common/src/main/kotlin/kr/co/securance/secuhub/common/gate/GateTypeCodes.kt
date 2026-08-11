package kr.co.securance.secuhub.common.gate

/**
 * 게이트 타입(`tb_gate_dtl.dtl_type`) 원시 코드값.
 *
 * 계획서 4.3절: 게이트 타입은 향후 계속 추가될 수 있는 확장 값이므로 닫힌 Kotlin `enum class`로
 * 고정하지 않고, DB의 `tb_code`(코드그룹 `GATE_TYPE`) 마스터 테이블로 관리한다.
 * 이 object는 "현재 알려진" 원시 코드값 상수만 제공한다 — 신규 타입 추가는
 * 코드 배포 없이 `tb_code` 데이터 추가만으로 이뤄지며, 이 상수 목록에 없는 값도
 * DB/코덱 레지스트리 조회로 정상 동작해야 한다(하드코딩 분기 금지).
 *
 * securance-protocol(코덱 선택)과 securance-domain(V1 마이그레이션 시드 데이터)이 공유한다.
 */
object GateTypeCodes {
    /** Speed Gate — SR-1400 계열. Flap Gate와 프로토콜을 공유한다(계획서 3.4절). */
    const val SPEED_GATE = 1

    /** Flap Gate. Speed Gate와 동일 프로토콜(`SpeedFlapGateProtocolCodec`)을 사용한다. */
    const val FLAP_GATE = 2

    /**
     * Turn Gate. `FastGate Protocol Ver1_2020102601_01.md` 대조 확인 결과 Speed/Flap과 완전히
     * 동일한 봉투(Header/Command/Tail, GATE_SETTING/GATE_STATUS/GATE_MOTOR 객체)를 쓴다 — 상태
     * 데이터의 GATE TYPE 필드 값(0x03)으로만 구분될 뿐 별도 프로토콜이 아니다(2026-08-12 D4 정정,
     * `SpeedFlapGateProtocolCodec` 참고). "1차 스캐폴드 미구현" 서술은 규격 문서 확보 전 남은
     * 낡은 코멘트였다.
     */
    const val TURN_GATE = 3

    /**
     * Fast Gate. Status/제어(Pause·Slide Open·Slide Close 포함)/TimeZone/Holiday는 Speed/Flap과
     * 동일한 `SpeedFlapGateProtocolCodec`으로 처리된다(2026-08-12 D4 정정) — 다만 모터 설정만은
     * 전용 Object Code `FAST_GATE_MOTOR(0x50)`을 쓰는 별도 페이로드(72바이트, TURN/SLIDE 모터
     * 포지션·RPM·보정값)라 아직 코덱이 없다(계획서 P11, 미전환).
     */
    const val FAST_GATE = 4
}
