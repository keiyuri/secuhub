package kr.co.securance.secuhub.web.gate

import jakarta.validation.Validation
import jakarta.validation.Validator
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 회귀 방지 테스트: [GateControlController.kt]에서 tb_data_snd.snd_type_cd에 저장되는
 * userMode/secuMode 값에 @Pattern 화이트리스트를 추가한 수정을 검증한다. 화이트리스트는
 * [kr.co.securance.secuhub.protocol.GateControlCommandBuilder.buildModeChangeCommand]가 실제로
 * 인식하는 controlType 접두어(ctrlTp1)/보안모드 부분 문자열과 동일해야 한다.
 */
class ModeChangeFormTest {

    private lateinit var validator: Validator

    @BeforeTest
    fun setUp() {
        val factory = Validation.buildDefaultValidatorFactory()
        validator = factory.validator
    }

    private fun violationCount(userMode: String, secuMode: String) =
        validator.validate(ModeChangeForm(userMode = userMode, secuMode = secuMode)).size

    @Test
    fun `허용된 운영-보안 모드 조합은 통과한다`() {
        val allowedUserModes = listOf("CC", "CF", "FC", "FF", "OP", "RP", "CL", "CS", "CX", "XC", "FX", "XF")
        val allowedSecuModes = listOf("LM", "MM", "HM")

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
        // Opus 전체 리뷰 지적 회귀 방지: 검증 이전에는 이 값이 그대로
        // tb_data_snd.snd_type_cd = "MODE_INJECTED;--LM" 형태로 저장됐다.
        assertTrue(violationCount("INJECTED", "LM") > 0)
        assertTrue(violationCount("CC", "INJECTED") > 0)
    }

    @Test
    fun `빈 문자열은 NotBlank로 거부된다`() {
        assertTrue(violationCount("", "LM") > 0)
        assertTrue(violationCount("CC", "") > 0)
    }

    @Test
    fun `대소문자를 구분하므로 소문자 입력은 거부된다`() {
        // 컨트롤러/빌더 쪽에서 대문자로 변환하기 전에 폼 검증이 먼저 실행되므로, 폼 자체는
        // 대문자 화이트리스트만 허용해야 한다(소문자를 허용하면 우연히 통과하는 값이 생길 수 있음).
        assertTrue(violationCount("cc", "lm") > 0)
    }
}
