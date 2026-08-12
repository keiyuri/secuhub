// Phase 3 실시간 대시보드(#18/#1/#17) 클라이언트 — 순정 WebSocket API만 사용(별도 라이브러리 없음).
// 서버(DashboardPushService)가 5초마다 SUMMARY, 3초마다 새 ALERT를 보낸다.
(function () {
  'use strict';

  function connect() {
    var protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    var socket = new WebSocket(protocol + '//' + location.host + '/ws/dashboard');

    socket.addEventListener('message', function (event) {
      var payload;
      try {
        payload = JSON.parse(event.data);
      } catch (e) {
        return;
      }
      if (payload.type === 'SUMMARY') {
        applySummary(payload);
      } else if (payload.type === 'ALERT') {
        showAlertModal(payload);
      }
    });

    // 재연결: 탭이 오래 열려있거나 서버 재기동 시 끊길 수 있어, 3초 후 재시도한다.
    socket.addEventListener('close', function () {
      setTimeout(connect, 3000);
    });
    socket.addEventListener('error', function () {
      socket.close();
    });
  }

  function setText(id, value) {
    var el = document.getElementById(id);
    if (el) el.textContent = value;
  }

  function applySummary(payload) {
    var summary = payload.summary || {};
    setText('rt-online-count', summary.onlineGateCount);
    setText('rt-offline-count', summary.offlineGateCount);
    setText('rt-unresolved-count', summary.unresolvedErrorCount);
    setText('rt-traffic-count', summary.todayTrafficCount);

    var body = document.querySelector('#gateErrorsBody tbody');
    if (!body) return;
    var rows = payload.recentErrors || [];
    if (rows.length === 0) {
      body.innerHTML = '<tr><td colspan="4" class="text-center text-muted">최근 미해결 오류가 없습니다.</td></tr>';
      return;
    }
    // escapeHtml()로 모든 값을 이스케이프한 뒤에만 innerHTML에 대입한다(서버가 DB에서 읽어온
    // dtlIp/description 등은 신뢰할 수 없는 입력으로 취급).
    body.innerHTML = rows.map(function (row) {
      return '<tr><td>' + escapeHtml(row.dtlIp) + '</td><td>' + escapeHtml(row.description) +
        '</td><td>' + escapeHtml(row.analDate) + '</td><td>' + escapeHtml(row.resolveYn) + '</td></tr>';
    }).join('');
  }

  // #1 SR_F_GateControl(장애 알림 팝업) / #17 SR_F_Warning(화재 경고 팝업) — 리셋 실행은
  // GateControlApiController(/api/gate-control/reset, gate-control.html과 동일한 엔드포인트)를
  // 그대로 호출한다(2026-08-11 B3, 계획서 3절 C4). 화재 경보는 시스템 리셋(RESET_SYSTEM),
  // 그 외(모터 장애 등)는 모터 리셋(RESET_MOTOR)으로 매핑한다 — 레거시 SR_F_Warning/GateControl의
  // 화재↔시스템 리셋, 모터 장애↔모터 리셋 대응과 동일하다.
  function showAlertModal(payload) {
    var isFire = payload.alertType === 'FIRE';
    var modalEl = document.getElementById('rt-alert-modal');
    if (!modalEl) return;
    modalEl.querySelector('.modal-title').textContent = isFire ? '화재 경고' : '게이트 장애 알림';
    modalEl.querySelector('.modal-header').className = 'modal-header ' + (isFire ? 'bg-danger text-white' : 'bg-warning');
    modalEl.querySelector('.rt-alert-ip').textContent = payload.dtlIp || '-';
    modalEl.querySelector('.rt-alert-desc').textContent = payload.description || '-';
    modalEl.querySelector('.rt-alert-date').textContent = payload.analDate || '-';

    var resultEl = modalEl.querySelector('.rt-alert-result');
    if (resultEl) { resultEl.textContent = ''; resultEl.className = 'rt-alert-result small mt-2'; }

    var resetBtn = modalEl.querySelector('.rt-alert-reset');
    if (resetBtn) {
      resetBtn.disabled = false;
      resetBtn.textContent = '리셋 실행';
      resetBtn.onclick = function () { sendResetCommand(payload, resetBtn, resultEl); };
    }

    if (window.bootstrap && window.bootstrap.Modal) {
      window.bootstrap.Modal.getOrCreateInstance(modalEl).show();
    }
  }

  function sendResetCommand(payload, resetBtn, resultEl, reauthPassword) {
    if (!payload.dtlIp || payload.dtlLaneNo === undefined || payload.dtlLaneNo === null) {
      if (resultEl) { resultEl.className = 'rt-alert-result small mt-2 text-danger'; resultEl.textContent = '리셋 대상 정보가 없습니다.'; }
      return;
    }
    var csrfToken = document.querySelector('meta[name="_csrf"]');
    var csrfHeader = document.querySelector('meta[name="_csrf_header"]');
    var command = payload.alertType === 'FIRE' ? 'RESET_SYSTEM' : 'RESET_MOTOR';

    var headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
    if (csrfToken && csrfHeader) headers[csrfHeader.content] = csrfToken.content;

    var body = { dtlIp: payload.dtlIp, dtlLaneNo: payload.dtlLaneNo, command: command };
    if (reauthPassword) body.reauthPassword = reauthPassword;
    var params = new URLSearchParams(body);

    resetBtn.disabled = true;
    resetBtn.textContent = '전송 중...';
    if (resultEl) { resultEl.className = 'rt-alert-result small mt-2 text-muted'; resultEl.textContent = '리셋 명령 전송 중...'; }

    fetch('/api/gate-control/reset', { method: 'POST', headers: headers, body: params.toString() })
      .then(function (res) { return res.json().then(function (b) { return { ok: res.ok, status: res.status, body: b }; }); })
      .then(function (r) {
        // securance.security.gate-control-reauth-required=true일 때 GateControlReauthInterceptor가
        // {reauthRequired:true}를 반환한다 — 비밀번호를 물어 한 번만 자동 재시도한다
        // (gate-tree.js sendResetWithReauth와 동일한 패턴). 이미 재시도한 요청(reauthPassword 전달됨)은
        // 다시 묻지 않고 실패를 그대로 보여준다 — 무한 프롬프트 반복을 막는다.
        if (r.body && r.body.reauthRequired && !reauthPassword) {
          resetBtn.disabled = false;
          resetBtn.textContent = '리셋 실행';
          var password = window.prompt('게이트 제어 재인증 — 비밀번호를 입력하세요.');
          if (password) sendResetCommand(payload, resetBtn, resultEl, password);
          return;
        }
        if (resultEl) {
          resultEl.className = 'rt-alert-result small mt-2 ' + (r.ok ? 'text-success' : 'text-danger');
          resultEl.textContent = '[' + r.status + '] ' + (r.body.message || '');
        }
        resetBtn.disabled = false;
        resetBtn.textContent = '리셋 실행';
      })
      .catch(function (err) {
        if (resultEl) { resultEl.className = 'rt-alert-result small mt-2 text-danger'; resultEl.textContent = '요청 실패: ' + err; }
        resetBtn.disabled = false;
        resetBtn.textContent = '리셋 실행';
      });
  }

  function escapeHtml(value) {
    if (value === null || value === undefined) return '';
    return String(value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  if (document.getElementById('rt-alert-modal')) {
    connect();
  }
})();
