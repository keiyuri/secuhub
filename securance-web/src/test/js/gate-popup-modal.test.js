// gate-popup-modal.js 회귀 테스트 — 버그: 게이트 관리 하위 메뉴(팝업)는 iframe 모달로만 열리고
// 부모 창의 URL이 바뀌지 않아, 서버 렌더링(fragments/sidebar.html의 currentPath 비교)이 재실행
// 되지 않는다. 그 결과 팝업을 여는 동안 이전 페이지(예: 대시보드)의 사이드바 강조색이 그대로
// 남아 있고, 실제로 클릭한 항목은 강조되지 않는 문제가 있었다 — 이 파일은 그 수정을 검증한다.
//
// 프로덕션 파일은 DOMContentLoaded에서 한 번 초기화되는 순정 스크립트라 모듈 시스템이 없다.
// require 캐시를 비워 매 테스트마다 새로 로드하고, jsdom이 이미 지나간 DOMContentLoaded를
// 재발생시키지 않으므로 로드 직후 수동으로 이벤트를 dispatch해 초기화를 트리거한다.
const path = require('path');

const GATE_POPUP_MODAL_PATH = path.resolve(
  __dirname,
  '../../main/resources/static/js/gate-popup-modal.js',
);

function setupDom() {
  document.body.innerHTML = `
    <aside class="app-sidebar">
      <a href="/dashboard" class="nav-link active">대시보드</a>
      <a href="/gates/groups" class="nav-link" data-popup="true" data-popup-title="게이트그룹">게이트그룹</a>
    </aside>
    <div id="gate-popup-modal">
      <div id="gate-popup-modal-title"></div>
      <div id="gate-popup-modal-spinner" class="d-none"></div>
      <iframe id="gate-popup-modal-frame" class="d-none"></iframe>
    </div>
  `;
}

// 프로덕션 코드는 document에 리스너를 등록한다(DOMContentLoaded 1회 + 그 안에서 click 위임 1회).
// document 자체는 테스트 파일 전체에서 재사용되는 전역이라(매 테스트 초기화되는 건 body.innerHTML
// 뿐), require 캐시를 비우고 다시 require()해도 이전 테스트가 등록한 리스너가 그대로 남는다 —
// 그 상태로 다음 테스트가 수동으로 DOMContentLoaded를 dispatch하면 "이전 테스트의 낡은 클로저"가
// 같은 id의 새 DOM 노드를 대상으로 다시 초기화되어 두 세대의 리스너가 동시에 반응하는 오염이
// 생긴다. document.addEventListener를 테스트 동안만 가로채 등록된 리스너를 기록해 두고, 매
// 테스트 종료 후 제거해 격리한다.
let documentListeners = [];

function loadGatePopupModal() {
  delete require.cache[require.resolve(GATE_POPUP_MODAL_PATH)];
  const originalAddEventListener = document.addEventListener.bind(document);
  document.addEventListener = function (type, listener, options) {
    documentListeners.push([type, listener]);
    return originalAddEventListener(type, listener, options);
  };
  try {
    require(GATE_POPUP_MODAL_PATH);
    document.dispatchEvent(new Event('DOMContentLoaded'));
  } finally {
    document.addEventListener = originalAddEventListener;
  }
}

function clickPopupLink(link) {
  var evt = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 });
  link.dispatchEvent(evt);
}

beforeEach(() => {
  setupDom();
  // 실제 bootstrap.bundle.min.js는 CDN/vendor 스크립트로만 로드되므로 테스트에서는 최소한의
  // Modal 스텁만 제공한다 — show()는 사이드바 강조 로직과 무관해 아무 동작도 하지 않아도 된다.
  global.bootstrap = {
    Modal: {
      getOrCreateInstance: () => ({ show: () => {} }),
    },
  };
});

afterEach(() => {
  documentListeners.forEach(([type, listener]) => document.removeEventListener(type, listener));
  documentListeners = [];
  delete global.bootstrap;
});

