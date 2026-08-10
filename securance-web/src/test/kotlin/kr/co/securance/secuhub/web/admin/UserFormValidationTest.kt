package kr.co.securance.secuhub.web.admin

import jakarta.validation.Validation
import jakarta.validation.Validator
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [UserForm] Bean Validation 검증 — 전체 프로젝트 재감사(2026-08-10)에서 지적된 항목:
 * 비밀번호 최소 길이/복잡도 검증이 전혀 없어 한 글자짜리 비밀번호도 통과하던 문제를
 * `@Pattern` 제약으로 막았는지 실제 [Validator]로 확인한다.
 */
class UserFormValidationTest {

    private val validator: Validator = Validation.buildDefaultValidatorFactory().validator

    private fun violationsFor(password: String) =
        validator.validateProperty(UserForm(userId = "tester", password = password, userName = "테스터"), "password")

    @Test
    fun `수정 시 기존 비밀번호를 유지하기 위한 빈 문자열은 통과한다`() {
        assertTrue(violationsFor("").isEmpty())
    }

    @Test
    fun `7자 이하 비밀번호는 거부된다`() {
        assertFalse(violationsFor("abc1234").isEmpty())
    }

    @Test
    fun `8자 이상 비밀번호는 통과한다`() {
        assertTrue(violationsFor("abcd1234").isEmpty())
    }

    @Test
    fun `64자를 초과하는 비밀번호는 거부된다`() {
        assertFalse(violationsFor("a".repeat(65)).isEmpty())
    }
}
