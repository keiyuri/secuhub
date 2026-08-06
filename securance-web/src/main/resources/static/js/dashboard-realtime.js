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

  // #1 SR_F_GateControl(장애 알림 팝업) / #17 SR_F_Warning(화재 경고 팝업) — 실제 리셋 실행은
  // GateControlService 구현 전까지 제공하지 않는다(계획서 3절, /gates/reset과 동일한 방침).
  function showAlertModal(payload) {
    var isFire = payload.alertType === 'FIRE';
    var modalEl = document.getElementById('rt-alert-modal');
    if (!modalEl) return;
    modalEl.querySelector('.modal-title').textContent = isFire ? '화재 경고' : '게이트 장애 알림';
    modalEl.querySelector('.modal-header').className = 'modal-header ' + (isFire ? 'bg-danger text-white' : 'bg-warning');
    modalEl.querySelector('.rt-alert-ip').textContent = payload.dtlIp || '-';
    modalEl.querySelector('.rt-alert-desc').textContent = payload.description || '-';
    modalEl.querySelector('.rt-alert-date').textContent = payload.analDate || '-';

    if (window.bootstrap && window.bootstrap.Modal) {
      window.bootstrap.Modal.getOrCreateInstance(modalEl).show();
    }
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
