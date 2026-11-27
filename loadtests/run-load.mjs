const config = {
  target: process.env.TARGET || 'http://127.0.0.1:8092',
  requests: Math.max(1, Number(process.env.REQUESTS || 100)),
  concurrency: Math.max(1, Number(process.env.CONCURRENCY || 10)),
  timeoutMs: Math.max(1000, Number(process.env.TIMEOUT_MS || 60000)),
  duplicate: process.env.DUPLICATE === '1'
}

const messages = [
  '你好，请简单确认收到。',
  '登录一直报401，请给出排查步骤。',
  '为什么这个月多扣了50元？',
  '登录报401，而且刚才重复扣款了。',
  '请查知识库里的退款政策。'
]

function percentile(values, p) {
  const sorted = [...values].sort((a, b) => a - b)
  const index = Math.max(0, Math.min(sorted.length - 1, Math.ceil(sorted.length * p / 100) - 1))
  return sorted[index] || 0
}

async function one(index) {
  const run = `${Date.now()}-${index}-${Math.random().toString(36).slice(2, 8)}`
  const payload = {
    message: config.duplicate ? (process.env.MESSAGE || messages[0]) : messages[index % messages.length],
    user_id: config.duplicate ? (process.env.USER_ID || 'load-replay-user') : `load-${run}`,
    conv_id: config.duplicate ? (process.env.CONV_ID || 'load-replay-conv') : `conv-${run}`,
    client_request_id: config.duplicate ? (process.env.CLIENT_REQUEST_ID || 'load-replay-msg') : `msg-${run}`
  }
  const started = performance.now()
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), config.timeoutMs)
  try {
    const response = await fetch(`${config.target}/chat`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(payload),
      signal: controller.signal
    })
    const text = await response.text()
    let data = null
    try { data = text ? JSON.parse(text) : null } catch {}
    return {
      http: response.status,
      status: data?.status || 'invalid_json',
      requestId: data?.request_id || '',
      elapsed: performance.now() - started,
      error: response.ok ? '' : text.slice(0, 180)
    }
  } catch (error) {
    return { http: 0, status: 'client_error', requestId: '', elapsed: performance.now() - started, error: String(error) }
  } finally {
    clearTimeout(timeout)
  }
}

const results = new Array(config.requests)
let next = 0
const started = performance.now()

async function worker() {
  while (true) {
    const index = next++
    if (index >= config.requests) return
    results[index] = await one(index)
  }
}

await Promise.all(Array.from({ length: Math.min(config.concurrency, config.requests) }, worker))
const durationMs = performance.now() - started
const latencies = results.map(result => result.elapsed)
const countBy = key => results.reduce((counts, result) => {
  const value = String(result[key])
  counts[value] = (counts[value] || 0) + 1
  return counts
}, {})
const requestIds = new Set(results.map(result => result.requestId).filter(Boolean))

console.log(JSON.stringify({
  target: config.target,
  requests: results.length,
  concurrency: config.concurrency,
  duplicate: config.duplicate,
  duration_ms: Math.round(durationMs),
  throughput_rps: Number((results.length / (durationMs / 1000)).toFixed(2)),
  http_counts: countBy('http'),
  status_counts: countBy('status'),
  distinct_request_ids: requestIds.size,
  latency_ms: {
    p50: Math.round(percentile(latencies, 50)),
    p95: Math.round(percentile(latencies, 95)),
    p99: Math.round(percentile(latencies, 99)),
    max: Math.round(Math.max(...latencies))
  },
  sample_errors: results.filter(result => result.error).slice(0, 5).map(result => result.error)
}, null, 2))
