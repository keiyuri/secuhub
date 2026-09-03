// gate-tree.js 회귀 테스트(2026-08-20 Opus 전체 리뷰 지적 — "가장 자주 깨지는 코드가 가장
// 검증이 없다": 최근 커밋 15건 중 8건이 이 파일의 버그 수정이었는데 테스트가 하나도 없었다).
//
// 프로덕션 파일(static/js/gate-tree.js) 자체는 <script> 태그로 로드되는 순정 IIFE라 모듈
// 시스템이 없다 — 파일 맨 아래의 테스트 전용 훅(`typeof module !== 'undefined'`로 가드)이
// CommonJS 환경(Vitest/Node)에서만 내부 함수를 module.exports로 노출한다. 그 가드 덕분에
// 브라우저 동작은 전혀 바뀌지 않는다.
// describe/test/expect/vi/beforeEach은 vitest.config.js의 `test.globals: true`가 전역으로
// 주입한다 — 이 파일이 CommonJS라 `require('vitest')`를 쓸 수 없다(Vitest가 명시적으로 금지).
const path = require('path');

const GATE_TREE_PATH = path.resolve(
  __dirname,
  '../../main/resources/static/js/gate-tree.js',
);

/** require 캐시를 비워 매 테스트마다 IIFE를 새로 실행한다(모듈 내부 상태 격리). */
function loadGateTree() {
  delete require.cache[require.resolve(GATE_TREE_PATH)];
  return require(GATE_TREE_PATH);
}

describe('escapeHtml', () => {
  test('& < > " 를 HTML 엔티티로 이스케이프한다', () => {
    const { escapeHtml } = loadGateTree();
    expect(escapeHtml('<script>alert("x")</script>&')).toBe(
      '&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt;&amp;',
    );
  });

  test('null/undefined는 빈 문자열로 처리한다', () => {
    const { escapeHtml } = loadGateTree();
    expect(escapeHtml(null)).toBe('');
    expect(escapeHtml(undefined)).toBe('');
  });
});

describe('maskIp', () => {
  test('대역(앞 옥텟)만 가리고 장비 식별용 뒤 옥텟은 남긴다', () => {
    const { maskIp } = loadGateTree();
    expect(maskIp('192.168.0.120')).toBe('*.168.0.120');
  });

  test('null/undefined/빈 문자열은 그대로 반환한다', () => {
    const { maskIp } = loadGateTree();
    expect(maskIp(null)).toBe(null);
    expect(maskIp(undefined)).toBe(undefined);
    expect(maskIp('')).toBe('');
  });
});

describe('renderDetail/renderGroup/renderLocation', () => {
  test('레인 이름이 없으면 마스킹된 dtlIp를 라벨로 쓰고, 게이트 이름은 이스케이프한다', () => {
    const { renderDetail } = loadGateTree();
    const grp = { gateTypeCode: 1 };
    const html = renderDetail(
      {},
      grp,
      { dtlId: 1, dtlIp: '192.168.0.1', dtlLaneNo: 1, dtlName: '<b>1번</b>', online: true },
    );
    expect(html).toContain('&lt;b&gt;1번&lt;/b&gt;');
    expect(html).toContain('data-online="true"');
    expect(html).toContain('data-gate-type="1"');
    expect(html).toContain('text-success'); // online=true → 초록 상태 아이콘
  });

  test('제어 요청에 쓰이는 data-dtl-ip 속성은 마스킹하지 않고 원본 IP를 유지한다', () => {
    const { renderDetail } = loadGateTree();
    const html = renderDetail(
      {},
      { gateTypeCode: 1 },
      { dtlId: 1, dtlIp: '192.168.0.1', dtlLaneNo: 1, online: true },
    );
    expect(html).toContain('data-dtl-ip="192.168.0.1"');
    // 화면 표시용 라벨/괄호 부분은 마스킹된 IP를 쓴다.
    expect(html).toContain('*.168.0.1');
  });

  test('오프라인 레인은 빨간 상태 아이콘을 쓴다', () => {
    const { renderDetail } = loadGateTree();
    const html = renderDetail({}, { gateTypeCode: 1 }, { dtlId: 2, dtlIp: '192.168.0.2', dtlLaneNo: 1, online: false });
    expect(html).toContain('text-danger');
  });

  test('그룹에 레인이 없으면 안내 문구를 표시한다', () => {
    const { renderGroup } = loadGateTree();
    const html = renderGroup({}, { grpId: 1, grpName: '1층', gateTypeCode: 1, details: [] });
    expect(html).toContain('등록된 레인이 없습니다');
  });

  test('빈 트리는 안내 문구만 렌더링한다', () => {
    const { render } = loadGateTree();
    const container = { innerHTML: '', querySelectorAll: () => [] };
    render(container, []);
    expect(container.innerHTML).toContain('등록된 게이트 위치가 없습니다');
  });
});