describe('팝업(게이트 관리 하위 메뉴) 클릭 시 사이드바 강조 이동', () => {
  test('팝업 링크를 클릭하면 대시보드 강조가 사라지고 클릭한 링크가 강조된다', () => {
    loadGatePopupModal();
    var dashboard = document.querySelector('a[href="/dashboard"]');
    var popupLink = document.querySelector('a[data-popup="true"]');
    expect(dashboard.classList.contains('active')).toBe(true);

    clickPopupLink(popupLink);

    expect(dashboard.classList.contains('active')).toBe(false);
    expect(popupLink.classList.contains('active')).toBe(true);
  });

  test('모달을 닫으면(hidden.bs.modal) 원래 강조 상태로 되돌아간다', () => {
    loadGatePopupModal();
    var dashboard = document.querySelector('a[href="/dashboard"]');
    var popupLink = document.querySelector('a[data-popup="true"]');

    clickPopupLink(popupLink);
    expect(popupLink.classList.contains('active')).toBe(true);

    document.getElementById('gate-popup-modal')
      .dispatchEvent(new Event('hidden.bs.modal'));

    expect(popupLink.classList.contains('active')).toBe(false);
    expect(dashboard.classList.contains('active')).toBe(true);
  });

  test('gate-tree.js처럼 링크 없이 window.GatePopupModal.open()으로 열어도 대시보드 강조는 사라진다', () => {
    loadGatePopupModal();
    var dashboard = document.querySelector('a[href="/dashboard"]');

    window.GatePopupModal.open('/gates/groups?parentId=1', '게이트그룹 관리');

    expect(dashboard.classList.contains('active')).toBe(false);
  });

  test('수정 키(Ctrl 등)를 누른 클릭은 가로채지 않아 강조가 바뀌지 않는다', () => {
    loadGatePopupModal();
    var dashboard = document.querySelector('a[href="/dashboard"]');
    var popupLink = document.querySelector('a[data-popup="true"]');

    var evt = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0, ctrlKey: true });
    popupLink.dispatchEvent(evt);

    expect(dashboard.classList.contains('active')).toBe(true);
    expect(popupLink.classList.contains('active')).toBe(false);
  });

  test('모달이 열린 채로(닫지 않고) 다른 팝업으로 전환해도 최초 원래 강조(대시보드)가 유지되다가 닫을 때 복원된다', () => {
    // gate-tree.js 우클릭 메뉴 등 window.GatePopupModal.open()을 연속 호출하는 경로 — 이미 열린
    // 모달 위에 새 URL로 다시 열면(Bootstrap Modal.show()는 이미 열려 있으면 아무 것도 하지 않음)
    // savedActiveLink가 "전환 중 임시로 강조된 링크"로 덮어써지면 안 된다(대시보드를 잃어버림).
    // 반드시 .app-sidebar "안"에 추가해야 한다 — highlightPopupLink/restoreActiveLink는
    // document.querySelector('.app-sidebar')로 범위를 좁혀 active를 찾으므로, 바깥에 붙이면
    // 실제로는 재현되지 않는 실패가 난다.
    document.querySelector('.app-sidebar').insertAdjacentHTML(
      'beforeend',
      '<a href="/gates/locations" class="nav-link" data-popup="true" data-popup-title="위치">위치</a>',
    );
    loadGatePopupModal();
    var dashboard = document.querySelector('a[href="/dashboard"]');
    var groupLink = document.querySelector('a[href="/gates/groups"]');
    var locationLink = document.querySelector('a[href="/gates/locations"]');

    clickPopupLink(groupLink);
    expect(groupLink.classList.contains('active')).toBe(true);

    clickPopupLink(locationLink);
    expect(groupLink.classList.contains('active')).toBe(false);
    expect(locationLink.classList.contains('active')).toBe(true);

    document.getElementById('gate-popup-modal')
      .dispatchEvent(new Event('hidden.bs.modal'));

    expect(locationLink.classList.contains('active')).toBe(false);
    expect(dashboard.classList.contains('active')).toBe(true);
  });

  test('처음부터 강조된 메뉴가 없던 페이지(예: 404)에서 열고 닫아도 예외 없이 계속 강조가 없다', () => {
    document.querySelector('a[href="/dashboard"]').classList.remove('active');
    loadGatePopupModal();
    var popupLink = document.querySelector('a[data-popup="true"]');

    clickPopupLink(popupLink);
    expect(popupLink.classList.contains('active')).toBe(true);

    document.getElementById('gate-popup-modal')
      .dispatchEvent(new Event('hidden.bs.modal'));

    expect(popupLink.classList.contains('active')).toBe(false);
    expect(document.querySelectorAll('.app-sidebar .nav-link.active').length).toBe(0);
  });

  test('[Codex 리뷰 반영] 활성 메뉴가 없는 페이지에서 모달을 닫지 않고 다른 팝업으로 전환해도 닫으면 계속 강조가 없다', () => {
    // 버그: savedActiveLink(원래 활성 링크 저장소)가 null인 이유가 "아직 저장 안 함"인지 "원래부터
    // 활성 링크가 없었음"인지 구분하지 못하면, 첫 팝업 오픈 때 저장된 값이 null이라 두 번째 팝업
    // 오픈(모달을 닫지 않고 전환) 시 "아직 저장 안 함"으로 오판해 이미 강조해 둔 첫 번째 팝업 링크를
    // 원래 활성 링크로 잘못 저장한다 — 이후 닫으면 원래는 강조가 없어야 하는데 첫 번째 팝업 링크가
    // 다시 강조되는 회귀가 생긴다.
    document.querySelector('a[href="/dashboard"]').classList.remove('active');
    document.querySelector('.app-sidebar').insertAdjacentHTML(
      'beforeend',
      '<a href="/gates/locations" class="nav-link" data-popup="true" data-popup-title="위치">위치</a>',
    );
    loadGatePopupModal();
    var groupLink = document.querySelector('a[href="/gates/groups"]');
    var locationLink = document.querySelector('a[href="/gates/locations"]');

    clickPopupLink(groupLink);
    expect(groupLink.classList.contains('active')).toBe(true);

    clickPopupLink(locationLink);
    expect(groupLink.classList.contains('active')).toBe(false);
    expect(locationLink.classList.contains('active')).toBe(true);

    document.getElementById('gate-popup-modal')
      .dispatchEvent(new Event('hidden.bs.modal'));

    expect(locationLink.classList.contains('active')).toBe(false);
    expect(groupLink.classList.contains('active')).toBe(false);
    expect(document.querySelectorAll('.app-sidebar .nav-link.active').length).toBe(0);
  });
});
