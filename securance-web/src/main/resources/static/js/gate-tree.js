// Phase 10 — #18 대시보드 게이트 트리뷰(계획서 3절/2026-08-12 사용자 확인: 레거시 없이 신규 설계).
// LOC/GRP/DTL 3계층 트리(정렬: 위치ID→그룹ID→게이트ID, 2026-08-19) + 연결상태 아이콘 + 우클릭 제어
// 메뉴. GateTreeApiController(/api/gate-tree, 사용여부·분석여부 Y인 노드만 반환 —
// GateTreeService.buildTree 참고)를 폴링해 그리고, 제어는 기존 엔드포인트(/api/gate-control/command,
// /api/gate-control/reset, /gates/details/{id}/mode|motor)를 그대로 재사용한다.
// 우클릭 메뉴는 DTL(레인)뿐 아니라 GRP(그룹)/LOC(위치) 노드에서도 표시한다 — SR_Speed_Client
// 트리뷰(tvNet_NodeMouseClick, "LOC/GRP/DTL 모든 노드에서 게이트 제어 메뉴 표시")와 동일하게
// 맞췄다(2026-08-19 사용자 요청). 상단 5개 항목(개방/폐쇄/정상 복구/FREE 모드/역방향 개방)은
// SR_Speed_Client 트리뷰(SR_F_DashBoard.GateControl.cs ContextMenu_Init)와 동일하게 맞췄다
// (2026-08-19 사용자 요청).
(function () {
  'use strict';

  var POLL_INTERVAL_MS = 5000; // dashboard-realtime.js의 SUMMARY 주기와 동일하게 맞춘다.
  var GATE_TYPE_NAMES = { 1: 'Speed', 2: 'Flap', 3: 'Turn', 4: 'Fast' };

  // <details> 펼침/접힘 상태는 폴링마다 트리 전체를 다시 그리므로 별도로 기억해둔다
  // (기본값: 위치는 펼침, 그룹은 접힘 — 그룹이 많으면 화면이 너무 길어지는 것을 방지).
  var expandState = Object.create(null);

  // 마지막으로 성공적으로 불러온 트리 — GRP/LOC 우클릭 메뉴가 "그 아래 모든 레인" 목록을
  // 계산할 때 DOM을 다시 파싱하지 않고 이 데이터에서 바로 찾는다.
  var lastTree = [];

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
    // data-loc-id/data-grp-id/data-*-name: 우클릭 메뉴가 그룹 범위 명령 대상(devices)과 표시
    // 문구를 계산하는 데 쓴다(collectDevicesForGrp 참고).
    return '<details class="gt-grp" data-key="' + key + '" data-loc-id="' + loc.locId +
      '" data-grp-id="' + grp.grpId + '" data-loc-name="' + escapeHtml(loc.locName) +
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
    // data-loc-id/data-loc-name: 우클릭 메뉴가 위치 범위 명령 대상(devices)과 표시 문구를
    // 계산하는 데 쓴다(collectDevicesForLoc 참고).
    return '<details class="gt-loc" data-key="' + key + '" data-loc-id="' + loc.locId +
      '" data-loc-name="' + escapeHtml(loc.locName) + '"' + (open ? ' open' : '') + '>' +
      '<summary><i class="bi bi-geo-alt"></i> ' + escapeHtml(loc.locName) + '</summary>' +
      groupsHtml + '</details>';
  }

  function render(container, tree) {
    lastTree = tree || [];
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

  // ---- GRP/LOC 범위 명령 대상 계산 ----

  /** locId 아래(모든 그룹의) 레인 전체를 {dtlIp, dtlLaneNo} 목록으로 수집한다. */
  function collectDevicesForLoc(locId) {
    var devices = [];
    lastTree.forEach(function (loc) {
      if (String(loc.locId) !== String(locId)) return;
      loc.groups.forEach(function (g) {
        g.details.forEach(function (d) { devices.push({ dtlIp: d.dtlIp, dtlLaneNo: d.dtlLaneNo }); });
      });
    });
    return devices;
  }

  /** grpId 아래 레인 전체를 {dtlIp, dtlLaneNo} 목록으로 수집한다. */
  function collectDevicesForGrp(grpId) {
    var devices = [];
    lastTree.forEach(function (loc) {
      loc.groups.forEach(function (g) {
        if (String(g.grpId) !== String(grpId)) return;
        g.details.forEach(function (d) { devices.push({ dtlIp: d.dtlIp, dtlLaneNo: d.dtlLaneNo }); });
      });
    });
    return devices;
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

  // GRP/LOC 범위(devices 여러 건)와 DTL 범위(devices 1건)를 동일한 경로로 처리한다 — DTL 단일
  // 대상일 때는 사실상 반복문이 한 번만 도는 것과 같다.
  //
  // 여러 대를 대상으로 할 때, securance.security.gate-control-reauth-required=true라면 기기마다
  // 매번 비밀번호를 물으면 안 된다(그룹/위치에 레인이 여러 개면 팝업이 계속 뜬다) — 첫 기기에서만
  // window.prompt()로 물어보고, 이후 나머지 기기에는 같은 비밀번호를 재사용한다.
  //
  // [재검토 수정] 사용자가 첫 프롬프트를 취소하면 "배치 전체를 포기하겠다"는 의사로 보고,
  // reauthCancelled 플래그를 세워 남은 기기에는 다시 prompt()를 띄우지 않는다(이전에는 취소해도
  // sharedPassword가 여전히 null이라 다음 기기마다 또 프롬프트가 떠서, 레인이 많은 그룹/위치를
  // 대상으로 하면 취소 클릭을 계속 반복해야 했다). 취소 이후 기기들은 재인증 요구 응답을 그대로
  // 실패로 기록한다.
  //
  // 전송은 병렬이 아니라 순차(reduce 체인)로 진행한다 — 재인증 프롬프트를 한 번만 띄우려면 첫
  // 응답을 봐야 하고, 여러 게이트에 TCP 커넥션을 동시에 여는 부담(레거시 GateCtrlDataSet가
  // TCP 대상만 예외적으로 병렬 처리하는 것과 달리)도 피한다.
  function postCommandToDevices(path, devices, command) {
    var sharedPassword = null;
    var reauthCancelled = false;
    var results = [];
    return devices.reduce(function (chain, dev) {
      return chain.then(function () {
        return postGateControl(path, dev.dtlIp, dev.dtlLaneNo, command, sharedPassword).then(function (r) {
          if (r.body && r.body.reauthRequired && !sharedPassword && !reauthCancelled) {
            var password = window.prompt('게이트 제어 재인증 — 비밀번호를 입력하세요.');
            if (!password) {
              reauthCancelled = true;
              results.push({ dev: dev, r: r });
              return;
            }
            sharedPassword = password;
            return postGateControl(path, dev.dtlIp, dev.dtlLaneNo, command, password).then(function (r2) {
              results.push({ dev: dev, r: r2 });
            });
          }
          results.push({ dev: dev, r: r });
        });
      });
    }, Promise.resolve()).then(function () { return results; });
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

  function showResultToast(label, ok, text) {
    var el = document.getElementById('gate-tree-toast');
    if (!el) {
      el = document.createElement('div');
      el.id = 'gate-tree-toast';
      document.body.appendChild(el);
    }
    el.className = 'gate-tree-toast ' + (ok ? 'gate-tree-toast-ok' : 'gate-tree-toast-error');
    el.textContent = label + ' — ' + text;
    el.style.display = 'block';
    if (resultToastTimer) clearTimeout(resultToastTimer);
    resultToastTimer = setTimeout(function () { el.style.display = 'none'; }, 4000);
  }

  // postCommandToDevices()의 결과 배열을 하나의 토스트로 요약해 보여준다. 대상이 1건(DTL 범위)이면
  // 기존과 동일하게 "라벨 — 메시지" 형태로, 여러 건(GRP/LOC 범위)이면 성공/실패 건수와 실패 목록
  // 일부를 함께 보여준다.
  function reportBatchResult(promiseOfResults, label) {
    return promiseOfResults
      .then(function (results) {
        if (results.length === 0) {
          showResultToast(label, false, '대상 게이트가 없습니다.');
          return;
        }
        if (results.length === 1) {
          var described = describeResult(results[0].r);
          showResultToast(label, described.ok, described.text);
          return;
        }
        var successCount = 0;
        var failMessages = [];
        results.forEach(function (item) {
          var d = describeResult(item.r);
          if (d.ok) {
            successCount++;
          } else {
            failMessages.push(item.dev.dtlIp + '/레인' + item.dev.dtlLaneNo + ': ' + d.text);
          }
        });
        var ok = failMessages.length === 0;
        var text = successCount + '/' + results.length + '건 성공';
        if (failMessages.length) {
          var shown = failMessages.slice(0, 3).join(', ');
          var more = failMessages.length > 3 ? ' 외 ' + (failMessages.length - 3) + '건' : '';
          text += ' — 실패: ' + shown + more;
        }
        showResultToast(label, ok, text);
      })
      .catch(function (err) {
        showResultToast(label, false, '요청 실패: ' + err);
      });
  }

  function buildDtlTarget(li) {
    var dtlIp = li.getAttribute('data-dtl-ip');
    var dtlLaneNo = li.getAttribute('data-dtl-lane');
    return {
      scope: 'dtl',
      dtlId: li.getAttribute('data-dtl-id'),
      gateType: li.getAttribute('data-gate-type'),
      label: dtlIp + ' / 레인 ' + dtlLaneNo,
      devices: [{ dtlIp: dtlIp, dtlLaneNo: dtlLaneNo }],
    };
  }

  function buildGrpTarget(details) {
    var grpId = details.getAttribute('data-grp-id');
    var grpName = details.getAttribute('data-grp-name');
    var locName = details.getAttribute('data-loc-name');
    var devices = collectDevicesForGrp(grpId);
    return {
      scope: 'grp',
      gateType: null,
      label: locName + ' / ' + grpName + ' (그룹 전체 ' + devices.length + '대)',
      devices: devices,
    };
  }

  function buildLocTarget(details) {
    var locId = details.getAttribute('data-loc-id');
    var locName = details.getAttribute('data-loc-name');
    var devices = collectDevicesForLoc(locId);
    return {
      scope: 'loc',
      gateType: null,
      label: locName + ' (위치 전체 ' + devices.length + '대)',
      devices: devices,
    };
  }

  function setupContextMenu(container) {
    var menu = document.getElementById('gate-tree-context-menu');
    if (!menu) return;
    var target = null; // 우클릭한 노드(DTL/GRP/LOC)로부터 계산한 대상 정보

    function hideMenu() { menu.style.display = 'none'; target = null; }

    container.addEventListener('contextmenu', function (e) {
      // DTL(레인) 노드가 가장 구체적인 대상이라 우선 확인하고, 아니라면 <summary>를 통해
      // GRP/LOC <details> 노드인지 확인한다(SR_Speed_Client의 "LOC/GRP/DTL 모든 노드에서
      // 게이트 제어 메뉴 표시"와 동일하게 세 레벨 모두에서 메뉴를 띄운다).
      var dtlLi = e.target.closest('.gt-dtl');
      var scopeDetails = !dtlLi ? (function () {
        var summary = e.target.closest('summary');
        return summary ? summary.parentElement : null;
      })() : null;

      var built = null;
      if (dtlLi) {
        built = buildDtlTarget(dtlLi);
      } else if (scopeDetails && scopeDetails.classList.contains('gt-grp')) {
        built = buildGrpTarget(scopeDetails);
      } else if (scopeDetails && scopeDetails.classList.contains('gt-loc')) {
        built = buildLocTarget(scopeDetails);
      }
      // 트리 바깥의 여백(빈 그룹 안내 문구 등)을 우클릭하면 커스텀 메뉴 없이 기본 브라우저
      // 메뉴를 그대로 둔다.
      if (!built) return;

      e.preventDefault();
      target = built;

      var header = menu.querySelector('.gate-tree-context-target');
      if (header) header.textContent = target.label;

      // "역방향 개방" 항목은 Flap DTL(gate_type_code=2)에서만 노출한다 — GRP/LOC 범위는
      // gateType이 null이라 항상 숨겨진다(SR_Speed_Client가 GRP/LOC 레벨에서 gate_type을
      // 0으로 취급해 이 항목을 절대 노출하지 않는 것과 동일).
      menu.querySelectorAll('[data-requires-gate-type]').forEach(function (menuLi) {
        menuLi.style.display = menuLi.getAttribute('data-requires-gate-type') === target.gateType ? '' : 'none';
      });
      // 리셋/모드 변경/모터 설정은 단일 레인(dtlId)이 있어야 동작하므로 DTL 범위에서만 노출한다.
      menu.querySelectorAll('[data-requires-scope]').forEach(function (menuLi) {
        menuLi.style.display = menuLi.getAttribute('data-requires-scope') === target.scope ? '' : 'none';
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
      // 모든 분기가 TypeError로 깨졌다 — 메뉴를 숨기기 전에 선택된 노드 정보를 별도 변수
      // (selected)로 옮겨두고 이후로는 이 변수만 사용한다.
      var selected = target;
      hideMenu();

      // 모드 변경/모터 설정은 페이지 이동(단일 dtlId 필요)이라 GRP/LOC 범위에서는 메뉴 자체가
      // 숨겨지지만, 방어적으로 한 번 더 확인한다.
      if (action === 'mode-change') {
        if (selected.scope !== 'dtl') return;
        window.location.href = '/gates/details/' + selected.dtlId + '/mode';
        return;
      }
      if (action === 'motor-setup') {
        if (selected.scope !== 'dtl') return;
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
        if (selected.scope !== 'dtl') return; // 메뉴에서도 숨김 — 대량 리셋은 이번 요청 범위 밖
        reportBatchResult(
          postCommandToDevices('/api/gate-control/reset', selected.devices, RESET_COMMANDS[action]),
          selected.label,
        );
        return;
      }
      if (OPERATION_COMMANDS[action]) {
        reportBatchResult(
          postCommandToDevices('/api/gate-control/command', selected.devices, OPERATION_COMMANDS[action]),
          selected.label,
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
      '.gate-tree .gt-loc>summary{cursor:context-menu;font-weight:600;padding:.25rem 0}' +
      '.gate-tree .gt-grp{margin-left:1.25rem}' +
      '.gate-tree .gt-grp>summary{cursor:context-menu;padding:.15rem 0}' +
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