describe('sendBulkSequentialByIp', () => {
  // 회귀 방지(2026-08-19 사용자 요청) — 같은 dtlIp(물리 컨트롤러)로 가는 요청은 순차 전송하고,
  // 서로 다른 dtlIp끼리는 서로를 기다리지 않아야 한다(GateCtrlDataSet과 동일 이유: 단일 제어
  // 세션만 처리하는 장비에 동시에 여러 TCP 연결이 몰리는 것을 방지).
  test('같은 IP의 요청들은 순서대로 실행되고, 다른 IP는 서로 기다리지 않는다', async () => {
    const { sendBulkSequentialByIp } = loadGateTree();
    const order = [];
    const releasers = {};

    function sendFn(dtlIp, dtlLaneNo) {
      order.push(dtlIp + ':' + dtlLaneNo + ':start');
      return new Promise((resolve) => {
        releasers[dtlIp + ':' + dtlLaneNo] = () => {
          order.push(dtlIp + ':' + dtlLaneNo + ':end');
          resolve({ ok: true });
        };
      });
    }

    const targets = [
      { dtlIp: '10.0.0.1', dtlLaneNo: 1 },
      { dtlIp: '10.0.0.1', dtlLaneNo: 2 }, // 같은 IP — 레인1이 끝나야 시작
      { dtlIp: '10.0.0.2', dtlLaneNo: 1 }, // 다른 IP — 즉시 시작
    ];

    sendBulkSequentialByIp(targets, sendFn);
    await Promise.resolve(); // 마이크로태스크 큐를 한 번 흘려보낸다.

    // 같은 IP의 두 번째 레인은 아직 시작하지 않아야 한다.
    expect(order).toEqual(['10.0.0.1:1:start', '10.0.0.2:1:start']);

    releasers['10.0.0.1:1']();
    // sendFn의 resolve → .then(sendFn) → chains[ip]=.then() 체인을 여러 홉 거치므로, 매크로태스크
    // 경계(setTimeout)로 마이크로태스크 큐 전체를 흘려보낸다.
    await new Promise((r) => setTimeout(r, 0));

    expect(order).toContain('10.0.0.1:2:start');
    releasers['10.0.0.1:2']();
    releasers['10.0.0.2:1']();
  });
});

