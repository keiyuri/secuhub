package kr.co.securance.secuhub.web.menu

import org.springframework.stereotype.Component

/** 1차 스캐폴드 사이드바 메뉴 — 후속 작업에서 DB(권한별 메뉴) 기반으로 대체 가능. */
@Component
class MenuProvider {
    fun menu(): List<MenuNode> = listOf(
        MenuNode.Header("모니터링"),
        MenuNode.Item("대시보드", "/dashboard", icon = "bi-speedometer2"),
        MenuNode.Group(
            "게이트 관리",
            icon = "bi-door-open",
            children = listOf(
                MenuNode.Item("위치/그룹", "/gates/locations"),
                MenuNode.Item("게이트 상세", "/gates/details"),
                MenuNode.Item("연결 상태", "/gates/net-state"),
            ),
        ),
        MenuNode.Header("운영"),
        MenuNode.Item("제어 명령 이력", "/control/history", icon = "bi-terminal"),
        MenuNode.Item("사용자 관리", "/admin/users", icon = "bi-people"),
    )
}
