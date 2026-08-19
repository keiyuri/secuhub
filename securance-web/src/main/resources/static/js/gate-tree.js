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
    return '<details class="gt-grp" data-key="' + key + '" data-grp-id="' + grp.grpId +
      '" data-grp-name="' + escapeHtml(grp.grpName) + '"' + (open ? ' open' : '') + '>' +
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
    return '<details class="gt-loc" data-key="' + key + '" data-loc-id="' + loc.locId +
      '" data-loc-name="' + escapeHtml(loc.locName) + '"' + (open ? ' open' : '') + '>' +
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
      // [버그 수정: 2026-08-19] 이전에는 '.gt-dtl'만 찾아, 위치(.gt-loc)/그룹(.gt-grp) 노드를
      // 우클릭하면 li가 항상 null이라 메뉴 자체가 뜨지 않고(브라우저 기본 메뉴만 표시) "게이트
      // 관리 팝업" 진입 방법도 없었다. 세 계층 셀렉터를 함께 찾아 가장 가까운 노드를 판별한다
      // (DOM이 loc > grp > dtl로 중첩돼 있어 closest()가 항상 가장 안쪽 노드부터 매칭한다).
      var node = e.target.closest('.gt-dtl, .gt-grp, .gt-loc');
      if (!node) return;
      e.preventDefault();

      var header = menu.querySelector('.gate-tree-context-target');
      var nodeType;
      if (node.classList.contains('gt-dtl')) {
        nodeType = 'dtl';
        target = {
          type: nodeType,
          dtlId: node.getAttribute('data-dtl-id'),
          dtlIp: node.getAttribute('data-dtl-ip'),
          dtlLaneNo: node.getAttribute('data-dtl-lane'),
          gateType: node.getAttribute('data-gate-type'),
        };
        if (header) header.textContent = target.dtlIp + ' / 레인 ' + target.dtlLaneNo;
      } else if (node.classList.contains('gt-grp')) {
        nodeType = 'grp';
        target = { type: nodeType, grpId: node.getAttribute('data-grp-id'), grpName: node.getAttribute('data-grp-name') };
        if (header) header.textContent = target.grpName;
      } else {
        nodeType = 'loc';
        target = { type: nodeType, locId: node.getAttribute('data-loc-id'), locName: node.getAttribute('data-loc-name') };
        if (header) header.textContent = target.locName;
      }

      // 노드 계층에 맞는 항목만 노출한다 — DTL 전용 명령(개방/폐쇄/리셋/모드변경 등)은 실제 게이트
      // IP/레인이 있는 DTL 노드에서만 의미가 있고, "위치 관리"/"게이트그룹 관리"는 그 반대다.
      menu.querySelectorAll('[data-requires-node-type]').forEach(function (menuLi) {
        menuLi.style.display = menuLi.getAttribute('data-requires-node-type') === nodeType ? '' : 'none';
      });

      // "역방향 개방" 항목은 Flap 게이트(gate_type_code=2)에서만 노출한다 — 다른 타입 게이트는
      // 명령 자체를 지원하지 않아, 항목을 감추는 것이 클릭 후 실패 응답을 받는 것보다 낫다.
      // (DTL 노드가 아니면 위 data-requires-node-type 필터로 이미 숨겨져 있으므로 영향 없다.)
      menu.querySelectorAll('[data-requires-gate-type]').forEach(function (menuLi) {
        if (nodeType !== 'dtl') return;
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

      // [2026-08-19 사용자 요청] 위치/그룹 노드 우클릭 메뉴는 URL/제목을 직접 들고 있지 않고,
      // 사이드바 "모니터링 > 게이트 관리" 그룹의 실제 메뉴 항목(위치/게이트그룹, MenuNode.Item
      // popup=true — MenuProvider.kt 참고)을 그대로 클릭해서 실행한다. 이렇게 하면 URL/제목/팝업
      // 여부가 MenuProvider 한 곳에만 존재해, 사이드바 메뉴 구성이 바뀌어도 트리 메뉴가 별도
      // 수정 없이 항상 같은 화면을 연다(이전에는 '/gates/locations' 등을 이 파일에도 하드코딩해
      // 두 곳이 어긋날 여지가 있었다). document.querySelector('a[data-popup="true"][href=...]')로
      // 사이드바 링크를 찾아 .click()하면 gate-popup-modal.js의 document 클릭 위임 리스너가
      // 그대로 반응해 동일한 모달 오픈 로직을 탄다.
      if (action === 'manage-location' || action === 'manage-group') {
        var menuHref = action === 'manage-location' ? '/gates/locations' : '/gates/groups';
        var sidebarLink = document.querySelector('.app-sidebar a[data-popup="true"][href="' + menuHref + '"]');
        if (sidebarLink) {
          sidebarLink.click();
        } else {
          // 사이드바 마크업이 예상과 달라 메뉴 항목을 찾지 못한 예외적인 경우의 최소 폴백 —
          // gate-popup-modal.js 초기화 실패 시(무동작 스텁, 같은 파일 8행)에는 href="#"라 대체
          // 경로가 없는 이 메뉴 항목이 조용히 실패하지 않도록 전체 페이지 이동으로 대체한다.
          var manageTitle = action === 'manage-location' ? '위치 관리' : '게이트그룹 관리';
          if (window.GatePopupModal && window.GatePopupModal.ready) {
            window.GatePopupModal.open(menuHref, manageTitle);
          } else {
            window.location.href = menuHref;
          }
        }
        return;
      }

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

      // [Codex 적대적 리뷰 수정: high] 상시 개방/폐쇄/FREE 모드/역방향 개방은 재인증이 비활성화된
      // 구성(securance.security.gate-control-reauth-required=false)이거나 최근 인증으로 재인증이
      // 생략되는 상황에서는 아무 확인 절차 없이 메뉴 클릭 한 번으로 즉시 전송됐다 — 오클릭 한 번이
      // 출입 통제 상태를 바로 바꿔 보안·운영 사고로 이어질 수 있다("정상 복구"는 안전한 기본
      // 동작으로 되돌리는 명령이라 확인 대상에서 제외한다). 명령을 서버로 보내기 전에 대상
      // IP/레인과 명령별 위험 문구를 담은 확인 대화상자를 띄우고, 취소 시 요청 자체를 만들지 않는다.
      var CONFIRM_MESSAGES = {
        'gate-open': '게이트를 상시 개방 상태로 전환합니다. 이후 별도로 복구하기 전까지 누구나 통과할 수 있습니다.',
        'gate-close': '게이트를 상시 폐쇄 상태로 전환합니다. 이후 별도로 복구하기 전까지 아무도 통과할 수 없습니다.',
        'gate-free': '게이트를 FREE 모드로 전환합니다. 이후 별도로 복구하기 전까지 통제 없이 자유롭게 통과할 수 있습니다.',
        'gate-reverse-open': '게이트를 역방향으로 개방합니다. 정방향 통행 흐름에 영향을 줄 수 있습니다.',
      };

      if (RESET_COMMANDS[action]) {
        reportCommandResult(
          sendResetWithReauth(selected.dtlIp, selected.dtlLaneNo, RESET_COMMANDS[action]),
          selected.dtlIp, selected.dtlLaneNo,
        );
        return;
      }
      if (OPERATION_COMMANDS[action]) {
        if (CONFIRM_MESSAGES[action]) {
          var confirmed = window.confirm(
            selected.dtlIp + ' / 레인 ' + selected.dtlLaneNo + '\n\n' + CONFIRM_MESSAGES[action] + '\n\n계속하시겠습니까?',
          );
          if (!confirmed) return;
        }
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
