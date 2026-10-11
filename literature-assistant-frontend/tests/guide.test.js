import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createGuideModel, notesMarkdown } from '../src/utils/guide.js'

test('目录忽略代码中的伪标题，重复标题仍有唯一锚点', () => {
  const model = createGuideModel('# 报告\n\n## 方法\n\n正文\n\n```text\n## 不是目录\n```\n\n## 方法\n')
  assert.deepEqual(model.headings.map(heading => heading.text), ['报告', '方法', '方法'])
  assert.equal(new Set(model.headings.map(heading => heading.id)).size, 3)
  assert.match(model.html, /id="guide-heading-2"/)
})

test('来源链接保留，原始HTML不执行，外部图片不加载', () => {
  const { html } = createGuideModel('参考[S2](#source-S2)\n\n<script>alert(1)</script>\n\n![外部图](https://example.com/pixel)')
  assert.match(html, /href="#source-S2"/)
  assert.doesNotMatch(html, /<script>/)
  assert.match(html, /&lt;script&gt;/)
  assert.doesNotMatch(html, /<img/)
})

test('历史报告与表格仍正常解析，不假造六部分内容', () => {
  const { html, headings } = createGuideModel('## 旧阅读指南\n\n|问题|证据|\n|---|---|\n|延期|项目记录|')
  assert.equal(headings.length, 1)
  assert.match(html, /<table>/)
  assert.match(html, /项目记录/)
})

test('事实、判断、建议保留文字标识并分别呈现', () => {
  const { html } = createGuideModel('【原文陈述】作者提出方案。【分析判断】未见效果证据。【写作建议】准备评价资料。')
  for (const type of ['fact', 'analysis', 'advice']) assert.ok(html.includes(`claim-${type}`))
})

test('导出笔记含文献来源、位置、摘录和个人思考', () => {
  const exported = notesMarkdown('案例研究.pdf', [{ title: '原因分析', category: 'METHOD',
    sourceLocation: '第3章 / S2', excerpt: '访谈材料', reflection: '需要了解编码过程' }])
  for (const part of ['案例研究.pdf', '研究方法', '第3章 / S2', '访谈材料', '需要了解编码过程']) assert.ok(exported.includes(part))
})
