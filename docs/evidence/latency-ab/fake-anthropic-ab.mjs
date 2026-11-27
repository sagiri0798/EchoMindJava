import http from 'node:http'

const port = Number(process.env.FAKE_PORT || 8099)
const delayMs = Math.max(0, Number(process.env.FAKE_DELAY_MS || 200))
const rewrites = Math.max(1, Number(process.env.REWRITES || 3))
const stats = {
  requests: 0,
  byKind: {},
  startedAt: new Date().toISOString()
}

function textContent(value) {
  if (typeof value === 'string') return value
  if (Array.isArray(value)) return value.map(textContent).join('\n')
  return value == null ? '' : JSON.stringify(value)
}

function lastUserText(body) {
  const messages = Array.isArray(body.messages) ? body.messages : []
  for (let i = messages.length - 1; i >= 0; i--) {
    const message = messages[i]
    if (message && message.role === 'user') {
      const text = textContent(message.content)
      if (text.trim()) return text.trim()
    }
  }
  return ''
}

function messagesText(body) {
  const messages = Array.isArray(body.messages) ? body.messages : []
  return messages.map(message => textContent(message && message.content)).join('\n')
}

function answerFor(body) {
  const messages = messagesText(body)
  const conversation = [textContent(body.system), messages].join('\n')
  const prompt = [conversation, textContent(body.tools)].join('\n')

  if (prompt.includes('你是客服意图分析专家')) {
    let intent = 'greeting'
    if (prompt.includes('登录')) intent = 'technical_login'
    else if (prompt.includes('退款')) intent = 'refund'
    else if (prompt.includes('扣款')) intent = 'payment_issue'
    return {
      kind: 'intent',
      text: JSON.stringify({ intent, confidence: 0.98, reasoning: 'latency-ab fixture' })
    }
  }
  if (prompt.includes('你是客服回答质量校验器')) {
    return {
      kind: 'verifier',
      text: JSON.stringify({ pass: true, grounded: true, need_escalation: false, reason: 'latency-ab fixture' })
    }
  }
  if (prompt.includes('从以下客服对话中提炼用户画像')) {
    return {
      kind: 'profile',
      text: JSON.stringify({ preferences: ['prefers concise answers'], entities: { 产品: ['EchoMind'], 问题类型: ['latency-ab'] } })
    }
  }
  if (prompt.includes('用 2-3 句话合并总结')) {
    return { kind: 'summary', text: '延迟 A/B 假模型摘要：保留当前任务与约束。' }
  }
  if (prompt.includes('将以下用户查询改写')) {
    const match = messages.match(/原始查询:\s*"([^"]*)"/)
    const query = match && match[1].trim() ? match[1].trim() : '退款政策'
    const variants = [query]
    if (rewrites >= 2) variants.push(query + ' 政策')
    if (rewrites >= 3) variants.push(query + ' 流程')
    while (variants.length < rewrites) variants.push(query + ' 说明' + variants.length)
    return { kind: 'rewrite', text: JSON.stringify(variants.slice(0, rewrites)) }
  }
  if (prompt.includes('检索结果按相关性排序') || prompt.includes('相关性排序')) {
    return { kind: 'rerank', text: '[0,1,2,3,4]' }
  }
  if (prompt.includes('客服回复协调员')) {
    return { kind: 'composer', text: '延迟 A/B 假模型汇总回复。' }
  }

  const hasToolResult = prompt.includes('"tool_result"') || prompt.includes('tool_result')
  if (Array.isArray(body.tools) && body.tools.length > 0 && !hasToolResult) {
    const query = lastUserText(body).slice(0, 80) || '退款政策'
    return {
      kind: 'tool_use',
      content: [{
        type: 'tool_use',
        id: `toolu_${stats.requests}`,
        name: 'search_knowledge_base',
        input: { query, top_k: 3 }
      }],
      stopReason: 'tool_use'
    }
  }
  return { kind: 'agent', text: '延迟 A/B 假模型回复：已收到请求。' }
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/stats') {
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify(stats))
    return
  }
  if (req.method === 'GET' && req.url === '/health') {
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ status: 'ok' }))
    return
  }
  if (req.method !== 'POST' || !req.url.endsWith('/messages')) {
    res.writeHead(404, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ error: 'not_found' }))
    return
  }

  let raw = ''
  for await (const chunk of req) raw += chunk
  let body = {}
  try {
    body = raw ? JSON.parse(raw) : {}
  } catch {
    res.writeHead(400, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ error: 'invalid_json' }))
    return
  }

  stats.requests += 1
  const answer = answerFor(body)
  stats.byKind[answer.kind] = (stats.byKind[answer.kind] || 0) + 1
  if (delayMs) await new Promise(resolve => setTimeout(resolve, delayMs))

  const content = answer.content || [{ type: 'text', text: answer.text }]
  res.writeHead(200, { 'content-type': 'application/json' })
  res.end(JSON.stringify({
    id: `msg_${stats.requests}`,
    type: 'message',
    role: 'assistant',
    model: body.model || 'fake-model',
    content,
    stop_reason: answer.stopReason || 'end_turn',
    stop_sequence: null,
    usage: { input_tokens: 1, output_tokens: 1 }
  }))
})

server.listen(port, '127.0.0.1', () => {
  console.log(`fake-anthropic-ab READY http://127.0.0.1:${port} delay=${delayMs}ms rewrites=${rewrites}`)
})
