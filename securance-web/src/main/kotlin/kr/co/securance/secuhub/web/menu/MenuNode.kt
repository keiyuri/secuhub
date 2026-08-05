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
    ) : MenuNode

    data class Group(
        val text: String,
        val icon: String? = null,
        val children: List<MenuNode>,
    ) : MenuNode
}
