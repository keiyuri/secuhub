package kr.co.securance.secuhub.web.schedule

import jakarta.validation.Validation
import jakarta.validation.Validator
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 회귀 방지 테스트(2026-08-20 Opus 전체 리뷰 지적) — [TimeZoneSlotForm]에 범위 검증이 전혀 없어,
 * `fromHour=99` 같은 값이 컨트롤러의 `binding.hasErrors()`를 통과한 뒤
 * [kr.co.securance.secuhub.protocol.TimeZoneCommandBuilder.Slot]의 `require(fromHour in 0..23)`에서
 * `IllegalArgumentException`(→ 500 에러 페이지)으로 터지던 문제. [TimeZoneForm.slot1]~`slot4`의
 * `@field:Valid` 캐스케이드까지 함께 검증한다 — 캐스케이드가 없으면 아래 `@Min/@Max`는 폼 바인딩
 * 단계에서 전혀 평가되지 않는다.
 */
class TimeZoneSlotFormTest {

    private lateinit var validator: Validator

    @BeforeTest
    fun setUp() {
        val factory = Validation.buildDefaultValidatorFactory()
        validator = factory.validator
    }

    @Test
    fun `기본값(미사용 슬롯)은 위반이 없다`() {
        assertTrue(validator.validate(TimeZoneSlotForm()).isEmpty())
    }

    @Test
    fun `범위를 벗어난 fromHour는 단독 검증에서 거부된다`() {
        val violations = validator.validate(TimeZoneSlotForm(fromHour = 99))
        assertTrue(violations.isNotEmpty(), "fromHour=99는 TimeZoneCommandBuilder.Slot의 require(0..23)와 동일한 범위를 위반해야 한다")
    }

    @Test
    fun `범위를 벗어난 toMinute는 단독 검증에서 거부된다`() {
        val violations = validator.validate(TimeZoneSlotForm(toMinute = 999))
        assertTrue(violations.isNotEmpty())
    }

    @Test
    fun `TimeZoneForm은 slot의 범위 위반을 캐스케이드로 잡아낸다`() {
        // @field:Valid 캐스케이드가 없으면 이 테스트가 실패한다 — TimeZoneForm 자체 필드(timezoneName)만
        // 검증되고 중첩된 slot1의 위반은 조용히 통과된다.
        val form = TimeZoneForm(timezoneName = "야간조", slot1 = TimeZoneSlotForm(fromHour = 25))
        val violations = validator.validate(form)
        assertTrue(violations.isNotEmpty(), "TimeZoneForm.slot1의 범위 위반이 캐스케이드 검증되어야 한다")
    }
}
