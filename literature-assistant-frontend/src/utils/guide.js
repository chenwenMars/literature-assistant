import { Marked, Renderer } from 'marked'

export const escapeHtml = (text) => text.replace(/&/g, '&amp;').replace(/</g, '&lt;')
  .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;')

export function createGuideModel(markdown) {
  const renderer = new Renderer()
  const headings = []
  renderer.heading = (text, level) => {
    const id = `guide-heading-${headings.length}`
    headings.push({ id, level, text: text.replace(/<[^>]*>/g, '') })
    return `<h${level} id="${id}">${text}</h${level}>\n`
  }
  // 文献/模型中的原始HTML只作为文字展示；DOMPurify在浏览器边缘再校验生成HTML。
  renderer.html = (html) => escapeHtml(html)
  renderer.image = (_href, _title, text) => `<span>${escapeHtml(text)}（图片未加载）</span>`
  renderer.text = (text) => text.replace(/【(原文陈述|分析判断|写作建议)】/g,
    (_match, label) => `<span class="claim-badge claim-${{ 原文陈述: 'fact', 分析判断: 'analysis', 写作建议: 'advice' }[label]}">${label}</span>`)
  const parser = new Marked({ renderer, gfm: true, breaks: false })
  const html = parser.parse(markdown)
  return { html, headings }
}

export function sectionText(container, headingId) {
  const heading = container.querySelector(`#${headingId}`)
  if (!heading) throw new Error('报告章节不存在')
  const level = Number(heading.tagName.slice(1))
  const parts = [heading.textContent]
  let node = heading.nextElementSibling
  while (node && !(/^H[1-6]$/.test(node.tagName) && Number(node.tagName.slice(1)) <= level)) {
    if (node.classList.contains('reading-diagram')) {
      parts.push(`论证关系图：\n${node.dataset.source}`)
    } else {
      parts.push(node.textContent)
    }
    node = node.nextElementSibling
  }
  return parts.join('\n\n')
}

export const noteCategories = [
  { value: 'WRITING', label: '写作方式' },
  { value: 'METHOD', label: '研究方法' },
  { value: 'IDEA', label: '研究启发' },
  { value: 'MATERIAL', label: '材料准备' }
]

export function notesMarkdown(name, notes) {
  return `# ${name} · 我的借鉴笔记\n\n` + notes.map(note =>
    `## ${note.title}\n\n类型：${noteCategories.find(item => item.value === note.category).label}\n\n`
    + `来源文献：${name}\n\n来源位置：${note.sourceLocation}\n\n`
    + `### 摘录或概述\n\n${note.excerpt}\n\n### 我的思考与下一步\n\n${note.reflection || '尚未填写'}\n`
  ).join('\n')
}

export function downloadMarkdown(name, content) {
  const url = URL.createObjectURL(new Blob([content], { type: 'text/markdown;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = name.replace(/[\\/:*?"<>|]/g, '_') + '.md'
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
