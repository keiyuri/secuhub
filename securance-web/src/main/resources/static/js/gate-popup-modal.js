// 게이트 관리 팝업(SR_F_SetupLocation/SR_F_SetupGateGroup 대응) — 사이드바에서 data-popup="true"가
// 붙은 링크를 클릭하면 전체 페이지 이동 대신 layout/dashboard-layout.html의 공용 모달을 연다.
// 실제 화면은 기존 URL을 iframe으로 그대로 불러오며, 그 안에서 이어지는 수정/취소 등 내부 네비게이션도
// window.self !== window.top 판별(dashboard-layout.html의 .gp-embed) 덕분에 계속 팝업 모드로 남는다.
document.addEventListener("DOMContentLoaded", function () {
  var modalEl = document.getElementById("gate-popup-modal");
  if (!modalEl || typeof bootstrap === "undefined") return;

  var bsModal = bootstrap.Modal.getOrCreateInstance(modalEl);
  var iframe = document.getElementById("gate-popup-modal-frame");
  var titleEl = document.getElementById("gate-popup-modal-title");
  var spinner = document.getElementById("gate-popup-modal-spinner");

  function showLoading() {
    spinner.classList.remove("d-none");
    iframe.classList.add("d-none");
  }

  document.addEventListener("click", function (e) {
    var link = e.target.closest('a[data-popup="true"]');
    if (!link) return;

    // [코드리뷰 반영] 수정 키(Ctrl/Cmd/Shift/Alt) 또는 가운데 클릭(새 탭/새 창으로 열기)일 때는
    // 가로채지 않고 브라우저 기본 동작(새 탭 열기 등)을 그대로 둔다 — 일반 좌클릭(button === 0,
    // 수정 키 없음)일 때만 모달로 연다.
    if (e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return;

    e.preventDefault();

    titleEl.textContent = link.getAttribute("data-popup-title") || link.textContent.trim();
    showLoading();
    iframe.src = link.getAttribute("href");
    bsModal.show();
  });

  iframe.addEventListener("load", function () {
    // about:blank(모달 닫힘 시 리셋)로 인한 load 이벤트에는 스피너를 다시 보여줄 필요 없음.
    if (iframe.src === "about:blank") return;
    spinner.classList.add("d-none");
    iframe.classList.remove("d-none");
  });

  modalEl.addEventListener("hidden.bs.modal", function () {
    // 닫힌 뒤에도 이전 화면 상태가 남아 있지 않도록 iframe을 비운다 — 다음에 열 때 최신 목록부터 시작.
    iframe.src = "about:blank";
    showLoading();
  });
});
