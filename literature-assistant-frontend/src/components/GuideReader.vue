<template>
  <div class="guide-reader" :class="{ 'reader-fullscreen': fullscreen }">
    <div class="reader-toolbar">
      <span>论文精读 · 研究与写作学习</span>
      <div>
        <el-button v-if="allowNotes" size="small" @mousedown.prevent @click="recordSelection">选中文字记笔记</el-button>
        <el-button size="small" @click="fullscreen = !fullscreen">{{ fullscreen ? '退出全屏' : '全屏阅读' }}</el-button>
      </div>
    </div>
    <el-alert v-if="renderError" :title="renderError" type="error" :closable="false" />
    <div class="reader-layout">
      <nav class="reader-toc" aria-label="报告目录">
        <strong>阅读目录</strong>
        <div v-for="heading in toc" :key="heading.id" class="toc-row" :class="{ nested: heading.level > 2 }">
          <button :class="{ active: activeId === heading.id }" @click="goTo(heading.id)">{{ heading.text }}</button>
          <button v-if="allowNotes && heading.level === 2" class="note-button" :aria-label="`记录${heading.text}`" @click="recordSection(heading)">＋</button>
        </div>
        <p v-if="!toc.length">报告生成后显示章节目录</p>
      </nav>
      <article ref="contentRef" class="guide-markdown" @click="handleLink" v-html="safeHtml"></article>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import DOMPurify from 'dompurify'
import mermaid from 'mermaid'
import { ElMessage } from 'element-plus'
import { createGuideModel, sectionText } from '@/utils/guide'

const props = defineProps({ markdown: { type: String, default: '' }, streaming: Boolean, allowNotes: Boolean })
const emit = defineEmits(['source', 'note'])
const contentRef = ref(null)
const fullscreen = ref(false)
const activeId = ref('')
const renderError = ref('')
let renderRevision = 0
// 每个实例/每次渲染使用不同SVG编号，避免预览与详情图表相互覆盖。
const instanceId = crypto.randomUUID().replace(/-/g, '')
const model = computed(() => createGuideModel(props.markdown))
const toc = computed(() => model.value.headings.filter(heading => heading.level === 2 || heading.level === 3))
const safeHtml = computed(() => DOMPurify.sanitize(model.value.html, { USE_PROFILES: { html: true } }))

