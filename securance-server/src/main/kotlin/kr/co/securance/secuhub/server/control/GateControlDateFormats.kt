package kr.co.securance.secuhub.server.control

import java.time.format.DateTimeFormatter

/**
 * `tb_data_snd.snd_date`(스키마 주석 규정 포맷, 초 단위) — [DirectGateControlService]/
 * [QueuedGateControlService]/[GateControlDispatcher]가 각자 동일한 상수로 중복 선언하고
 * 있었다(2026-09-03 코드 리뷰 지적: 세 곳이 리터럴 "yyyyMMddHHmmss"로 따로 정의돼 있어, 포맷을
 * 바꿔야 할 때 한 곳을 빠뜨리면 파싱 불일치가 생길 수 있었다). 실제 전송/발송 로직 자체는 세
 * 클래스가 서로 다른 방식(즉시 전송 vs 큐잉, 코덱 폴백 등)으로 동작하므로 그 부분은 합치지
 * 않고, 우연히 동일했던 상수만 여기로 뺀다.
 */
object GateControlDateFormats {
    val SEND_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
}
