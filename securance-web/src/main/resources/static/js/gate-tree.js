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
// [모바일 터치 선택 지원, 2026-09-02] 지금까지는 노드 선택/제어 메뉴 진입이 전부 마우스 우클릭
// (contextmenu 이벤트)에만 의존해, 모바일에서는 어떤 노드를 선택했는지 확인할 방법도(선택 표시
// 없음) 제어 메뉴를 열 방법도(iOS Safari는 일반 요소에 contextmenu를 보내지 않고, Android도
// 기종마다 신뢰할 수 없다) 없었다. 탭으로 노드를 선택·시각적으로 표시(.gt-selected, 폴링
// 재렌더링 후에도 유지)하고, 롱프레스(500ms)로 우클릭과 동일한 제어 메뉴를 열도록 보완했다
// (아래 selectNode/showContextMenuForNode/touchstart 핸들러 참고). 데스크톱 우클릭 동작은
// 그대로 유지한다.
(function () {
  'use strict';

  var POLL_INTERVAL_MS = 5000; // dashboard-realtime.js의 SUMMARY 주기와 동일하게 맞춘다.

  // <details> 펼침/접힘 상태는 폴링마다 트리 전체를 다시 그리므로 별도로 기억해둔다
  // (기본값: 위치는 펼침, 그룹은 접힘 — 그룹이 많으면 화면이 너무 길어지는 것을 방지).
  var expandState = Object.create(null);

  function isExpanded(key, defaultValue) {
    return Object.prototype.hasOwnProperty.call(expandState, key) ? expandState[key] : defaultValue;
  }

  // [모바일 터치 선택 지원] 이전에는 노드 선택 상태를 표시하는 방법이 전혀 없었다 — 우클릭
  // 메뉴(setupContextMenu)만 선택 대상을 내부 변수(target)로 들고 있을 뿐 화면에는 아무 표시도
  // 없었고, 터치 기기에는 우클릭 자체가 없어(iOS Safari는 일반 요소에 contextmenu 이벤트를 아예
  // 보내지 않는다) 어떤 노드를 선택했는지 확인할 방법도, 선택할 방법도 없었다. 선택된 노드의
  // data-node-key를 기억해두고(폴링 재렌더링 후에도 유지 — expandState와 동일한 이유),
  // 렌더링마다 해당 노드에 .gt-selected 클래스를 다시 입혀 시각적으로 표시한다.
  var selectedNodeKey = null;

  function nodeKeyOf(node) {
    if (node.classList.contains('gt-dtl')) return 'dtl-' + node.getAttribute('data-dtl-id');
    if (node.classList.contains('gt-grp')) return 'grp-' + node.getAttribute('data-grp-id');
    return 'loc-' + node.getAttribute('data-loc-id');
  }

  function selectNode(node) {
    if (!node) return;
    var container = node.closest('.gate-tree') || node.parentElement;
    if (container) {
      var prev = container.querySelector('.gt-selected');
      if (prev && prev !== node) prev.classList.remove('gt-selected');
    }
    node.classList.add('gt-selected');
    selectedNodeKey = nodeKeyOf(node);
  }

  // 폴링마다 트리를 통째로 다시 그리므로(render), 선택 표시도 매번 새 DOM에 다시 입혀야 유지된다.
  function applySelectionHighlight(container) {
    if (!selectedNodeKey) return;
    var el = container.querySelector('[data-node-key="' + selectedNodeKey + '"]');
    if (el) el.classList.add('gt-selected');
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
    //
    // 코드 리뷰 지적(2026-08-28): 예전에는 그룹의 gateTypeCode(그룹 레벨 캐시값, 이제 삭제됨)를
    // 모든 하위 레인에 그대로 썼다 — 레인이 실제로 그룹과 다른 타입일 수 있는데도(그룹 전체가
    // 항상 같은 타입이라는 보장이 없다) 그룹값을 대신 쓰는 것 자체가 부정확했다. 레인 자신의
    // 실제 dtlType(GateTreeDetailNode.dtlType, GateDetail.dtlType 원본)을 쓴다.
    //
    // data-node-key: 모바일 터치 선택 상태(gt-selected)를 폴링 재렌더링 후에도 복원하는 데 쓴다
    // (아래 applySelectionHighlight 참고 — <details> open/close를 expandState로 기억하는 것과
    // 같은 이유).
    return '<li class="gt-dtl" data-node-key="dtl-' + d.dtlId + '" data-dtl-id="' + d.dtlId +
      '" data-dtl-ip="' + escapeHtml(d.dtlIp) +
      '" data-dtl-lane="' + d.dtlLaneNo + '" data-online="' + d.online +
      '" data-gate-type="' + d.dtlType + '">' +
      statusIcon(d.online) +
      ' <span class="gt-dtl-label">' + label + '</span>' +
      ' <span class="text-muted small">(' + escapeHtml(d.dtlIp) + ' / 레인 ' + d.dtlLaneNo + ')</span>' +
      '</li>';
  }

  function renderGroup(loc, grp) {
    var key = 'grp-' + grp.grpId;
    var open = isExpanded(key, false);
    var onlineCount = grp.details.filter(function (d) { return d.online; }).length;
    var detailsHtml = grp.details.length
      ? '<ul class="gt-dtl-list">' + grp.details.map(function (d) { return renderDetail(loc, grp, d); }).join('') + '</ul>'
      : '<div class="text-muted small ms-4">등록된 레인이 없습니다.</div>';
    // 코드 리뷰 지적(2026-08-28): 그룹 배지에 붙던 게이트 타입 이름을 없앴다 — 게이트 타입은
    // 레인(dtl) 단위 값으로만 존재하고(GateGroup.gateTypeCode 삭제), 한 그룹의 레인들이 서로 다른
    // 타입일 수 있어 그룹 대표값 하나로 요약할 수 없다. 타입은 각 레인 항목에서 확인한다.
    // data-loc-id/data-grp-id/data-*-name: 우클릭 메뉴(setupContextMenu)가 그룹 노드 클릭 시
    // target.grpId/grpName과 표시 문구를 이 속성에서 그대로 읽는다.
    return '<details class="gt-grp" data-key="' + key + '" data-node-key="grp-' + grp.grpId +
      '" data-loc-id="' + loc.locId +
      '" data-grp-id="' + grp.grpId + '" data-loc-name="' + escapeHtml(loc.locName) +
      '" data-grp-name="' + escapeHtml(grp.grpName) + '"' + (open ? ' open' : '') + '>' +
      '<summary><i class="bi bi-diagram-3"></i> ' + escapeHtml(grp.grpName) +
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
    return '<details class="gt-loc" data-key="' + key + '" data-node-key="loc-' + loc.locId +
      '" data-loc-id="' + loc.locId +
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
    applySelectionHighlight(container);
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

  // [버그 수정: 2026-08-21] DTL 단일 대상 제어(개방/폐쇄/복구/FREE/역방향 개방/리셋)가 클릭 시
  // sendCommandWithReauth/sendResetWithReauth를 호출하는데, 두 함수가 이 파일 어디에도 정의돼
  // 있지 않았다 — reportCommandResult(fn(...), ...) 형태로 인자 자리에서 즉시 호출되므로
  // ReferenceError가 클릭 핸들러 안에서 동기적으로 던져져, catch도 안 되고 fetch 자체가 나가지
  // 않았다(콘솔 에러만 남고 화면엔 아무 반응도 없었다). 그 결과 트리뷰에서 DTL 노드를 우클릭해
  // 게이트 제어를 눌러도 tb_data_snd 이력 저장은커녕 서버 요청조차 발생하지 않아 실행 상태를
  // 확인할 방법이 없었다(LOC/GRP 일괄 전송은 sendBulkOperationCommand가 postGateControl을 직접
  // 호출해 정상 동작했다 — 이 버그는 DTL 단일 대상에만 있었다). dashboard-realtime.js의
  // sendResetCommand와 동일한 패턴(재인증 필요 시 비밀번호 프롬프트로 단 한 번만 자동 재시도)으로
  // 구현한다. 두 엔드포인트(command/reset)는 경로만 다르고 재시도 로직이 완전히 같으므로,
  // postGateControl과 같은 이유로 공유 헬퍼(sendWithReauth)로 뽑아 중복을 없앤다.
  function sendWithReauth(path, dtlIp, dtlLaneNo, command, reauthPassword) {
    return postGateControl(path, dtlIp, dtlLaneNo, command, reauthPassword)
      .then(function (r) {
        if (r.body && r.body.reauthRequired && !reauthPassword) {
          var password = window.prompt('게이트 제어 재인증 — 비밀번호를 입력하세요.');
          if (!password) return { ok: false, status: r.status, body: { message: '재인증이 취소되어 전송하지 않았습니다.' } };
          return sendWithReauth(path, dtlIp, dtlLaneNo, command, password);
        }
        return r;
      });
  }

  function sendCommandWithReauth(dtlIp, dtlLaneNo, command, reauthPassword) {
    return sendWithReauth('/api/gate-control/command', dtlIp, dtlLaneNo, command, reauthPassword);
  }

  function sendResetWithReauth(dtlIp, dtlLaneNo, command, reauthPassword) {
    return sendWithReauth('/api/gate-control/reset', dtlIp, dtlLaneNo, command, reauthPassword);
  }

  function setupContextMenu(container) {
    var menu = document.getElementById('gate-tree-context-menu');
    if (!menu) return;
    var target = null; // 우클릭한 노드(DTL/GRP/LOC)로부터 계산한 대상 정보
    // 롱프레스로 메뉴를 연 직후 touchend가 발생시키는 합성 click(ghost click)을 걸러내는 플래그.
    // touchstart는 passive 리스너라 그 안에서 preventDefault를 호출해도 효과가 없고, 타이머
    // 콜백(비동기) 안에서 호출하는 것은 애초에 원본 이벤트 스코프를 벗어나 아무 의미가 없다 —
    // 그래서 합성 click을 막는 대신, 짧은 시간 동안만 무시하도록 플래그로 처리한다. 이 플래그가
    // 없으면 메뉴를 연 직후의 ghost click이 아래 document 클릭 리스너(메뉴 바깥 클릭 시 hideMenu)에
    // 걸려 메뉴가 뜨자마자 다시 닫혀버린다.
    var suppressNextClick = false;

    function hideMenu() { menu.style.display = 'none'; target = null; }

    // [모바일 터치 선택 지원] 이전에는 이 로직 전체가 'contextmenu' 이벤트 핸들러 안에만 있어서
    // 마우스 우클릭에서만 동작했다. iOS Safari는 일반 요소에서 contextmenu 이벤트를 아예 보내지
    // 않고, Android Chrome도 길게 눌러야만(그리고 기종에 따라 신뢰할 수 없게) 보내므로 모바일에서는
    // 사실상 게이트 제어 메뉴에 진입할 방법이 없었다. 메뉴를 열고 위치를 계산하는 로직을 노드/좌표를
    // 받는 함수로 뽑아, 아래 touchstart 롱프레스 핸들러에서도 그대로 재사용한다.
    function showContextMenuForNode(node, clientX, clientY) {
      selectNode(node); // 메뉴를 여는 시점에 선택 표시도 함께 갱신한다 — 어떤 노드를 조작 중인지 확인할 수 있게.

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
      var x = Math.min(clientX, window.innerWidth - menuWidth - 8);
      var y = Math.min(clientY, window.innerHeight - menuHeight - 8);
      menu.style.left = Math.max(0, x) + 'px';
      menu.style.top = Math.max(0, y) + 'px';
      menu.style.visibility = 'visible';
    }

    // [버그 수정: 2026-08-19] 이전에는 '.gt-dtl'만 찾아, 위치(.gt-loc)/그룹(.gt-grp) 노드를
    // 우클릭하면 li가 항상 null이라 메뉴 자체가 뜨지 않고(브라우저 기본 메뉴만 표시) "게이트
    // 관리 팝업" 진입 방법도 없었다. 세 계층 셀렉터를 함께 찾아 가장 가까운 노드를 판별한다
    // (DOM이 loc > grp > dtl로 중첩돼 있어 closest()가 항상 가장 안쪽 노드부터 매칭한다).
    container.addEventListener('contextmenu', function (e) {
      var node = e.target.closest('.gt-dtl, .gt-grp, .gt-loc');
      if (!node) return;
      e.preventDefault();
      showContextMenuForNode(node, e.clientX, e.clientY);
    });

    // [모바일 터치 선택 지원] 짧게 탭하면 노드를 "선택"만 하고(선택 표시 갱신 — LOC/GRP는 <summary>
    // 클릭의 기본 펼침/접힘 동작도 그대로 유지된다), 일정 시간 이상 눌러 유지하면(롱프레스) 우클릭과
    // 동일한 게이트 제어 메뉴를 연다. 데스크톱 우클릭 동작(위 contextmenu 리스너)은 그대로 두고
    // 터치 전용 보완 경로로 추가한다.
    container.addEventListener('click', function (e) {
      if (suppressNextClick) return;
      var node = e.target.closest('.gt-dtl, .gt-grp, .gt-loc');
      if (!node) return;
      selectNode(node);
    });

    var LONG_PRESS_MS = 500; // 롱프레스로 인식할 최소 유지 시간.
    var TOUCH_MOVE_TOLERANCE_PX = 10; // 이 이상 손가락이 움직이면 스크롤 의도로 보고 롱프레스를 취소한다.
    var longPressTimer = null;
    var touchStartPos = null;

    function clearLongPressTimer() {
      if (longPressTimer) {
        clearTimeout(longPressTimer);
        longPressTimer = null;
      }
    }

    container.addEventListener('touchstart', function (e) {
      // [Codex 적대적 리뷰 수정: medium, 2026-09-02] 이전에는 손가락이 1개가 아니면(멀티터치)
      // 아무 것도 하지 않고 그냥 return했다 — 첫 손가락으로 이미 시작된 롱프레스 타이머는 그대로
      // 살아 있어, 두 번째 손가락이 닿아 두 손가락 스크롤/핀치로 전환한 뒤에도 500ms 뒤 최초
      // 노드의 제어 메뉴가 열리고 그 노드가 선택돼버렸다. 손가락이 1개가 아닌 순간 진행 중이던
      // 롱프레스를 즉시 취소한다.
      if (!e.touches || e.touches.length !== 1) {
        clearLongPressTimer();
        touchStartPos = null;
        return;
      }
      var node = e.target.closest('.gt-dtl, .gt-grp, .gt-loc');
      if (!node) return;
      var touch = e.touches[0];
      touchStartPos = { x: touch.clientX, y: touch.clientY };
      clearLongPressTimer();
      longPressTimer = setTimeout(function () {
        longPressTimer = null;
        suppressNextClick = true; // 곧 뒤따라올 ghost click을 한 번 무시한다(위 선언부 설명 참고).
        showContextMenuForNode(node, touchStartPos.x, touchStartPos.y);
      }, LONG_PRESS_MS);
    }, { passive: true });

    container.addEventListener('touchmove', function (e) {
      // 위 touchstart와 동일한 이유 — 이동 중 두 번째 손가락이 닿아 멀티터치로 전환되면 즉시
      // 취소한다(단일 터치 상태에서 진입했더라도 그 이후 멀티터치로 바뀔 수 있다).
      if (!e.touches || e.touches.length !== 1) {
        clearLongPressTimer();
        return;
      }
      if (!longPressTimer || !touchStartPos) return;
      var touch = e.touches[0];
      var dx = touch.clientX - touchStartPos.x;
      var dy = touch.clientY - touchStartPos.y;
      if (Math.sqrt(dx * dx + dy * dy) > TOUCH_MOVE_TOLERANCE_PX) clearLongPressTimer();
    }, { passive: true });

    // [Codex 리뷰 수정: P2, 2026-09-02] 이전에는 롱프레스가 fire된 시점부터 350ms 뒤에 억제
    // 플래그를 무조건 해제했다 — 사용자가 메뉴가 열린 뒤에도 손가락을 350ms 이상 더 누르고 있으면
    // (즉 총 850ms 이상 누르는, 흔한 롱프레스) touchend 시점에 이미 플래그가 풀려 있어 뒤따르는
    // ghost click이 그대로 통과해 방금 연 메뉴를 즉시 닫아버렸다. 손가락을 얼마나 오래 누르고
    // 있었는지와 무관하게 항상 안전하도록, 리셋 타이머를 "롱프레스가 fire된 시점"이 아니라
    // "손가락을 뗀 시점(touchend)"을 기준으로 잡는다 — ghost click은 항상 touchend 직후에 발생하므로
    // 이렇게 하면 몇 초를 누르고 있어도 안전하다.
    function onTouchEnd() {
      clearLongPressTimer();
      if (suppressNextClick) {
        setTimeout(function () { suppressNextClick = false; }, 350);
      }
    }
    container.addEventListener('touchend', onTouchEnd);
    container.addEventListener('touchcancel', onTouchEnd);

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
        if (selected.type !== 'dtl') return;
        window.location.href = '/gates/details/' + selected.dtlId + '/mode';
        return;
      }
      if (action === 'motor-setup') {
        if (selected.type !== 'dtl') return;
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
      // 롱프레스로 메뉴를 막 연 직후의 ghost click까지 "메뉴 바깥 클릭"으로 처리되면 메뉴가 뜨자마자
      // 다시 닫혀버리므로, 같은 플래그로 걸러낸다(위 suppressNextClick 선언부 설명 참고).
      if (suppressNextClick) return;
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
      '.gate-tree .gt-dtl{padding:.1rem .25rem;cursor:context-menu;border-radius:.25rem}' +
      '.gate-tree .gt-status{font-size:.6rem;margin-right:.25rem}' +
      // [모바일 터치 선택 지원] 롱프레스(500ms)로 우리 JS가 제어 메뉴를 여는데, 그 사이 iOS/Android
      // 기본 동작(텍스트 선택 말풍선, iOS 콜아웃 메뉴)이 먼저 끼어들면 두 UI가 동시에 뜨는 충돌이
      // 생긴다. 노드 한 줄 영역에서는 텍스트 선택/콜아웃을 꺼서 우리 메뉴만 뜨도록 한다.
      '.gate-tree .gt-dtl,.gate-tree .gt-loc>summary,.gate-tree .gt-grp>summary{' +
      '-webkit-user-select:none;user-select:none;-webkit-touch-callout:none;touch-action:manipulation}' +
      // [모바일 터치 선택 지원] 탭/롱프레스로 선택한 노드를 시각적으로 표시한다 — 이전에는 어떤
      // 노드를 선택(우클릭 대상으로 지정)했는지 화면에서 전혀 확인할 수 없었다. LOC/GRP는
      // <summary>에, DTL은 <li> 자신에 직접 강조 스타일을 준다.
      // <details>(gt-loc/gt-grp) 자신에 강조를 주면 펼쳐진 하위 트리 전체가 감싸여 보이므로,
      // LOC/GRP는 <summary> 헤더 줄에만 강조를 준다 — DTL(<li>)은 자신이 한 줄이라 그대로 적용한다.
      '.gate-tree .gt-dtl.gt-selected{background:rgba(13,110,253,.15);outline:1px solid rgba(13,110,253,.4)}' +
      '.gate-tree .gt-loc.gt-selected>summary,.gate-tree .gt-grp.gt-selected>summary{' +
      'background:rgba(13,110,253,.15);outline:1px solid rgba(13,110,253,.4);border-radius:.25rem}' +
      '.gate-tree-context-menu{min-width:200px;z-index:1080}' +
      // 우클릭 메뉴로 보낸 명령의 성공/실패를 표시하는 토스트(2026-08-19 Codex 적대적 리뷰 대응).
      '#gate-tree-toast{display:none;position:fixed;right:1rem;bottom:1rem;max-width:360px;' +
      'padding:.6rem 1rem;border-radius:.375rem;color:#fff;font-size:.9rem;z-index:1090;' +
      'box-shadow:0 .25rem .75rem rgba(0,0,0,.3)}' +
      '#gate-tree-toast.gate-tree-toast-ok{background:#198754}' +
      '#gate-tree-toast.gate-tree-toast-error{background:#dc3545}' +
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
      selectNode: selectNode,
      nodeKeyOf: nodeKeyOf,
      setupContextMenu: setupContextMenu,
      describeResult: describeResult,
      sendBulkSequentialByIp: sendBulkSequentialByIp,
      reportBulkCommandResult: reportBulkCommandResult,
      sendBulkOperationCommand: sendBulkOperationCommand,
      sendCommandWithReauth: sendCommandWithReauth,
      sendResetWithReauth: sendResetWithReauth,
      setupContextMenu: setupContextMenu,
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

  syncTreeColumnHeight();
  window.addEventListener('resize', debounce(syncTreeColumnHeight, 150));
  // 대시보드 실시간 갱신(SmallBox 카운트)이 자릿수 변화 등으로 오른쪽 컬럼 높이를 미세하게
  // 바꿀 수 있어 폴링 주기에 맞춰 재동기화한다(dashboard-realtime.js의 SUMMARY 주기와 별개로
  // 이 값 하나만 가볍게 재계산).
  setInterval(syncTreeColumnHeight, POLL_INTERVAL_MS);
})();
