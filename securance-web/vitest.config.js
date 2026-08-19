const { defineConfig } = require('vitest/config');

module.exports = defineConfig({
  test: {
    environment: 'jsdom',
    include: ['src/test/js/**/*.test.js'],
    // 테스트 파일이 CommonJS(require)인 상태에서 `require('vitest')`는 Vitest가 명시적으로
    // 금지한다("Vitest cannot be imported in a CommonJS module using require()") — describe/test/
    // expect/vi 등을 전역으로 주입해 별도 import 없이 쓰도록 한다.
    globals: true,
  },
});