describe('sendBulkOperationCommand', () => {
  beforeEach(() => {
    global.window = global.window || {};
  });

  // 회귀 방지(2026-08-19/20, 가장 최근 커밋 c581b0e) — 이전에는 첫 대상 요청이 reject(네트워크
  // 오류 등)되면 함수 전체가 reject되어, 재인증이 필요 없는 나머지 대상은 통째로 전송조차 되지
  // 않았다. 상시 개방/폐쇄 같은 위험 명령이 부분 적용된 채로 멈추는 사고로 이어질 수 있었다.
  test('첫 대상 요청이 실패해도 나머지 대상에는 여전히 전송을 시도한다', async () => {
    const gateTree = loadGateTree();
    const attempted = [];
    gateTree.__setPostGateControl((path, dtlIp, dtlLaneNo) => {
      attempted.push(dtlIp);
      if (dtlIp === '10.0.0.1') return Promise.reject(new Error('network down'));
      return Promise.resolve({ ok: true, status: 200, body: { message: 'ok' } });
    });

    const targets = [
      { dtlIp: '10.0.0.1', dtlLaneNo: 1 },
      { dtlIp: '10.0.0.2', dtlLaneNo: 1 },
      { dtlIp: '10.0.0.3', dtlLaneNo: 1 },
    ];

    const promises = await gateTree.sendBulkOperationCommand(targets, 'OPEN');

    expect(promises).not.toBeNull();
    expect(promises).toHaveLength(3);
    // 첫 대상이 reject여도 나머지 두 대상은 실제로 전송을 시도해야 한다(핵심 회귀 지점).
    expect(attempted).toContain('10.0.0.2');
    expect(attempted).toContain('10.0.0.3');
    await expect(promises[0]).rejects.toThrow('network down');
    await expect(promises[1]).resolves.toEqual({ ok: true, status: 200, body: { message: 'ok' } });
  });

  test('재인증이 필요하면 프롬프트를 한 번만 띄우고, 취소하면 아무 대상에도 전송하지 않는다', async () => {
    const gateTree = loadGateTree();
    const attempted = [];
    gateTree.__setPostGateControl((path, dtlIp, dtlLaneNo, command, reauthPassword) => {
      attempted.push({ dtlIp, reauthPassword });
      if (!reauthPassword) return Promise.resolve({ ok: false, status: 401, body: { reauthRequired: true } });
      return Promise.resolve({ ok: true, status: 200, body: { message: 'ok' } });
    });
    global.window.prompt = vi.fn(() => null); // 사용자가 취소.

    const targets = [
      { dtlIp: '10.0.0.1', dtlLaneNo: 1 },
      { dtlIp: '10.0.0.2', dtlLaneNo: 1 },
    ];

    const result = await gateTree.sendBulkOperationCommand(targets, 'CLOSE');

    expect(result).toBeNull(); // 부분 실행 없이 전체 중단.
    expect(global.window.prompt).toHaveBeenCalledTimes(1);
    // 재인증 여부를 확인하는 첫 호출 1건만 나가고, 취소 후에는 추가 전송이 없어야 한다.
    expect(attempted).toHaveLength(1);
    expect(attempted[0].dtlIp).toBe('10.0.0.1');
  });

  test('재인증이 필요 없으면 첫 대상 응답을 재사용하고 나머지만 전송한다', async () => {
    const gateTree = loadGateTree();
    const calls = [];
    gateTree.__setPostGateControl((path, dtlIp) => {
      calls.push(dtlIp);
      return Promise.resolve({ ok: true, status: 200, body: { message: 'ok' } });
    });

    const targets = [
      { dtlIp: '10.0.0.1', dtlLaneNo: 1 },
      { dtlIp: '10.0.0.2', dtlLaneNo: 1 },
    ];

    const promises = await gateTree.sendBulkOperationCommand(targets, 'NORMAL');

    expect(calls).toHaveLength(2); // 첫 대상 1회 + 나머지 1회 = 정확히 대상 수만큼.
    expect(promises).toHaveLength(2);
  });
});

