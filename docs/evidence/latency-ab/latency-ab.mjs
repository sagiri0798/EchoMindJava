import fs from 'node:fs'
import crypto from 'node:crypto'

const target = (process.env.TARGET || 'http://127.0.0.1:8095').replace(/\/+$/, '')
const queriesFile = process.env.QUERIES || 'docs/evidence/latency-ab/queries.json'
const resultFile = process.env.RESULT_FILE || 'docs/evidence/latency-ab/result.json'
const label = process.env.LABEL || 'ab'
const rounds = Math.max(1, Number(process.env.ROUNDS || 3))
const requestsPerRound = Math.max(1, Number(process.env.REQUESTS || 60))
const concurrency = Math.max(1, Number(process.env.CONCURRENCY || 5))
const warmup = Math.max(0, Number(process.env.WARMUP || 16))
const timeoutMs = Math.max(1000, Number(process.env.TIMEOUT_MS || 120000))
const seed = process.env.SEED === '1'
const datasetFile = process.env.DATASET || 'rag-eval/dataset-v1.json'
const seedFile = process.env.SEED_RESULT || ''

const querySet = JSON.parse(fs.readFileSync(queriesFile, 'utf8')).queries
if (!Array.isArray(querySet) || querySet.length === 0) throw new Error('queries.json must contain a non-empty queries array')

function percentile(values, p) {
  const sorted = [...values].sort((a, b) => a - b)
  const index = Math.max(0, Math.min(sorted.length - 1, Math.ceil(sorted.length * p / 100) - 1))
  return sorted[index] || 0
}

function hash(value) {
  return value ? crypto.createHash('sha256').update(value).digest('hex').slice(0, 16) : ''
}

function countBy(results, key) {
  return results.reduce((counts, result) => {
    const value = String(result[key])
    counts[value] = (counts[value] || 0) + 1
    return counts
  }, {})
}

function summarize(results) {
  const latencies = results.map(result => result.elapsed)
  const byQuery = {}
  for (const query of querySet) {
    const subset = results.filter(result => result.queryId === query.id)
    if (!subset.length) continue
    const values = subset.map(result => result.elapsed)
    byQuery[query.id] = {
      count: subset.length,
      p50: Math.round(percentile(values, 50)),
      p95: Math.round(percentile(values, 95)),
      p99: Math.round(percentile(values, 99)),
      answerHashes: [...new Set(subset.map(result => result.answerHash).filter(Boolean))],
      agentTypes: [...new Set(subset.map(result => result.agentType).filter(Boolean))],
      toolsUsed: [...new Set(subset.flatMap(result => result.toolsUsed))],
      status: countBy(subset, 'status')
    }
  }
  const requestIds = results.map(result => result.requestId).filter(Boolean)
  return {
    requests: results.length,
    latency_ms: {
      p50: Math.round(percentile(latencies, 50)),
      p95: Math.round(percentile(latencies, 95)),
      p99: Math.round(percentile(latencies, 99)),
      max: Math.round(Math.max(...latencies))
    },
    http_counts: countBy(results, 'http'),
    status_counts: countBy(results, 'status'),
    distinct_request_ids: new Set(requestIds).size,
    duplicate_request_ids: requestIds.length - new Set(requestIds).size,
    answer_hashes: [...new Set(results.map(result => result.answerHash).filter(Boolean))],
    agent_types: [...new Set(results.map(result => result.agentType).filter(Boolean))],
    tools_used: [...new Set(results.flatMap(result => result.toolsUsed))],
    errors: results.filter(result => result.error).slice(0, 5).map(result => result.error),
    by_query: byQuery
  }
}

async function call(query, phase, round, index) {
  const run = `${label}-${phase}-${round}-${index}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  const payload = {
    message: query.query,
    user_id: `ab-${run}`,
    conv_id: `ab-conv-${run}`,
    client_request_id: `ab-${run}`
  }
  const started = performance.now()
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await fetch(`${target}/chat`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(payload),
      signal: controller.signal
    })
    const text = await response.text()
    let data = null
    try { data = text ? JSON.parse(text) : null } catch {}
    const answer = data?.response || ''
    return {
      queryId: query.id,
      http: response.status,
      status: data?.status || 'invalid_json',
      requestId: data?.request_id || '',
      elapsed: performance.now() - started,
      answerHash: hash(answer),
      answerLength: answer.length,
      agentType: data?.agent_type || '',
      agentTypes: data?.agent_types || [],
      toolsUsed: Array.isArray(data?.tools_used) ? data.tools_used : [],
      knowledgeUsed: Boolean(data?.knowledge_used),
      error: response.ok ? '' : text.slice(0, 180)
    }
  } catch (error) {
    return {
      queryId: query.id,
      http: 0,
      status: 'client_error',
      requestId: '',
      elapsed: performance.now() - started,
      answerHash: '',
      answerLength: 0,
      agentType: '',
      agentTypes: [],
      toolsUsed: [],
      knowledgeUsed: false,
      error: String(error)
    }
  } finally {
    clearTimeout(timer)
  }
}

async function batch(phase, round, total) {
  const results = new Array(total)
  let next = 0
  async function worker() {
    while (true) {
      const index = next++
      if (index >= total) return
      results[index] = await call(querySet[index % querySet.length], phase, round, index)
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, total) }, worker))
  return results
}

async function seedCorpus() {
  const dataset = JSON.parse(fs.readFileSync(datasetFile, 'utf8'))
  const documents = dataset.documents.map(document => ({
    title: document.title,
    content: document.content,
    document_id: document.document_id
  }))
  const response = await fetch(`${target}/knowledge/add`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ documents }),
    signal: AbortSignal.timeout(timeoutMs)
  })
  const text = await response.text()
  const result = { http: response.status, body: text.slice(0, 500), documents: documents.length }
  if (seedFile) fs.writeFileSync(seedFile, JSON.stringify(result, null, 2))
  console.log('SEED ' + JSON.stringify(result))
  if (!response.ok) throw new Error(`seed failed: ${response.status} ${text.slice(0, 200)}`)
}

const output = {
  label,
  target,
  queriesFile,
  rounds,
  requestsPerRound,
  concurrency,
  warmup,
  timeoutMs,
  seeded: seed,
  start: new Date().toISOString(),
  warmup_result: null,
  rounds: [],
  overall: null
}

if (seed) await seedCorpus()
if (warmup > 0) {
  const warmupResults = await batch('warmup', 0, warmup)
  output.warmup_result = summarize(warmupResults)
  console.log('WARMUP ' + JSON.stringify({ requests: warmupResults.length, latency: output.warmup_result.latency_ms, status: output.warmup_result.status_counts }))
}

const all = []
for (let round = 1; round <= rounds; round++) {
  const results = await batch('measure', round, requestsPerRound)
  const summary = summarize(results)
  output.rounds.push({ round, ...summary })
  all.push(...results)
  console.log(`ROUND ${round} ` + JSON.stringify({ requests: results.length, latency: summary.latency_ms, status: summary.status_counts, duplicates: summary.duplicate_request_ids }))
}

output.overall = summarize(all)
output.end = new Date().toISOString()
fs.writeFileSync(resultFile, JSON.stringify(output, null, 2))
console.log('RESULT ' + JSON.stringify({ label, target, rounds, requestsPerRound, overall: output.overall.latency_ms, status: output.overall.status_counts }))
