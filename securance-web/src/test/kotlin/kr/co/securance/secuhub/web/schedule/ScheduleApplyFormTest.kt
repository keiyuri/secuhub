package kr.co.securance.secuhub.web.schedule

import jakarta.validation.Validation
import jakarta.validation.Validator
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 회귀 방지 테스트: Codex 리뷰 지적 반영 — [ScheduleController.kt]의 tb_data_snd.snd_type_cd에
 * 저장되는 userMode/secuMode 값에 @Pattern 화이트리스트를 추가한 수정을 검증한다. 화이트리스트는
 * schedule/index.html이 실제로 제공하는 선택지(=USER_MODE_SLOTS/SECU_MODE_SLOTS의 키)와 동일해야
 * 한다. [kr.co.securance.secuhub.web.gate.ModeChangeFormTest]와 동일한 취지의 테스트다.
 */
class ScheduleApplyFormTest {

    private lateinit var validator: Validator

    @BeforeTest
    fun setUp() {
        val factory = Validation.buildDefaultValidatorFactory()
        validator = factory.validator
    }

    private fun violationCount(userMode: String, secuMode: String) =
        validator.validate(ScheduleApplyForm(userMode = userMode, secuMode = secuMode)).size

    @Test
    fun `화면이 제공하는 모든 운영-보안 모드 조합은 통과한다`() {
        // schedule/index.html의 select option 값 = USER_MODE_SLOTS/SECU_MODE_SLOTS 키와 동일.
        val allowedUserModes = ScheduleApplyService.USER_MODE_SLOTS.keys
        val allowedSecuModes = ScheduleApplyService.SECU_MODE_SLOTS.keys

        allowedUserModes.forEach { userMode ->
            allowedSecuModes.forEach { secuMode ->
                assertTrue(
                    violationCount(userMode, secuMode) == 0,
                    "userMode=$userMode, secuMode=$secuMode 조합은 위반이 없어야 한다",
                )
            }
        }
    }

    @Test
    fun `화이트리스트에 없는 임의 문자열은 거부된다`() {
        // 수정 이전에는 이 값이 그대로 tb_data_snd.snd_type_cd = "MODE_INJECTED;--NA" 형태로 저장됐다.
        assertTrue(violationCount("INJECTED", "NA") > 0)
        assertTrue(violationCount("CC", "INJECTED") > 0)
    }

    @Test
    fun `대소문자를 구분하므로 소문자 입력은 거부된다`() {
        assertTrue(violationCount("cc", "na") > 0)
    }
}
