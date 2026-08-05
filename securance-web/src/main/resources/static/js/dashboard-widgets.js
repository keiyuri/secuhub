// 계획서 5.2절: adminlte-react useSortable 훅과 동일한 패턴 — 카드 헤더를 손잡이로 삼아
// .connectedSortable 컬럼 간 위젯을 드래그로 재배치한다. 순서 영속화(/api/dashboard/widgets/reorder)는
// 1차 스캐폴드 이후 후속 작업이다(계획서 5.2절).
document.addEventListener("DOMContentLoaded", function () {
  document.querySelectorAll(".connectedSortable").forEach(function (column) {
    // eslint-disable-next-line no-undef
    Sortable.create(column, {
      group: "dashboard-widgets",
      handle: ".card-header",
      animation: 200,
    });
  });
});
