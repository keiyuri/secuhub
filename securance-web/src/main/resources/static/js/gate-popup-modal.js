// 게이트 관리 팝업(SR_F_SetupLocation/SR_F_SetupGateGroup 대응) — 사이드바에서 data-popup="true"가
// 붙은 링크를 클릭하면 전체 페이지 이동 대신 layout/dashboard-layout.html의 공용 모달을 연다.
// 실제 화면은 기존 URL을 iframe으로 그대로 불러오며, 그 안에서 이어지는 수정/취소 등 내부 네비게이션도
// window.self !== window.top 판별(dashboard-layout.html의 .gp-embed) 덕분에 계속 팝업 모드로 남는다.
// [게이트 트리뷰 연동] gate-tree.js가 위치/그룹 노드 우클릭 메뉴에서 "위치 관리"/"게이트그룹
// 관리" 팝업을 열 때도 이 모달을 재사용한다 — 클릭 위임(data-popup 링크)과 동일한 오픈 로직을
// window.GatePopupModal.open(url, title)로 노출해 중복 구현하지 않는다. ready=false인 동안은
// 아래 DOMContentLoaded 초기화가 아직(또는 끝내) 되지 않은 상태 — 호출부(gate-tree.js)가 이
// 값을 보고 모달 대신 전체 페이지 이동으로 폴백할 수 있게 한다(사이드바 링크의 href 폴백과
// 달리 트리 메뉴 항목은 href="#"라 대체 경로가 없어 무동작 스텁만으로는 조용히 실패한다).
window.GatePopupModal = { ready: false, open: function () {} };

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

  // 버그 수정: 팝업(게이트 관리 하위 메뉴)은 iframe 모달로만 열리고 부모 창의 URL은 그대로라서,
  // 서버 렌더링(fragments/sidebar.html의 currentPath 비교)이 재실행되지 않는다 — 그 결과 팝업을
  // 여는 동안 이전 페이지(예: 대시보드)의 사이드바 강조색이 계속 남아 있고, 클릭한 항목은 강조되지
  // 않는 문제가 있었다. 팝업을 열 때 클라이언트에서 강조를 직접 옮기고, 닫으면 원래 상태로 되돌린다.
  var savedActiveLink = null;

  function highlightPopupLink(link) {
    var sidebar = document.querySelector(".app-sidebar");
    if (!sidebar) return;
    if (!savedActiveLink) {
      // 모달이 열려 있는 동안 이미 강조를 옮겨 놓은 이전 링크가 이번 것이면 원래 상태를 덮어쓰지
      // 않도록, 최초 1회만(savedActiveLink가 비어 있을 때만) 현재 강조 링크를 저장해 둔다.
      savedActiveLink = sidebar.querySelector(".nav-link.active");
    }
    Array.prototype.forEach.call(sidebar.querySelectorAll(".nav-link.active"), function (el) {
      el.classList.remove("active");
    });
    if (link) link.classList.add("active");
  }

  function restoreActiveLink() {
    var sidebar = document.querySelector(".app-sidebar");
    if (sidebar) {
      Array.prototype.forEach.call(sidebar.querySelectorAll(".nav-link.active"), function (el) {
        el.classList.remove("active");
      });
      if (savedActiveLink) savedActiveLink.classList.add("active");
    }
    savedActiveLink = null;
  }

  function openPopup(url, title, sourceLink) {
    if (!url) return;
    titleEl.textContent = title || "게이트 관리";
    showLoading();
    iframe.src = url;
    bsModal.show();
    highlightPopupLink(sourceLink);
  }

  // DOMContentLoaded 이전에 window.GatePopupModal.open()이 호출될 일은 없지만(다른 스크립트도
  // 전부 DOM 로드 이후 실행), 방어적으로 초기화 이후 실제 구현으로 교체한다.
  window.GatePopupModal.open = openPopup;
  window.GatePopupModal.ready = true;

  document.addEventListener("click", function (e) {
    var link = e.target.closest('a[data-popup="true"]');
    if (!link) return;

    // [코드리뷰 반영] 수정 키(Ctrl/Cmd/Shift/Alt) 또는 가운데 클릭(새 탭/새 창으로 열기)일 때는
    // 가로채지 않고 브라우저 기본 동작(새 탭 열기 등)을 그대로 둔다 — 일반 좌클릭(button === 0,
    // 수정 키 없음)일 때만 모달로 연다.
    if (e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return;

    e.preventDefault();
    openPopup(link.getAttribute("href"), link.getAttribute("data-popup-title") || link.textContent.trim(), link);
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
    // 팝업을 여는 동안 옮겨 놓았던 사이드바 강조를 원래(서버가 currentPath로 렌더링한) 상태로 되돌린다.
    restoreActiveLink();
  });
});
