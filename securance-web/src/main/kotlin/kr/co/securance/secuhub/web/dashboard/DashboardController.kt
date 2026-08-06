package kr.co.securance.secuhub.web.dashboard

import kr.co.securance.secuhub.web.menu.MenuProvider
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/** 위젯 기반 관리자 대시보드(계획서 15번 요청, 5.2절)의 1차 스캐폴드 컨트롤러. */
@Controller
class DashboardController(
    private val dashboardService: DashboardService,
    private val menuProvider: MenuProvider,
) {
    @GetMapping("/", "/dashboard")
    fun dashboard(model: Model): String {
        val view = dashboardService.loadDashboard()
        model.addAttribute("menu", menuProvider.menu())
        model.addAttribute("pageTitle", "대시보드")
        model.addAttribute("summary", view.summary)
        model.addAttribute("recentErrors", view.recentErrors)
        return "dashboard"
    }
}
