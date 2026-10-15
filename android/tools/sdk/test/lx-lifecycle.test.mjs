import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'

const shim = await readFile(new URL('../../../app/src/main/assets/lx-shim.js', import.meta.url), 'utf8')
const microtasks = () => new Promise(resolve => setImmediate(resolve))

const fixture = () => {
  let now = 0
  const calls = []
  const context = vm.createContext({
    Date: class extends Date { static now() { return now } },
    __lxNative: {
      http(payload, scope) {
        calls.push({ ...JSON.parse(payload), scope })
        return JSON.stringify({ statusCode: 200, raw: '' })
      },
      log() {},
    },
  })
  vm.runInContext(shim, context)
  return {
    context, calls,
    run: code => vm.runInContext(code, context),
    begin: id => context.__lxBeginRequest(id),
    end: id => context.__lxEndRequest(id),
    tick(time) { now = time; context.__lxTick() },
  }
}

for (const outcome of ['success', 'failure', 'timeout', 'cancel']) {
  test(`${outcome} retires request timeouts, intervals and nested timers before reuse`, async () => {
    const f = fixture()
    f.run(`
      lx.on('request', () => {
        setTimeout(() => setTimeout(() => lx.request('https://old.test/nested'), 20), 0);
        setTimeout(() => lx.request('https://old.test/timeout'), 50);
        setInterval(() => lx.request('https://old.test/interval'), 50);
        ${outcome === 'success' ? "return 'done';" : outcome === 'failure' ? "throw new Error('fixture');" : 'return new Promise(() => {});'}
      });
    `)
    f.begin('old')
    f.context.__lxInvoke('{}')
    f.tick(0)
    await microtasks()
    if (outcome === 'success' || outcome === 'failure') {
      assert.equal(JSON.parse(f.context.__lxTakeResult()).ok, outcome === 'success')
    }
    f.end('old')
    f.begin('next')
    f.tick(100)
    await microtasks()
    assert.deepEqual(f.calls, [], 'retired timers must not issue HTTP under the next request')
    f.end('next')
  })
}

test('initialization intervals and their nested timers retain initialization ownership', async () => {
  const f = fixture()
  f.run(`
    setInterval(() => {
      lx.request('https://init.test/interval');
      Promise.resolve().then(() => setTimeout(() => lx.request('https://init.test/nested'), 20));
    }, 50);
  `)
  f.begin('first')
  f.tick(50)
  await microtasks()
  f.end('first')
  f.begin('second')
  f.tick(70)
  f.tick(100)
  await microtasks()
  f.end('second')
  assert.deepEqual(f.calls.map(({ url }) => url), [
    'https://init.test/interval', 'https://init.test/nested', 'https://init.test/interval',
  ])
  assert.ok(f.calls.every(({ scope }) => scope === ''), 'initialization HTTP must not adopt a request scope')
})

test('queued Promise callbacks from a retired request cannot start HTTP or timers', async () => {
  const f = fixture()
  f.begin('old')
  f.run(`
    globalThis.finishOld = null;
    new Promise(resolve => { finishOld = resolve; }).then(() => {
      lx.request('https://old.test/promise');
      setTimeout(() => lx.request('https://old.test/promise-timer'), 0);
    });
  `)
  f.end('old')
  f.begin('next')
  f.run('finishOld()')
  await microtasks()
  f.tick(100)
  assert.deepEqual(f.calls, [])
  f.end('next')
})

test('native SDK HTTP and live request callbacks carry the same scope identifier', async () => {
  const f = fixture()
  f.begin('sdk-one')
  f.run(`
    __lxNative.http(JSON.stringify({url:'https://sdk.test/direct'}));
    setTimeout(() => lx.request('https://sdk.test/timer'), 0);
    Promise.resolve().then(() => lx.request('https://sdk.test/promise'));
  `)
  f.tick(0)
  await microtasks()
  f.end('sdk-one')
  assert.equal(f.calls.length, 3)
  assert.ok(f.calls.every(({ scope }) => scope === 'sdk-one'))
})

test('stale cleanup cannot remove the next request and timer cancellation is stable', () => {
  const f = fixture()
  f.begin('old')
  f.end('old')
  f.begin('next')
  f.run(`
    const id = setInterval(() => { clearInterval(id); lx.request('https://next.test/once'); }, 0);
    const removed = setTimeout(() => lx.request('https://next.test/removed'), 0);
    clearTimeout(removed);
    setTimeout(() => lx.request('https://next.test/other'), 16);
  `)
  f.end('old')
  f.tick(16)
  f.tick(100)
  assert.deepEqual(f.calls.map(({ url }) => url), ['https://next.test/once', 'https://next.test/other'])
  f.end('next')
})
