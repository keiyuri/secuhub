// Phase 10 — #18 대시보드 게이트 트리뷰(계획서 3절/2026-08-12 사용자 확인: 레거시 없이 신규 설계).
// LOC/GRP/DTL 3계층 트리(정렬: 위치ID→그룹ID→게이트ID, 2026-08-19) + 연결상태 아이콘 + 우클릭 제어
// 메뉴. GateTreeApiController(/api/gate-tree)를 폴링해 그리고, 제어는 기존 엔드포인트
// (/api/gate-control/command, /api/gate-control/reset, /gates/details/{id}/mode|motor)를 그대로
// 재사용한다. 우클릭 메뉴 상단 5개 항목(개방/폐쇄/정상 복구/FREE 모드/역방향 개방)은
// SR_Speed_Client 트리뷰(SR_F_DashBoard.GateControl.cs ContextMenu_Init)와 동일하게 맞췄다
// (2026-08-19 사용자 요청).
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
    // data-gate-type: 우클릭 메뉴가 "역방향 개방" 항목의 표시 여부를 판단하는 데 쓴다(Flap=2에서만
    // 표시 — SR_Speed_Client ContextMenu_Init의 iGateType==2 분기와 동일).
    return '<li class="gt-dtl" data-dtl-id="' + d.dtlId + '" data-dtl-ip="' + escapeHtml(d.dtlIp) +
      '" data-dtl-lane="' + d.dtlLaneNo + '" data-online="' + d.online +
      '" data-gate-type="' + grp.gateTypeCode + '">' +
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

  // 리셋(/api/gate-control/reset)과 일반 운영 명령(/api/gate-control/command, 개방/폐쇄/복구/FREE/
  // 역방향 개방)은 둘 다 GateControlReauthInterceptor가 같은 방식으로 재인증을 요구하므로
  // (dtlIp/dtlLaneNo/command + 선택적 reauthPassword) 요청 조립·응답 파싱 로직을 공유한다.
  function postGateControl(path, dtlIp, dtlLaneNo, command, reauthPassword) {
    var payload = { dtlIp: dtlIp, dtlLaneNo: dtlLaneNo, command: command };
    if (reauthPassword) payload.reauthPassword = reauthPassword;
    var params = new URLSearchParams(payload);
    return fetch(path, { method: 'POST', headers: csrfHeaders(), body: params.toString() })
      .then(function (res) { return res.json().then(function (body) { return { ok: res.ok, status: res.status, body: body }; }); });
  }

  // securance.security.gate-control-reauth-required=true일 때 GateControlReauthInterceptor가
  // {reauthRequired:true}를 반환한다 — window.prompt()로 비밀번호를 받아 한 번만 자동 재시도한다.
  // 취소하면 재시도하지 않고 최초(재인증 요구) 응답을 그대로 반환한다.
  function postGateControlWithReauth(path, dtlIp, dtlLaneNo, command) {
    return postGateControl(path, dtlIp, dtlLaneNo, command).then(function (r) {
      if (!r.body || !r.body.reauthRequired) return r;
      var password = window.prompt('게이트 제어 재인증 — 비밀번호를 입력하세요.');
      if (!password) return r;
      return postGateControl(path, dtlIp, dtlLaneNo, command, password);
    });
  }

  function sendResetWithReauth(dtlIp, dtlLaneNo, command) {
    return postGateControlWithReauth('/api/gate-control/reset', dtlIp, dtlLaneNo, command);
  }

  // 게이트 상시 개방/폐쇄/정상 복구/FREE 모드/역방향 개방 — SR_Speed_Client 트리뷰 컨텍스트 메뉴
  // (ContextMenu_Init)와 동일한 명령 세트. SpeedGateControlCommand enum 이름(OPEN/CLOSE/NORMAL/
  // FREE_FREE/REVERSE_OPEN)을 그대로 command 파라미터로 보낸다.
  function sendCommandWithReauth(dtlIp, dtlLaneNo, command) {
    return postGateControlWithReauth('/api/gate-control/command', dtlIp, dtlLaneNo, command);
  }

  // [Codex 적대적 리뷰 수정: high] 이전에는 postGateControl(WithReauth)의 응답을 catch에서만
  // 조용히 삼켰다 — 429(대기열 포화)/503(미접속)/401(재인증 실패)처럼 fetch 자체는 정상 resolve
  // 되는 실패 응답도 전혀 표시되지 않아, 운영자가 개방/폐쇄 등 출입 통제 명령이 실제로 게이트에
  // 전달됐는지 알 수 없었다("대시보드 알림 팝업이 별도로 상태를 반영한다"는 주석은 사실
  // dashboard-realtime.js의 화재/장애 알림 모달(rt-alert-*) 전용 로직이라 이 우클릭 메뉴 요청과는
  // 연결되어 있지 않았다). HTTP 상태와 무관하게 항상 결과를 화면에 노출한다.
  function describeResult(r) {
    if (!r) return { ok: false, text: '응답을 확인할 수 없습니다.' };
    var message = (r.body && r.body.message) ? r.body.message : ('HTTP ' + r.status);
    return { ok: !!r.ok, text: message };
  }

  var resultToastTimer = null;

  function showResultToast(dtlIp, dtlLaneNo, ok, text) {
    var el = document.getElementById('gate-tree-toast');
    if (!el) {
      el = document.createElement('div');
      el.id = 'gate-tree-toast';
      document.body.appendChild(el);
    }
    el.className = 'gate-tree-toast ' + (ok ? 'gate-tree-toast-ok' : 'gate-tree-toast-error');
    el.textContent = dtlIp + ' / 레인 ' + dtlLaneNo + ' — ' + text;
    el.style.display = 'block';
    if (resultToastTimer) clearTimeout(resultToastTimer);
    resultToastTimer = setTimeout(function () { el.style.display = 'none'; }, 4000);
  }

  // 명령 전송 Promise에 결과 표시를 일괄로 연결한다 — 성공(2xx)/실패(4xx·5xx) 모두 r.ok/r.body를
  // 검사해 표시하고, 네트워크·JSON 파싱 실패(catch)도 동일하게 사용자에게 노출한다.
  function reportCommandResult(promise, dtlIp, dtlLaneNo) {
    return promise
      .then(function (r) {
        var described = describeResult(r);
        showResultToast(dtlIp, dtlLaneNo, described.ok, described.text);
      })
      .catch(function (err) {
        showResultToast(dtlIp, dtlLaneNo, false, '요청 실패: ' + err);
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
        gateType: li.getAttribute('data-gate-type'),
      };
      var header = menu.querySelector('.gate-tree-context-target');
      if (header) header.textContent = target.dtlIp + ' / 레인 ' + target.dtlLaneNo;

      // "역방향 개방" 항목은 Flap 게이트(gate_type_code=2)에서만 노출한다 — 다른 타입 게이트는
      // 명령 자체를 지원하지 않아, 항목을 감추는 것이 클릭 후 실패 응답을 받는 것보다 낫다.
      menu.querySelectorAll('[data-requires-gate-type]').forEach(function (menuLi) {
        menuLi.style.display = menuLi.getAttribute('data-requires-gate-type') === target.gateType ? '' : 'none';
      });

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
      // [Codex 리뷰 수정: P1] hideMenu()가 target을 null로 초기화하므로, 그 뒤에 target.*를 읽는
      // 모든 분기(모드 변경/모터 설정/리셋/운영 명령)가 TypeError로 깨졌다 — 메뉴를 숨기기 전에
      // 선택된 노드 정보를 별도 변수(selected)로 옮겨두고 이후로는 이 변수만 사용한다.
      var selected = target;
      hideMenu();

      if (action === 'mode-change') {
        window.location.href = '/gates/details/' + selected.dtlId + '/mode';
        return;
      }
      if (action === 'motor-setup') {
        window.location.href = '/gates/details/' + selected.dtlId + '/motor';
        return;
      }

      // SpeedGateControlCommand enum 이름과 1:1 대응 — GateControlApiController가 문자열을
      // valueOf()로 그대로 변환하므로 오타는 400(Bad Request)으로 즉시 드러난다.
      var RESET_COMMANDS = { 'reset-system': 'RESET_SYSTEM', 'reset-motor': 'RESET_MOTOR' };
      var OPERATION_COMMANDS = {
        'gate-open': 'OPEN',
        'gate-close': 'CLOSE',
        'gate-normal': 'NORMAL',
        'gate-free': 'FREE_FREE',
        'gate-reverse-open': 'REVERSE_OPEN',
      };

      if (RESET_COMMANDS[action]) {
        reportCommandResult(
          sendResetWithReauth(selected.dtlIp, selected.dtlLaneNo, RESET_COMMANDS[action]),
          selected.dtlIp, selected.dtlLaneNo,
        );
        return;
      }
      if (OPERATION_COMMANDS[action]) {
        reportCommandResult(
          sendCommandWithReauth(selected.dtlIp, selected.dtlLaneNo, OPERATION_COMMANDS[action]),
          selected.dtlIp, selected.dtlLaneNo,
        );
      }
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
      // 우클릭 메뉴로 보낸 명령의 성공/실패를 표시하는 토스트(2026-08-19 Codex 적대적 리뷰 대응).
      '#gate-tree-toast{display:none;position:fixed;right:1rem;bottom:1rem;max-width:360px;' +
      'padding:.6rem 1rem;border-radius:.375rem;color:#fff;font-size:.9rem;z-index:1090;' +
      'box-shadow:0 .25rem .75rem rgba(0,0,0,.3)}' +
      '#gate-tree-toast.gate-tree-toast-ok{background:#198754}' +
      '#gate-tree-toast.gate-tree-toast-error{background:#dc3545}';
    document.head.appendChild(style);
  }

  var container = document.getElementById('gate-tree');
  if (!container) return;
  injectStyles();
  setupContextMenu(container);
  loadTree(container);
  setInterval(function () { loadTree(container); }, POLL_INTERVAL_MS);
})();
