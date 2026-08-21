// Phase 10 — #18 대시보드 게이트 트리뷰(계획서 3절/2026-08-12 사용자 확인: 레거시 없이 신규 설계).
// LOC/GRP/DTL 3계층 트리 + 연결상태 아이콘 + 우클릭 제어 메뉴.
// GateTreeApiController(/api/gate-tree)를 폴링해 그리고, 제어는 기존 엔드포인트
// (/api/gate-control/reset, /gates/details/{id}/mode|motor)를 그대로 재사용한다.
(function () {
  'use strict';

  var POLL_INTERVAL_MS = 5000; // dashboard-realtime.js의 SUMMARY 주기와 동일하게 맞춘다.
  var GATE_TYPE_NAMES = { 1: 'Speed', 2: 'Flap', 3: 'Turn', 4: 'Fast' };

  // <details> 펼침/접힘 상태는 폴링마다 트리 전체를 다시 그리므로 별도로 기억해둔다
  // (기본값: 위치는 펼침, 그룹은 접힘 — 그룹이 많으면 화면이 너무 길어지는 것을 방지).
  var expandState = Object.create(null);

  function isExpanded(key, defaultValue) {
    return Object.prototype.hasOwnProperty.call(expandState, key) ? expandState[key] : defaultValue;
  }

  function escapeHtml(value) {
    if (value === null || value === undefined) return '';
    return String(value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function statusIcon(online) {
    return '<i class="bi bi-circle-fill gt-status ' + (online ? 'text-success' : 'text-danger') + '"' +
      ' title="' + (online ? '온라인' : '오프라인') + '"></i>';
  }

  function renderDetail(loc, grp, d) {
    var label = d.dtlName ? escapeHtml(d.dtlName) : escapeHtml(d.dtlIp);
    return '<li class="gt-dtl" data-dtl-id="' + d.dtlId + '" data-dtl-ip="' + escapeHtml(d.dtlIp) +
      '" data-dtl-lane="' + d.dtlLaneNo + '" data-online="' + d.online + '">' +
      statusIcon(d.online) +
      ' <span class="gt-dtl-label">' + label + '</span>' +
      ' <span class="text-muted small">(' + escapeHtml(d.dtlIp) + ' / 레인 ' + d.dtlLaneNo + ')</span>' +
      '</li>';
  }

  function renderGroup(loc, grp) {
    var key = 'grp-' + grp.grpId;
    var open = isExpanded(key, false);
    var typeName = GATE_TYPE_NAMES[grp.gateTypeCode] || ('타입 ' + grp.gateTypeCode);
    var onlineCount = grp.details.filter(function (d) { return d.online; }).length;
    var detailsHtml = grp.details.length
      ? '<ul class="gt-dtl-list">' + grp.details.map(function (d) { return renderDetail(loc, grp, d); }).join('') + '</ul>'
      : '<div class="text-muted small ms-4">등록된 레인이 없습니다.</div>';
    return '<details class="gt-grp" data-key="' + key + '"' + (open ? ' open' : '') + '>' +
      '<summary><i class="bi bi-diagram-3"></i> ' + escapeHtml(grp.grpName) +
      ' <span class="badge text-bg-secondary">' + typeName + '</span>' +
      ' <span class="text-muted small">(' + onlineCount + '/' + grp.details.length + ' 온라인)</span>' +
      '</summary>' + detailsHtml + '</details>';
  }

  function renderLocation(loc) {
    var key = 'loc-' + loc.locId;
    var open = isExpanded(key, true);
    var groupsHtml = loc.groups.length
      ? loc.groups.map(function (g) { return renderGroup(loc, g); }).join('')
      : '<div class="text-muted small ms-4">등록된 그룹이 없습니다.</div>';
    return '<details class="gt-loc" data-key="' + key + '"' + (open ? ' open' : '') + '>' +
      '<summary><i class="bi bi-geo-alt"></i> ' + escapeHtml(loc.locName) + '</summary>' +
      groupsHtml + '</details>';
  }

  function render(container, tree) {
    if (!tree || tree.length === 0) {
      container.innerHTML = '<div class="text-muted small">등록된 게이트 위치가 없습니다.</div>';
      return;
    }
    container.innerHTML = tree.map(renderLocation).join('');
    // 사용자가 펼치거나 접으면 다음 폴링 렌더링에도 상태가 유지되도록 기록한다.
    container.querySelectorAll('details[data-key]').forEach(function (el) {
      el.addEventListener('toggle', function () {
        expandState[el.getAttribute('data-key')] = el.open;
      });
    });
  }

  function loadTree(container) {
    fetch('/api/gate-tree', { headers: { Accept: 'application/json' } })
      .then(function (res) {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
      })
      .then(function (tree) { render(container, tree); })
      .catch(function () {
        if (container.getAttribute('data-loading') === 'true') {
          container.innerHTML = '<div class="text-danger small">트리를 불러오지 못했습니다.</div>';
        }
      })
      .finally(function () { container.removeAttribute('data-loading'); });
  }

  // ---- 우클릭 제어 메뉴 ----

  function csrfHeaders() {
    var csrfToken = document.querySelector('meta[name="_csrf"]');
    var csrfHeader = document.querySelector('meta[name="_csrf_header"]');
    var headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
    if (csrfToken && csrfHeader) headers[csrfHeader.content] = csrfToken.content;
    return headers;
  }

  function sendReset(dtlIp, dtlLaneNo, command, reauthPassword) {
    var payload = { dtlIp: dtlIp, dtlLaneNo: dtlLaneNo, command: command };
    if (reauthPassword) payload.reauthPassword = reauthPassword;
    var params = new URLSearchParams(payload);
    return fetch('/api/gate-control/reset', { method: 'POST', headers: csrfHeaders(), body: params.toString() })
      .then(function (res) { return res.json().then(function (body) { return { ok: res.ok, status: res.status, body: body }; }); });
  }

  // securance.security.gate-control-reauth-required=true일 때 GateControlReauthInterceptor가
  // {reauthRequired:true}를 반환한다 — window.prompt()로 비밀번호를 받아 한 번만 자동 재시도한다.
  // 취소하면 재시도하지 않고 최초(재인증 요구) 응답을 그대로 반환한다.
  function sendResetWithReauth(dtlIp, dtlLaneNo, command) {
    return sendReset(dtlIp, dtlLaneNo, command).then(function (r) {
      if (!r.body || !r.body.reauthRequired) return r;
      var password = window.prompt('게이트 제어 재인증 — 비밀번호를 입력하세요.');
      if (!password) return r;
      return sendReset(dtlIp, dtlLaneNo, command, password);
    });
  }

  function setupContextMenu(container) {
    var menu = document.getElementById('gate-tree-context-menu');
    if (!menu) return;
    var target = null; // 우클릭한 DTL 노드의 data-* 값

    function hideMenu() { menu.style.display = 'none'; target = null; }

    container.addEventListener('contextmenu', function (e) {
      var li = e.target.closest('.gt-dtl');
      if (!li) return;
      e.preventDefault();
      target = {
        dtlId: li.getAttribute('data-dtl-id'),
        dtlIp: li.getAttribute('data-dtl-ip'),
        dtlLaneNo: li.getAttribute('data-dtl-lane'),
      };
      var header = menu.querySelector('.gate-tree-context-target');
      if (header) header.textContent = target.dtlIp + ' / 레인 ' + target.dtlLaneNo;

      var menuWidth = menu.offsetWidth || 200;
      var menuHeight = menu.offsetHeight || 200;
      var x = Math.min(e.clientX, window.innerWidth - menuWidth - 8);
      var y = Math.min(e.clientY, window.innerHeight - menuHeight - 8);
      menu.style.left = Math.max(0, x) + 'px';
      menu.style.top = Math.max(0, y) + 'px';
      menu.style.display = 'block';
    });

    menu.addEventListener('click', function (e) {
      var item = e.target.closest('[data-action]');
      if (!item || !target) return;
      e.preventDefault();
      var action = item.getAttribute('data-action');
      hideMenu();

      if (action === 'mode-change') {
        window.location.href = '/gates/details/' + target.dtlId + '/mode';
        return;
      }
      if (action === 'motor-setup') {
        window.location.href = '/gates/details/' + target.dtlId + '/motor';
        return;
      }
      var command = action === 'reset-system' ? 'RESET_SYSTEM' : action === 'reset-motor' ? 'RESET_MOTOR' : null;
      if (!command) return;
      sendResetWithReauth(target.dtlIp, target.dtlLaneNo, command).catch(function () { /* 실패는 조용히 무시 — 대시보드 알림 팝업이 별도로 상태를 반영한다 */ });
    });

    document.addEventListener('click', function (e) {
      if (menu.style.display !== 'none' && !menu.contains(e.target)) hideMenu();
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') hideMenu();
    });
    window.addEventListener('scroll', hideMenu, true);
  }

  function injectStyles() {
    var style = document.createElement('style');
    style.textContent =
      '.gate-tree{max-height:420px;overflow:auto}' +
      '.gate-tree .gt-loc>summary{cursor:pointer;font-weight:600;padding:.25rem 0}' +
      '.gate-tree .gt-grp{margin-left:1.25rem}' +
      '.gate-tree .gt-grp>summary{cursor:pointer;padding:.15rem 0}' +
      '.gate-tree .gt-dtl-list{list-style:none;margin:0;padding-left:1.5rem}' +
      '.gate-tree .gt-dtl{padding:.1rem 0;cursor:context-menu}' +
      '.gate-tree .gt-status{font-size:.6rem;margin-right:.25rem}' +
      '.gate-tree-context-menu{min-width:200px;z-index:1080}' +
      // 2026-08-21: 대시보드 1행에서 게이트 트리뷰 카드(.col-lg-8)가 오른쪽 SmallBox 2x2 컬럼
      // (.col-lg-4, 온라인/오프라인/미해결 오류/오늘 통행량)과 같은 행에 있다. 컬럼→카드→카드바디→
      // 트리를 flex 세로 축으로 연결해두고, 실제 높이 값은 syncTreeColumnHeight()가 JS로 오른쪽
      // 컬럼 실측 높이를 왼쪽 컬럼에 그대로 넣어준다(아래 참고) — 여기서는 그 높이를 card/card-body/
      // #gate-tree까지 세로로 전달할 flex 배선만 담당한다.
      '.gate-tree-col{display:flex;flex-direction:column}' +
      '.gate-tree-col>.card{flex:1 1 auto;display:flex;flex-direction:column;min-height:0}' +
      '.gate-tree-col>.card>.card-body{flex:1 1 auto;display:flex;flex-direction:column;min-height:0}' +
      '.gate-tree-col .gate-tree{flex:1 1 auto;min-height:0;max-height:none;overflow:auto}';
    document.head.appendChild(style);
  }

  // 2026-08-21(2차 수정): align-items-stretch를 쓰면 트리 내용이 길 때(위치/그룹이 많을 때)
  // 트리의 "내용 기준 자연 높이"가 행 전체 높이를 끌어올려, 트리 카드가 오른쪽 SmallBox 2x2
  // 합계보다 훨씬 길어지고 SmallBox 쪽엔 빈 여백만 늘어나는 문제가 있었다(사용자 지적). 대신
  // dashboard.html의 행은 align-items-start로 각 컬럼이 자기 내용 기준 자연 높이를 갖게 하고,
  // 여기서 오른쪽 컬럼(SmallBox 합계, .col-lg-4)의 실측 높이를 읽어 왼쪽 컬럼(.gate-tree-col)에
  // 그대로 지정한다 — 트리 내용이 넘치면 #gate-tree 내부 스크롤로 처리되므로 트리 카드 하단은
  // 항상 오늘 통행량 박스 하단과 같아진다.
  // 2026-08-21(3차 수정, Codex 리뷰 P2 지적): Bootstrap lg 기준(992px) 미만에서는 .col-lg-8/
  // .col-lg-4가 세로로 쌓여 두 컬럼 하단을 맞출 이유가 없다 — 그 상태에서도 오른쪽 컬럼 높이를
  // 강제로 넣으면 트리 카드가 그 높이로 잘려 불필요한 내부 스크롤이 생긴다. lg 미만에서는 인라인
  // 높이를 아예 제거해 컬럼이 각자 자연 높이를 갖도록 한다.
  var LG_BREAKPOINT_PX = 992; // Bootstrap 5 $grid-breakpoints.lg
  function syncTreeColumnHeight() {
    var col8 = document.querySelector('.gate-tree-col');
    if (!col8) return;
    if (window.innerWidth < LG_BREAKPOINT_PX) {
      col8.style.height = '';
      return;
    }
    // 버그 수정(Opus 전체 리뷰, 2026-08-21): 트리 카드는 collapsible/removable이라(card.html
    // 프래그먼트) 사용자가 접기(-)를 누르면 card-body가 사라지고, 삭제(×)를 누르면 카드 자체가
    // DOM에서 제거된다. 두 경우 모두 이전에 강제로 넣어둔 인라인 height가 그대로 남아, 접힌
    // 헤더 아래나 빈 컬럼에 목표 높이만큼 빈 여백이 생긴다(자가치유되지 않음 — 아래에서 다시
    // 인라인 height를 넣는 것뿐이라). 카드가 없거나(삭제됨) 접혀 있으면 강제 높이를 지운다.
    var card = col8.querySelector('.card');
    if (!card || card.classList.contains('collapsed-card')) {
      col8.style.height = '';
      return;
    }
    var row = col8.closest('.row');
    var col4 = row && row.querySelector('.col-lg-4');
    if (!col4) return;
    col8.style.height = ''; // 재측정 전에 이전 강제 높이를 지워 col4 실측치가 영향받지 않게 한다.
    var targetHeight = col4.getBoundingClientRect().height;
    if (targetHeight > 0) col8.style.height = targetHeight + 'px';
  }

  function debounce(fn, wait) {
    var timer = null;
    return function () {
      clearTimeout(timer);
      timer = setTimeout(fn, wait);
    };
  }

  var container = document.getElementById('gate-tree');
  if (!container) return;
  injectStyles();
  setupContextMenu(container);
  loadTree(container);
  setInterval(function () { loadTree(container); }, POLL_INTERVAL_MS);

  syncTreeColumnHeight();
  window.addEventListener('resize', debounce(syncTreeColumnHeight, 150));
  // 대시보드 실시간 갱신(SmallBox 카운트)이 자릿수 변화 등으로 오른쪽 컬럼 높이를 미세하게
  // 바꿀 수 있어 폴링 주기에 맞춰 재동기화한다(dashboard-realtime.js의 SUMMARY 주기와 별개로
  // 이 값 하나만 가볍게 재계산).
  setInterval(syncTreeColumnHeight, POLL_INTERVAL_MS);
})();