function goTo(id) {
  activeId.value = id
  contentRef.value.querySelector(`#${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function handleLink(event) {
  const link = event.target.closest('a')
  if (!link) return
  const href = link.getAttribute('href')
  if (href?.startsWith('#source-')) {
    event.preventDefault()
    emit('source', href.slice('#source-'.length))
  } else if (href?.startsWith('#guide-heading-')) {
    event.preventDefault()
    goTo(href.slice(1))
  }
}

function recordSection(heading) {
  emit('note', { title: heading.text, sourceLocation: `精读报告：${heading.text}`, excerpt: sectionText(contentRef.value, heading.id) })
}

function recordSelection() {
  const selection = window.getSelection()
  if (!selection?.rangeCount || !contentRef.value.contains(selection.anchorNode)
      || !contentRef.value.contains(selection.focusNode) || !selection.toString().trim()) {
    ElMessage.warning('请先在报告正文中选中需要记录的文字')
    return
  }
  const range = selection.getRangeAt(0)
  let title = '精读摘录'
  for (const heading of contentRef.value.querySelectorAll('h2, h3')) {
    if (range.comparePoint(heading, 0) === -1) title = heading.textContent
  }
  emit('note', { title, sourceLocation: `精读报告：${title}`, excerpt: selection.toString().trim() })
}

async function renderCharts() {
  const revision = ++renderRevision
  renderError.value = ''
  await nextTick()
  if (props.streaming || !contentRef.value) return
  const blocks = contentRef.value.querySelectorAll('pre code.language-mermaid')
  // Mermaid维护全局解析状态；顺序渲染，并显式展示每张图的失败。
  for (const [index, block] of [...blocks].entries()) {
    const pre = block.parentElement
    const code = block.textContent
    try {
      const { svg } = await mermaid.render(`reading${instanceId}r${revision}n${index}`, code)
      if (revision !== renderRevision || !pre.isConnected) return
      const container = document.createElement('div')
      container.className = 'reading-diagram'
      container.dataset.source = code
      container.innerHTML = DOMPurify.sanitize(svg, { USE_PROFILES: { svg: true, svgFilters: true }, ADD_ATTR: ['dominant-baseline'] })
      pre.replaceWith(container)
    } catch (error) {
      console.error('阅读报告图表渲染失败', error)
      if (revision !== renderRevision || !pre.isConnected) return
      const notice = document.createElement('p')
      notice.className = 'diagram-error'
      notice.textContent = `图表渲染失败：${error.message}`
      pre.before(notice)
    }
  }
}

function escapeFullscreen(event) {
  if (event.key === 'Escape') fullscreen.value = false
}

onMounted(() => {
  // 使用SVG原生文字，避免foreignObject中的HTML标签被安全过滤后变成空白。
  mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: 'default', htmlLabels: false, flowchart: { htmlLabels: false }, suppressErrorRendering: true })
  document.addEventListener('keydown', escapeFullscreen)
  renderCharts()
})
watch(() => [props.markdown, props.streaming], renderCharts)
onUnmounted(() => {
  renderRevision++
  document.removeEventListener('keydown', escapeFullscreen)
})
</script>

<style scoped>
.guide-reader { background: white; border: 1px solid #e3e8ee; border-radius: 12px; }
.reader-toolbar { position: sticky; top: 0; z-index: 2; display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 12px 16px; background: #f7f9fc; border-bottom: 1px solid #e3e8ee; font-size: 14px; }
.reader-layout { display: grid; grid-template-columns: 220px minmax(0, 1fr); align-items: start; }
.reader-toc { position: sticky; top: 62px; padding: 22px 12px; max-height: calc(100vh - 100px); overflow: auto; font-size: 13px; }
.reader-toc strong { display: block; padding: 0 8px 12px; color: #46576b; }
.toc-row { display: flex; align-items: start; }
.toc-row.nested { padding-left: 12px; }
.toc-row button { text-align: left; border: 0; background: none; padding: 8px; color: #526477; line-height: 1.5; cursor: pointer; flex: 1; }
.toc-row button:hover, .toc-row button.active { background: #ecf3fa; color: #1a5b91; border-radius: 5px; }
.toc-row .note-button { flex: 0; font-size: 18px; }
.guide-markdown { min-width: 0; max-width: 850px; padding: 24px 36px 48px; font-size: 16px; line-height: 1.9; color: #263547; overflow-wrap: anywhere; }
.guide-markdown :deep(h1) { font-size: 27px; line-height: 1.5; }
.guide-markdown :deep(h2) { font-size: 23px; margin-top: 36px; padding-bottom: 8px; border-bottom: 1px solid #e3e8ee; scroll-margin-top: 80px; }
.guide-markdown :deep(h3) { font-size: 19px; margin-top: 24px; scroll-margin-top: 80px; }
.guide-markdown :deep(h4) { font-size: 17px; }
.guide-markdown :deep(p) { margin: 14px 0; }
.guide-markdown :deep(a) { color: #1b639a; text-underline-offset: 3px; }
.guide-markdown :deep(table) { display: block; max-width: 100%; overflow-x: auto; border-collapse: collapse; font-size: 14px; }
.guide-markdown :deep(th), .guide-markdown :deep(td) { border: 1px solid #dce3eb; padding: 10px 12px; min-width: 110px; vertical-align: top; }
.guide-markdown :deep(th) { background: #f1f5f9; }
.guide-markdown :deep(pre) { background: #f4f6f8; padding: 16px; overflow-x: auto; line-height: 1.6; }
.guide-markdown :deep(blockquote) { margin: 16px 0; border-left: 3px solid #8daac4; padding: 4px 16px; background: #f7f9fc; }
.guide-markdown :deep(svg) { max-width: 100%; height: auto; }
.guide-markdown :deep(.diagram-error) { color: #b42318; }
.guide-markdown :deep(.claim-badge) { display: inline-block; font-size: 12px; line-height: 1.8; padding: 1px 7px; border-radius: 4px; margin-right: 4px; }
.guide-markdown :deep(.claim-fact) { background: #eaf3fa; color: #205f91; }
.guide-markdown :deep(.claim-analysis) { background: #f1edfa; color: #69509b; }
.guide-markdown :deep(.claim-advice) { background: #eaf5ef; color: #357452; }
.reader-fullscreen { position: fixed; inset: 0; z-index: 2100; overflow-y: auto; border-radius: 0; }
.reader-fullscreen .reader-layout { max-width: 1200px; margin: auto; }
@media (max-width: 760px) {
  .reader-layout { grid-template-columns: minmax(0, 1fr); }
  .reader-toc { position: static; max-height: 180px; border-bottom: 1px solid #e3e8ee; }
  .guide-markdown { padding: 18px; }
  .reader-toolbar { flex-wrap: wrap; }
}
</style>
