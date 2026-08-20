// Phase 10 — #18 대시보드 게이트 트리뷰(계획서 3절/2026-08-12 사용자 확인: 레거시 없이 신규 설계).
// LOC/GRP/DTL 3계층 트리(정렬: 위치ID→그룹ID→게이트ID, 2026-08-19) + 연결상태 아이콘 + 우클릭 제어
// 메뉴. GateTreeApiController(/api/gate-tree, 사용여부·분석여부 Y인 노드만 반환 —
// GateTreeService.buildTree 참고)를 폴링해 그리고, 제어는 기존 엔드포인트(/api/gate-control/command,
// /api/gate-control/reset, /gates/details/{id}/mode|motor)를 그대로 재사용한다.
// 우클릭 메뉴는 DTL(레인)뿐 아니라 GRP(그룹)/LOC(위치) 노드에서도 표시한다 — SR_Speed_Client
// 트리뷰(tvNet_NodeMouseClick, "LOC/GRP/DTL 모든 노드에서 게이트 제어 메뉴 표시")와 동일하게
// 맞췄다(2026-08-19 사용자 요청). 상단 5개 항목(개방/폐쇄/정상 복구/FREE 모드/역방향 개방)은
// SR_Speed_Client 트리뷰(SR_F_DashBoard.GateControl.cs ContextMenu_Init)와 동일하게 맞췄다
// (2026-08-19 사용자 요청). 같은 5개 항목을 LOC/GRP 노드에서도 동일하게 띄우고 하위 DTL 전체에
// 일괄 전송하도록 확장했다(2026-08-19 두 번째 요청 — 레거시가 노드 계층과 무관하게 항상 같은
// 메뉴를 띄우고 FullPath 하위 전체에 broadcast하는 것과 동일하게 "완전 교체"; dashboard.html의
// 우클릭 메뉴 마크업 주석 참고).
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
    // data-loc-id/data-grp-id/data-*-name: 우클릭 메뉴(setupContextMenu)가 그룹 노드 클릭 시
    // target.grpId/grpName과 표시 문구를 이 속성에서 그대로 읽는다.
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
    // data-loc-id/data-loc-name: 우클릭 메뉴(setupContextMenu)가 위치 노드 클릭 시
    // target.locId/locName과 표시 문구를 이 속성에서 그대로 읽는다.
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

  // [2026-08-19 사용자 요청] LOC/GRP 노드의 일괄 전송을 지원하려고 dtlIp/dtlLaneNo 두 인자 대신
  // 라벨 문자열 하나를 받도록 일반화했다 — 단일 게이트는 "IP / 레인 N", 다건 전송은 "위치 ~ N대"
  // 처럼 호출부가 원하는 라벨을 그대로 넘긴다.
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

  // 명령 전송 Promise에 결과 표시를 일괄로 연결한다 — 성공(2xx)/실패(4xx·5xx) 모두 r.ok/r.body를
  // 검사해 표시하고, 네트워크·JSON 파싱 실패(catch)도 동일하게 사용자에게 노출한다.
  function reportCommandResult(promise, dtlIp, dtlLaneNo) {
    return promise
      .then(function (r) {
        var described = describeResult(r);
        showResultToast(dtlIp + ' / 레인 ' + dtlLaneNo, described.ok, described.text);
      })
      .catch(function (err) {
        showResultToast(dtlIp + ' / 레인 ' + dtlLaneNo, false, '요청 실패: ' + err);
      });
  }

  // [2026-08-19 사용자 요청: 리뷰 후속] LOC/GRP 일괄 전송 시 같은 dtlIp(물리 컨트롤러)로 가는
  // 요청은 순차 전송하고, 서로 다른 dtlIp끼리는 병렬로 전송한다. 레거시 GateCtrlDataSet도 동일한
  // 이유로 이렇게 처리한다(SR_F_DashBoard.GateControl.cs 342행 주석: "같은 IP의 레인들은 이 그룹
  // 내부에서 순차 전송... 단일 제어 세션만 처리하는 장비에 최대 10개의 TCP 연결이 동시에 몰리면
  // 일부 레인 명령이 거부/타임아웃으로 유실될 수 있다") — 같은 물리 장비에 동시에 여러 명령이
  // 몰리는 것을 막기 위함이다. targets 배열과 같은 순서·개수의 Promise 배열을 반환하므로
  // reportBulkCommandResult의 성공/실패 집계는 그대로 사용할 수 있다. 앞선 요청이 실패해도(네트워크
  // 오류로 reject) 같은 IP의 다음 요청까지 막히면 안 되므로, 체인 연결에는 항상 resolve하는
  // sendFn 결과의 사본을 쓰고, 호출부에는 원래의(성공/실패가 살아있는) Promise를 그대로 돌려준다.
  function sendBulkSequentialByIp(targets, sendFn) {
    var chains = {}; // dtlIp -> 그 IP의 마지막 요청이 완료(성공/실패 무관)됐음을 나타내는 Promise
    return targets.map(function (t) {
      var wait = chains[t.dtlIp] || Promise.resolve();
      var result = wait.then(function () { return sendFn(t.dtlIp, t.dtlLaneNo); });
      chains[t.dtlIp] = result.then(function () {}, function () {});
      return result;
    });
  }

  // [2026-08-19 사용자 요청] LOC/GRP 노드 우클릭 → 일괄 전송 결과 요약. 레거시(GateCtrlDataSet)는
  // 게이트별 개별 실패만 로그로 남기고 UI에는 알리지 않았지만, 이 프로젝트는 앞서 Codex 적대적
  // 리뷰(2026-08-19)로 "실패를 조용히 삼키지 않는다"는 원칙을 세웠으므로 성공/실패 건수를 요약해
  // 토스트로 노출한다.
  // [Codex 적대적 리뷰 수정: high, 2026-08-19] "N/M대 성공"이라는 집계만으로는 운영자가 정확히
  // 어떤 게이트가 명령을 받지 못했는지 알 수 없어, 위험 상태(예: 개방 명령이 일부만 적용된 상태)를
  // 식별·복구할 방법이 없었다. targets를 함께 받아 실패한 게이트의 IP/레인을 텍스트에 그대로
  // 나열한다(너무 길어지지 않도록 최대 5건까지만 나열하고 나머지는 "외 N건"으로 축약).
  function reportBulkCommandResult(promises, targetLabel, targets) {
    var FAILED_LABEL_LIMIT = 5;
    return Promise.all(promises.map(function (p) {
      return p
        .then(function (r) { return describeResult(r); })
        .catch(function (err) { return { ok: false, text: '요청 실패: ' + err }; });
    })).then(function (results) {
      var okCount = results.filter(function (r) { return r.ok; }).length;
      var text = okCount + '/' + results.length + '대 성공';
      if (okCount < results.length) {
        var failedLabels = results
          .map(function (r, i) { return (!r.ok && targets[i]) ? (targets[i].dtlIp + '/레인' + targets[i].dtlLaneNo) : null; })
          .filter(Boolean);
        var shown = failedLabels.slice(0, FAILED_LABEL_LIMIT).join(', ');
        if (failedLabels.length > FAILED_LABEL_LIMIT) shown += ' 외 ' + (failedLabels.length - FAILED_LABEL_LIMIT) + '건';
        text += ' (' + (results.length - okCount) + '대 실패: ' + shown + ')';
      }
      showResultToast(targetLabel, okCount === results.length, text);
    });
  }

  // [Codex 적대적 리뷰 수정: high, 2026-08-19] 이전에는 대상마다 독립적으로 sendCommandWithReauth를
  // 호출해, 재인증이 활성화된 구성에서 게이트마다 따로 프롬프트가 떴다. 사용자가 중간에 프롬프트를
  // 취소하거나 일부만 응답하면 이미 인증된 일부 게이트에는 명령이 적용되고 나머지는 적용되지 않는
  // 상태로 남을 수 있었다. 대신 첫 대상으로 재인증 필요 여부를 먼저 확인한다 — 비밀번호 없이
  // 전송했을 때 GateControlReauthInterceptor가 {reauthRequired:true}를 반환한다는 것은 preHandle
  // 단계에서 컨트롤러(실제 명령 처리)에 도달하기 전에 차단됐다는 뜻이라, 이 시점까지는 어떤
  // 게이트에도 명령이 실제로 전달되지 않는다. 재인증이 필요하면 프롬프트를 단 한 번만 띄우고,
  // 취소하면 전체 일괄 전송을 여기서 중단한다(null 반환) — 부분 실행 없이 아무 게이트도 건드리지
  // 않은 채로 끝난다. 재인증이 필요 없는 구성이면 이 "확인 요청"이 곧 실제 첫 번째 명령 전송이라
  // 낭비 없이 그대로 이어간다.
  function sendBulkOperationCommand(targets, command) {
    if (targets.length === 0) return Promise.resolve([]);
    var first = targets[0];
    var rest = targets.slice(1);
    var firstPromise = postGateControl('/api/gate-control/command', first.dtlIp, first.dtlLaneNo, command);
    return firstPromise.then(function (r) {
      if (r.body && r.body.reauthRequired) {
        var password = window.prompt('게이트 제어 재인증 — 비밀번호를 입력하세요.');
        if (!password) return null;
        // 첫 대상도 아직 실행되지 않았으므로 targets 전체를 비밀번호와 함께 다시 보낸다.
        return sendBulkSequentialByIp(targets, function (dtlIp, dtlLaneNo) {
          return postGateControl('/api/gate-control/command', dtlIp, dtlLaneNo, command, password);
        });
      }
      // 재인증 불필요 — 첫 대상은 이미 전송·응답까지 끝났으니 그 결과를 그대로 쓰고 나머지만 보낸다.
      var restPromises = sendBulkSequentialByIp(rest, function (dtlIp, dtlLaneNo) {
        return postGateControl('/api/gate-control/command', dtlIp, dtlLaneNo, command);
      });
      return [Promise.resolve(r)].concat(restPromises);
    }, function () {
      // [Codex 적대적 리뷰 수정: high, 2026-08-19] 이전에는 첫 대상 요청 자체가 reject(응답 유실/
      // 비JSON 응답/네트워크 오류 등 — postGateControl의 res.json() 파싱 실패 포함)되면 이 then의
      // 성공 콜백이 전혀 실행되지 않아 함수 전체가 reject되고, 재인증이 필요 없는 나머지 N-1대는
      // 통째로 전송조차 되지 않았다. 상시 개방/폐쇄/FREE 같은 위험 명령이 첫 게이트에만(그것도
      // 실제 적용 여부조차 불명한 채) 적용되고 나머지는 아예 시도되지 않는 부분 적용 상태를
      // 만들 수 있었다. 첫 요청이 reject되면 재인증 필요 여부를 알 수 없으므로(preHandle 응답을
      // 못 받음) 재인증이 필요 없는 구성이라고 가정하고 나머지 대상에는 비밀번호 없이 그대로
      // 전송한다 — 실제로 재인증이 필요한 구성이었다면 그 응답들도 reauthRequired로 개별 실패
      // 처리되어 운영자가 식별할 수 있다(집단 프롬프트를 다시 띄우지는 않는다 — "프롬프트 1회"
      // 원칙 유지). 첫 대상의 원래 reject는 감추지 않고 결과 배열에 그대로 보존해, 실제 적용
      // 여부가 불명확한 상태로 reportBulkCommandResult가 "요청 실패"로 명시하게 한다.
      var restPromises = sendBulkSequentialByIp(rest, function (dtlIp, dtlLaneNo) {
        return postGateControl('/api/gate-control/command', dtlIp, dtlLaneNo, command);
      });
      return [firstPromise].concat(restPromises);
    });
  }

  function setupContextMenu(container) {
    var menu = document.getElementById('gate-tree-context-menu');
    if (!menu) return;
    var target = null; // 우클릭한 노드(DTL/GRP/LOC)로부터 계산한 대상 정보

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
        target = {
          type: nodeType,
          grpId: node.getAttribute('data-grp-id'),
          grpName: node.getAttribute('data-grp-name'),
          // [2026-08-19 사용자 요청: "완전 교체"] LOC/GRP 노드도 DTL과 동일한 5개 제어 명령을
          // 하위 게이트 전체에 일괄 전송한다(레거시 GateCtrlDataSet의 broadcast와 동일 의도) —
          // 서버에 별도 broadcast 엔드포인트가 없어, 우클릭 시점에 이 노드 하위의 .gt-dtl DOM에서
          // dtlIp/dtlLaneNo/gateType을 모아두고 클릭 시 순회 전송한다.
          dtlList: Array.prototype.map.call(node.querySelectorAll('.gt-dtl'), function (el) {
            return {
              dtlIp: el.getAttribute('data-dtl-ip'),
              dtlLaneNo: el.getAttribute('data-dtl-lane'),
              gateType: el.getAttribute('data-gate-type'),
            };
          }),
        };
        if (header) header.textContent = target.grpName;
      } else {
        nodeType = 'loc';
        target = {
          type: nodeType,
          locId: node.getAttribute('data-loc-id'),
          locName: node.getAttribute('data-loc-name'),
          dtlList: Array.prototype.map.call(node.querySelectorAll('.gt-dtl'), function (el) {
            return {
              dtlIp: el.getAttribute('data-dtl-ip'),
              dtlLaneNo: el.getAttribute('data-dtl-lane'),
              gateType: el.getAttribute('data-gate-type'),
            };
          }),
        };
        if (header) header.textContent = target.locName;
      }

      // 리셋/모드 변경/모터 설정은 여전히 DTL 전용이다(레거시에도 없던 항목이거나 개발자용으로만
      // 존재 — dashboard.html 우클릭 메뉴 주석 참고). 5개 제어 명령(개방~역방향 개방)은 세 계층
      // 모두에서 항상 노출되므로 이 필터 대상이 아니다(마크업에 data-requires-node-type 없음).
      menu.querySelectorAll('[data-requires-node-type]').forEach(function (menuLi) {
        menuLi.style.display = menuLi.getAttribute('data-requires-node-type') === nodeType ? '' : 'none';
      });

      // "역방향 개방" 항목은 Flap 게이트(gate_type_code=2)에서만 노출한다 — 다른 타입 게이트는
      // 명령 자체를 지원하지 않아, 항목을 감추는 것이 클릭 후 실패 응답을 받는 것보다 낫다.
      // DTL 노드는 자신의 gateType을, LOC/GRP 노드는 하위 DTL 중 Flap 타입이 하나라도 있는지를 본다.
      var reverseCapable = nodeType === 'dtl'
        ? target.gateType === '2'
        : target.dtlList.some(function (d) { return d.gateType === '2'; });
      menu.querySelectorAll('[data-requires-gate-type]').forEach(function (menuLi) {
        menuLi.style.display = reverseCapable ? '' : 'none';
      });

      // [Codex 리뷰 수정: P2] display:none 상태에서 offsetWidth/offsetHeight를 읽으면 항상 0이라
      // 200px 폴백만 쓰여, 항목이 10개 이상으로 늘어난 DTL 메뉴(실제 높이 약 400px)를 화면
      // 아래쪽에서 열면 리셋/모드 변경/모터 설정 같은 하단 항목이 뷰포트 밖으로 잘려 클릭할 수
      // 없었다. visibility:hidden 상태로 먼저 display:block(레이아웃에는 참여하되 화면에는 안
      // 보임)해서 실제 크기를 측정한 뒤 위치를 계산하고, 그다음에야 보이게 전환한다.
      menu.style.visibility = 'hidden';
      menu.style.display = 'block';
      var menuWidth = menu.offsetWidth || 200;
      var menuHeight = menu.offsetHeight || 200;
      var x = Math.min(e.clientX, window.innerWidth - menuWidth - 8);
      var y = Math.min(e.clientY, window.innerHeight - menuHeight - 8);
      menu.style.left = Math.max(0, x) + 'px';
      menu.style.top = Math.max(0, y) + 'px';
      menu.style.visibility = 'visible';
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
        // 리셋은 여전히 DTL 전용이라 selected는 항상 단일 게이트다(메뉴 필터가 이미 보장).
        reportCommandResult(
          sendResetWithReauth(selected.dtlIp, selected.dtlLaneNo, RESET_COMMANDS[action]),
          selected.dtlIp, selected.dtlLaneNo,
        );
        return;
      }
      if (OPERATION_COMMANDS[action]) {
        // [2026-08-19 사용자 요청: "완전 교체"] LOC/GRP 노드에서는 selected.dtlList(우클릭 시점에
        // 모아둔 하위 게이트 목록)에 동일 명령을 순회 전송한다 — 레거시 GateCtrlDataSet의
        // FullPath 하위 broadcast와 동일 의도. 역방향 개방은 Flap 타입(gate_type_code=2)에만
        // 의미가 있어 그 중에서도 한 번 더 필터링한다(메뉴 자체는 하위에 Flap이 하나라도 있으면
        // 노출되므로, 섞여 있는 다른 타입 게이트로는 보내지 않는다).
        var isBulk = selected.type !== 'dtl';
        var bulkTargets = isBulk
          ? selected.dtlList.filter(function (d) { return action !== 'gate-reverse-open' || d.gateType === '2'; })
          : null;

        if (isBulk && bulkTargets.length === 0) {
          showResultToast(selected.type === 'loc' ? selected.locName : selected.grpName, false, '대상 게이트가 없습니다.');
          return;
        }

        var targetLabel = !isBulk
          ? (selected.dtlIp + ' / 레인 ' + selected.dtlLaneNo)
          : (selected.type === 'loc' ? '위치 "' + selected.locName + '"' : '그룹 "' + selected.grpName + '"')
            + ' 하위 게이트 ' + bulkTargets.length + '대';

        if (CONFIRM_MESSAGES[action]) {
          var confirmed = window.confirm(targetLabel + '\n\n' + CONFIRM_MESSAGES[action] + '\n\n계속하시겠습니까?');
          if (!confirmed) return;
        }

        if (!isBulk) {
          reportCommandResult(
            sendCommandWithReauth(selected.dtlIp, selected.dtlLaneNo, OPERATION_COMMANDS[action]),
            selected.dtlIp, selected.dtlLaneNo,
          );
        } else {
          // [Codex 적대적 리뷰 수정: high] 재인증은 대상 전체에 대해 딱 한 번만 확인한다
          // (sendBulkOperationCommand 참고) — 취소 시 어떤 게이트도 건드리지 않고 전체 중단한다.
          sendBulkOperationCommand(bulkTargets, OPERATION_COMMANDS[action])
            .then(function (promises) {
              if (!promises) {
                showResultToast(targetLabel, false, '재인증이 취소되어 전송하지 않았습니다.');
                return;
              }
              reportBulkCommandResult(promises, targetLabel, bulkTargets);
            })
            .catch(function (err) {
              showResultToast(targetLabel, false, '요청 실패: ' + err);
            });
        }
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

  // 테스트 전용 훅(2026-08-20 Opus 전체 리뷰 지적 — "가장 자주 깨지는 코드가 가장 검증이 없다").
  // 프로덕션 동작에는 영향이 없다: 브라우저가 <script> 태그로 이 파일을 로드할 때는 CommonJS
  // `module` 전역이 존재하지 않으므로 이 블록은 실행되지 않는다. Vitest(Node/jsdom) 환경에서
  // `require('.../gate-tree.js')`로 로드할 때만 내부 함수를 노출해 순수 로직을 단위 테스트할 수
  // 있게 한다. 아래 자동 실행 블록(폴링 시작 등)도 같은 이유로 이 환경에서는 건너뛴다 — 그렇지
  // 않으면 테스트가 import하는 순간 실제 fetch()/setInterval이 걸려 부작용이 생긴다.
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
      escapeHtml: escapeHtml,
      renderDetail: renderDetail,
      renderGroup: renderGroup,
      renderLocation: renderLocation,
      render: render,
      describeResult: describeResult,
      sendBulkSequentialByIp: sendBulkSequentialByIp,
      reportBulkCommandResult: reportBulkCommandResult,
      sendBulkOperationCommand: sendBulkOperationCommand,
      __setPostGateControl: function (fn) { postGateControl = fn; },
    };
    return;
  }

  var container = document.getElementById('gate-tree');
  if (!container) return;
  injectStyles();
  setupContextMenu(container);
  loadTree(container);
  setInterval(function () { loadTree(container); }, POLL_INTERVAL_MS);
})();
