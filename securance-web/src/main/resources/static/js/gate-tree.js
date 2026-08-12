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

  function sendReset(dtlIp, dtlLaneNo, command) {
    var params = new URLSearchParams({ dtlIp: dtlIp, dtlLaneNo: dtlLaneNo, command: command });
    return fetch('/api/gate-control/reset', { method: 'POST', headers: csrfHeaders(), body: params.toString() })
      .then(function (res) { return res.json().then(function (body) { return { ok: res.ok, status: res.status, body: body }; }); });
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
      sendReset(target.dtlIp, target.dtlLaneNo, command).catch(function () { /* 실패는 조용히 무시 — 대시보드 알림 팝업이 별도로 상태를 반영한다 */ });
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
      '.gate-tree-context-menu{min-width:200px;z-index:1080}';
    document.head.appendChild(style);
  }

  var container = document.getElementById('gate-tree');
  if (!container) return;
  injectStyles();
  setupContextMenu(container);
  loadTree(container);
  setInterval(function () { loadTree(container); }, POLL_INTERVAL_MS);
})();
