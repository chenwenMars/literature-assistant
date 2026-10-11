import { test } from 'node:test'
import assert from 'node:assert/strict'
import { decodeReasoning } from '../src/utils/guideStream.js'

test('思考片段保留换行、空格、引号和正文协议占位符的原始文字', () => {
  const text = '先阅读章节\n\n  再核对"证据"\t<empty-line> <empty-space>\\路径\r\n'
  assert.equal(decodeReasoning(JSON.stringify({ content: text })), text)
})

test('无效JSON与非字符串思考内容明确失败', () => {
  assert.throws(() => decodeReasoning('broken json'), SyntaxError)
  for (const data of ['null', '{}', '{"content":123}', '{"content":null}', '[]']) {
    assert.throws(() => decodeReasoning(data), /AI思考事件格式错误/)
  }
})
