package kr.co.securance.secuhub

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * securance-app 진입점(계획서 7절).
 *
 * 패키지를 모듈 공통 루트(`kr.co.securance.secuhub`)에 두어, 컴포넌트 스캔/JPA 엔티티·리포지토리
 * 스캔이 기본 설정만으로 common/protocol/domain/server/scheduler/web 전 모듈을 커버하게 한다.
 */
@SpringBootApplication
class SecuranceApplication

fun main(args: Array<String>) {
    runApplication<SecuranceApplication>(*args)
}
