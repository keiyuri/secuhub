package kr.co.securance.secuhub.web.menu

/**
 * 사이드바 메뉴 모델(계획서 5.1절 — adminlte-react의 `MenuNode` 판별 유니온을 Kotlin sealed
 * interface로 이식). 재귀 Thymeleaf 프래그먼트(`fragments/sidebar.html`)가 이 구조를 그대로 렌더링한다.
 */
sealed interface MenuNode {
    data class Header(val text: String) : MenuNode

    data class Item(
        val text: String,
        val href: String,
        val icon: String? = null,
        val badge: String? = null,
        // 레거시 SR_Speed_Client의 SR_F_SetupLocation/SR_F_SetupGateGroup처럼 사이드바에서 곧바로
        // 팝업(Bootstrap modal + iframe)으로 띄울 항목 표시. sidebar.html이 이 값을 보고
        // data-popup 속성을 붙이면 static/js/gate-popup-modal.js가 클릭을 가로채 모달로 연다.
        // 페이지 자체(컨트롤러/템플릿)는 그대로 두고 진입 경로만 팝업으로 바꾸는 것이라, 기존
        // 전체 페이지 URL(북마크·직접 접근)은 변경 없이 계속 동작한다.
        val popup: Boolean = false,
    ) : MenuNode

    data class Group(
        val text: String,
        val icon: String? = null,
        val children: List<MenuNode>,
    ) : MenuNode
}
