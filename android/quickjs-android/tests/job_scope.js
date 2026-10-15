// 在真实 vendored QuickJS/JNI 上运行，不以 Node 的 Promise 行为模拟原生 await。
const equal = (actual, expected, message = '') => {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${message}: ${JSON.stringify(actual)} !== ${JSON.stringify(expected)}`)
  }
}
const begin = __lxBeginRequest
const end = __lxEndRequest
const tick = time => { clock = time; __lxTick(); drain() }
const urls = () => calls.map(call => call.url)
const assertThrows = fn => {
  let threw = false
  try { fn() } catch (_) { threw = true }
  equal(threw, true, 'expected error')
}

const cases = {
  timers() {
    begin('1')
    setTimeout(() => setTimeout(() => lx.request('old/nested'), 20), 0)
    setTimeout(() => lx.request('old/timeout'), 50)
    setInterval(() => lx.request('old/interval'), 50)
    tick(0)
    end('1')
    begin('2')
    tick(100)
    equal(calls, [])
    end('2')
  },
  initializationTimers() {
    setInterval(() => {
      lx.request('init/interval')
      Promise.resolve().then(() => setTimeout(() => lx.request('init/nested'), 20))
    }, 50)
    begin('1'); tick(50); end('1')
    begin('2'); tick(70); tick(100); end('2')
    equal(urls(), ['init/interval', 'init/nested', 'init/interval'])
    equal(calls.every(call => call.scope === ''), true)
  },
  pendingThen() {
    let resolve
    const shared = new Promise(done => { resolve = done })
    begin('1')
    shared.then(() => { lx.request('old/then'); setTimeout(() => lx.request('old/timer'), 0) })
    end('1'); begin('2'); resolve(); drain(); tick(100)
    equal(calls, [])
    end('2')
  },
  queuedJobs() {
    let modified = 0
    begin('1')
    Promise.resolve().then(() => modified++)
    ;(async () => { await 1; modified++ })()
    Promise.resolve({then(resolve) { modified++; resolve() }})
    end('1'); begin('2'); gc(); drain()
    equal(modified, 0)
    equal(__lxNative.scope.current(), '2')
    end('2')
  },
  pendingAwait() {
    let resolve, modified = 0
    const shared = new Promise(done => { resolve = done })
    begin('1')
    ;(async () => { await shared; modified++; lx.request('old/await') })()
    end('1'); begin('2'); resolve(); gc(); drain()
    equal(modified, 0); equal(calls, [])
    end('2')
  },
  pendingCatchFinally() {
    let reject, modified = 0
    const shared = new Promise((_, fail) => { reject = fail })
    begin('1')
    ;(async () => {
      try { await shared } catch (_) { modified++ } finally { modified++ }
    })()
    shared.catch(() => modified++).finally(() => modified++)
    end('1'); begin('2'); reject(new Error('fixture')); drain()
    equal(modified, 0)
    end('2')
  },
  initializationAwait() {
    let resolve, nested = 0
    const shared = new Promise(done => { resolve = done })
    ;(async () => {
      await shared
      await { then(done) { done() } }
      lx.request('init/await')
      setTimeout(() => nested++, 20)
    })()
    begin('1'); resolve(); drain(); end('1')
    begin('2'); tick(20)
    equal(calls.map(call => call.scope), [''])
    equal(nested, 1)
    end('2')
  },
  liveRequest() {
    begin('1')
    __lxNative.http(JSON.stringify({url:'sdk/direct'}))
    setTimeout(() => lx.request('live/timer'), 0)
    Promise.resolve().then(() => lx.request('live/then'))
    ;(async () => { await 1; await {then(done) { done() }}; lx.request('live/await') })()
    tick(0)
    equal(calls.length, 4)
    equal(calls.every(call => call.scope === '1'), true)
    end('1')
  },
  staleCleanup() {
    begin('1'); end('1'); begin('2'); end('1')
    const id = setInterval(() => { clearInterval(id); lx.request('live/once') }, 0)
    const removed = setTimeout(() => lx.request('removed'), 0)
    clearTimeout(removed)
    setTimeout(() => lx.request('live/other'), 16)
    tick(16); tick(100)
    equal(urls(), ['live/once', 'live/other'])
    equal(__lxNative.scope.current(), '2')
    end('2')
  },
  nestedAwait() {
    let resolve, modified = 0
    const shared = new Promise(done => { resolve = done })
    begin('1')
    ;(async () => { await (async () => { await shared; modified++; return 1 })(); modified++ })()
    end('1'); begin('2'); resolve(); drain()
    equal(modified, 0)
    end('2')
  },
  thenableAwait() {
    let resolve, modified = 0
    begin('1')
    ;(async () => { await { then(done) { resolve = done } }; modified++ })()
    drain(); end('1'); begin('2'); resolve(); drain()
    equal(modified, 0)
    end('2')
  },
  asyncGenerator() {
    let resolve, modified = 0
    const shared = new Promise(done => { resolve = done })
    async function* values() { await shared; modified++; yield 1 }
    begin('1'); values().next(); drain(); end('1')
    begin('2'); resolve(); drain()
    equal(modified, 0)
    end('2')
  },
  identities() {
    const scope = __lxNative.scope
    for (const id of ['', '0', '-1', 'x', '18446744073709551616']) assertThrows(() => begin(id))
    begin('9007199254740993')
    assertThrows(() => begin('9007199254740994'))
    assertThrows(() => scope.enter('7'))
    equal(scope.current(), '9007199254740993')
    end('9007199254740993')
    begin('9007199254740993'); end('9007199254740993')
    begin('9007199254740994'); equal(scope.current(), '9007199254740994'); end('9007199254740994')
  },
  restoreAfterException() {
    // 故障发生在初始化 timer 内；即使 throw，也不能把当前请求变成初始化作用域。
    setTimeout(() => { throw new Error('timer fixture') }, 0)
    begin('1'); tick(0)
    equal(__lxNative.scope.current(), '1')
    Promise.resolve().then(() => { throw new Error('job fixture') }).catch(() => lx.request('live/recovery'))
    drain(); equal(calls[0].scope, '1'); end('1')
  },
  manyReusesAndGc() {
    let resolve, modified = 0
    const shared = new Promise(done => { resolve = done })
    for (let id = 1; id <= 100; id++) {
      begin(String(id))
      ;(async () => { await shared; modified++ })()
      end(String(id))
      gc()
    }
    begin('101'); resolve(); drain(); gc()
    equal(modified, 0)
    end('101')
  },
  reversedTokenOrder() {
    let modified = 0
    begin('2'); Promise.resolve().then(() => modified++); end('2')
    begin('2'); drain(); equal(modified, 0, 'host ID reuse must not revive retired generation'); end('2')
    begin('1')
    Promise.resolve().then(() => lx.request('live/reordered'))
    drain(); equal(calls[0].scope, '1'); end('1')
  },
  initializationAdoption() {
    for (const kind of ['promise', 'thenable']) {
      let settle, initValue, nextValue
      const shared = new Promise(resolve => { settle = resolve })
      ;(async () => { initValue = await shared })()
      const a = kind === 'promise' ? '1' : '3', b = kind === 'promise' ? '2' : '4'
      begin(a)
      settle(kind === 'promise' ? Promise.resolve('ready') : {then(resolve) { resolve('ready') }})
      end(a); begin(b)
      ;(async () => { nextValue = await shared })()
      drain()
      equal(initValue, 'ready', 'initialization adoption must survive resolver cancellation')
      equal(nextValue, 'ready', 'next request must not hang on shared adoption')
      end(b)
    }
  },
  retiredAdoption() {
    let settle, modified = 0
    begin('1')
    new Promise(resolve => { settle = resolve })
    end('1'); begin('2')
    settle({then(resolve) { modified++; resolve() }})
    drain(); equal(modified, 0, 'request-owned adoption must not borrow next owner')
    end('2')
  },
  closeWithPendingJobs() {
    begin('1')
    Promise.resolve().then(() => lx.request('must-not-run-at-close'))
    ;(async () => { await 1; lx.request('must-not-run-at-close') })()
    // Native harness 直接销毁 context/runtime，覆盖未排空队列引用释放。
  },
}
cases[testCase]()