describe('sendCommandWithReauth/sendResetWithReauth', () => {
  // 회귀 방지(2026-08-21) — 두 함수가 정의되지 않은 채 호출부에서만 참조되고 있어, DTL 단일 대상
  // 게이트 제어(개방/폐쇄/복구/FREE/역방향 개방/리셋)를 클릭하면 ReferenceError가 동기적으로
  // 던져져 fetch 자체가 나가지 않았다(이력 저장·실행 상태 확인 불가의 원인).
  beforeEach(() => {
    global.window = global.window || {};
  });

  test('sendCommandWithReauth는 /api/gate-control/command로 요청을 보낸다', async () => {
    const gateTree = loadGateTree();
    const calls = [];
    gateTree.__setPostGateControl((path, dtlIp, dtlLaneNo, command, reauthPassword) => {
      calls.push({ path, dtlIp, dtlLaneNo, command, reauthPassword });
      return Promise.resolve({ ok: true, status: 200, body: { message: 'ok' } });
    });

    const result = await gateTree.sendCommandWithReauth('10.0.0.1', 1, 'OPEN');

    expect(calls).toEqual([{ path: '/api/gate-control/command', dtlIp: '10.0.0.1', dtlLaneNo: 1, command: 'OPEN', reauthPassword: undefined }]);
    expect(result).toEqual({ ok: true, status: 200, body: { message: 'ok' } });
  });

  test('sendResetWithReauth는 /api/gate-control/reset으로 요청을 보낸다', async () => {
    const gateTree = loadGateTree();
    const calls = [];
    gateTree.__setPostGateControl((path, dtlIp, dtlLaneNo, command, reauthPassword) => {
      calls.push({ path, dtlIp, dtlLaneNo, command, reauthPassword });
      return Promise.resolve({ ok: true, status: 200, body: { message: 'ok' } });
    });

    const result = await gateTree.sendResetWithReauth('10.0.0.1', 1, 'RESET_MOTOR');

    expect(calls).toEqual([{ path: '/api/gate-control/reset', dtlIp: '10.0.0.1', dtlLaneNo: 1, command: 'RESET_MOTOR', reauthPassword: undefined }]);
    expect(result).toEqual({ ok: true, status: 200, body: { message: 'ok' } });
  });

  test('재인증이 필요하면 비밀번호를 프롬프트로 받아 한 번만 자동 재시도한다', async () => {
    const gateTree = loadGateTree();
    const calls = [];
    gateTree.__setPostGateControl((path, dtlIp, dtlLaneNo, command, reauthPassword) => {
      calls.push(reauthPassword);
      if (!reauthPassword) return Promise.resolve({ ok: false, status: 401, body: { reauthRequired: true } });
      return Promise.resolve({ ok: true, status: 200, body: { message: 'ok' } });
    });
    global.window.prompt = vi.fn(() => 'secret');

    const result = await gateTree.sendCommandWithReauth('10.0.0.1', 1, 'CLOSE');

    expect(calls).toEqual([undefined, 'secret']);
    expect(result).toEqual({ ok: true, status: 200, body: { message: 'ok' } });
  });

  test('재인증 프롬프트를 취소하면 재시도하지 않고 실패로 처리한다', async () => {
    const gateTree = loadGateTree();
    const calls = [];
    gateTree.__setPostGateControl((path, dtlIp, dtlLaneNo, command, reauthPassword) => {
      calls.push(reauthPassword);
      return Promise.resolve({ ok: false, status: 401, body: { reauthRequired: true } });
    });
    global.window.prompt = vi.fn(() => null);

    const result = await gateTree.sendCommandWithReauth('10.0.0.1', 1, 'CLOSE');

    expect(calls).toEqual([undefined]); // 재시도 없음.
    expect(result.ok).toBe(false);
  });
});

describe('reportBulkCommandResult', () => {
  test('전부 성공하면 N/N대 성공만 표시한다', async () => {
    const { reportBulkCommandResult } = loadGateTree();
    global.document = {
      getElementById: () => null,
      body: { appendChild: vi.fn() },
      createElement: () => ({ style: {} }),
    };
    const el = { style: {}, className: '', textContent: '' };
    global.document.getElementById = () => el;

    await reportBulkCommandResult(
      [Promise.resolve({ ok: true, status: 200, body: {} }), Promise.resolve({ ok: true, status: 200, body: {} })],
      '그룹 "1층"',
      [{ dtlIp: '10.0.0.1', dtlLaneNo: 1 }, { dtlIp: '10.0.0.2', dtlLaneNo: 1 }],
    );

    expect(el.textContent).toContain('2/2대 성공');
    expect(el.className).toContain('gate-tree-toast-ok');
  });

  // 회귀 방지(2026-08-19 Codex 적대적 리뷰) — "N/M대 성공" 집계만으로는 어떤 게이트가 실패했는지
  // 알 수 없었다. 실패한 게이트의 IP/레인을 텍스트에 나열해야 한다(최대 5건, 초과분은 "외 N건").
  test('일부 실패 시 실패한 게이트의 IP/레인을 나열한다(5건 초과 시 축약)', async () => {
    const { reportBulkCommandResult } = loadGateTree();
    const el = { style: {}, className: '', textContent: '' };
    global.document = { getElementById: () => el };

    const targets = Array.from({ length: 7 }, (_, i) => ({ dtlIp: '10.0.0.' + i, dtlLaneNo: 1 }));
    const promises = targets.map((t, i) =>
      Promise.resolve(i === 0 ? { ok: true, status: 200, body: {} } : { ok: false, status: 500, body: {} }),
    );

    await reportBulkCommandResult(promises, '위치 "본관"', targets);

    expect(el.textContent).toContain('1/7대 성공');
    expect(el.textContent).toContain('외 1건'); // 6건 실패 중 5건만 나열하고 나머지 1건은 축약.
    expect(el.className).toContain('gate-tree-toast-error');
  });
});
