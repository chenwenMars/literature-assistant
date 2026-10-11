<template>
  <div class="literature-detail-view">
    <el-skeleton v-if="literatureStore.detailLoading" :rows="10" animated />
    <el-alert v-else-if="literatureStore.error" :title="literatureStore.error" type="error" :closable="false" />
    <div v-else-if="literature" class="detail-content">
      <div class="detail-header">
        <el-button @click="router.push('/')">返回文献库</el-button>
        <div><h2>{{ literature.originalName }}</h2><p>理解作者的研究过程，积累自己的写作思路</p></div>
      </div>
      <div class="reading-context">
        <strong>我的研究方向：AI赋能的T公司采购系统研发项目管理研究</strong>
        <p>关注MBA项目管理与AI辅助管理。先学习参考论文的构思、方法与写法，逐步形成自己的研究设计。</p>
      </div>
      <el-alert v-if="literature.status === 2 || literature.status === 3" :title="literature.statusDesc" :description="literature.errorMessage" type="error" :closable="false" show-icon class="detail-alert" />
      <el-alert v-if="literature.status === 0" title="文献正在处理，稍后刷新查看结果" type="info" :closable="false" class="detail-alert" />
      <div class="document-summary">
        <el-tag v-for="tag in literature.tags" :key="tag">{{ tag }}</el-tag>
        <p v-if="literature.description">{{ literature.description }}</p>
        <details>
          <summary>文件信息与分析版本</summary>
          <p>类型：{{ literature.fileType.toUpperCase() }} · 大小：{{ formatSize(literature.fileSize) }} · 创建时间：{{ literature.createTime }}</p>
          <p>状态：{{ literature.statusDesc }} · 报告：{{ literature.reportVersion || '历史报告' }}</p>
        </details>
      </div>
      <div class="detail-actions">
        <el-button @click="openSource()">对照原文</el-button>
        <el-button @click="downloadFile">下载原文件</el-button>
        <el-button :disabled="!literature.readingGuideSummary" @click="exportGuide">导出精读报告</el-button>
      </div>
      <el-alert v-if="!literature.reportVersion" title="这是历史报告，保留原有内容；新导入文献采用六部分精读结构。" type="info" :closable="false" class="detail-alert" />
      <GuideReader v-if="literature.readingGuideSummary" :markdown="literature.readingGuideSummary" allow-notes @source="openSource" @note="notesRef.open($event)" />
      <el-empty v-else description="尚无精读报告" />
      <LiteratureNotes ref="notesRef" :literature-id="literature.id" :original-name="literature.originalName" />
    </div>
    <el-empty v-else description="未找到文献" />

    <el-drawer v-model="sourceVisible" title="原文对照 · 解析文本" size="min(780px, 96vw)">
      <el-alert v-if="sourceError" :title="sourceError" type="error" :closable="false" class="detail-alert" />
      <el-skeleton v-if="sourceLoading" :rows="6" animated />
      <template v-else-if="source">
        <el-alert v-if="!literature.reportVersion" title="此文本为重新解析的原文件，不代表历史报告生成时已读取这些片段。" type="warning" :closable="false" class="detail-alert" />
        <ul class="source-warnings"><li v-for="warning in source.warnings" :key="warning">{{ warning }}</li></ul>
        <el-input v-model="sourceQuery" placeholder="搜索原文文字、片段编号或位置" clearable aria-label="搜索原文" />
        <p class="source-count">{{ filteredBlocks.length }} / {{ source.blocks.length }} 个片段</p>
        <div v-for="block in filteredBlocks" :key="block.id" :id="`source-${block.id}`" class="source-block" :class="{ highlighted: highlightedSource === block.id }">
          <div class="source-block-header"><strong>{{ block.id }} · {{ block.label }}</strong><el-button size="small" @click="recordSource(block)">记录原文笔记</el-button></div>
          <pre>{{ block.text }}</pre>
        </div>
        <el-empty v-if="!filteredBlocks.length" description="没有匹配的原文片段" />
      </template>
      <div v-else-if="!sourceLoading && literature && !literature.sourceAvailable">
        <p>历史文献尚未保存原文快照。可以从原文件重新解析，用于阅读对照。</p>
        <el-button type="primary" @click="reextractSource">重新解析原文</el-button>
      </div>
      <el-button v-else-if="sourceError" @click="openSource()">重新加载原文</el-button>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useLiteratureStore } from '@/stores/literatureStore'
import GuideReader from '@/components/GuideReader.vue'
import LiteratureNotes from '@/components/LiteratureNotes.vue'
import { literatureRequest } from '@/utils/literatureApi'
import { downloadMarkdown } from '@/utils/guide'

const route = useRoute()
const router = useRouter()
const literatureStore = useLiteratureStore()
const literature = computed(() => literatureStore.currentLiterature)
const notesRef = ref(null)
const source = ref(null)
const sourceVisible = ref(false)
const sourceLoading = ref(false)
const sourceError = ref('')
const sourceQuery = ref('')
const highlightedSource = ref('')
const filteredBlocks = computed(() => source.value?.blocks.filter(block =>
  `${block.id} ${block.label} ${block.text}`.toLowerCase().includes(sourceQuery.value.toLowerCase())) || [])

async function openSource(id) {
  sourceVisible.value = true
  sourceError.value = ''
  if (!source.value && literature.value.sourceAvailable) {
    sourceLoading.value = true
    try { source.value = await literatureRequest('get', `${literature.value.id}/source`) }
    catch (failure) { sourceError.value = `原文加载失败：${failure.message}` }
    finally { sourceLoading.value = false }
  }
  if (typeof id === 'string' && source.value) {
    if (!source.value.blocks.some(block => block.id === id)) {
      sourceError.value = `报告中的来源编号${id}不存在，请核对报告与原文件`
      return
    }
    sourceQuery.value = ''
    highlightedSource.value = id
    await nextTick()
    document.getElementById(`source-${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }
}

async function reextractSource() {
  sourceLoading.value = true
  sourceError.value = ''
  try {
    source.value = await literatureRequest('post', `${literature.value.id}/source`)
    literature.value.sourceAvailable = true
  } catch (failure) { sourceError.value = `原文解析失败：${failure.message}` }
  finally { sourceLoading.value = false }
}

function recordSource(block) {
  sourceVisible.value = false
  notesRef.value.open({ title: `${block.id} 原文借鉴`, sourceLocation: `原文：${block.label} / ${block.id}`, excerpt: block.text })
}
function exportGuide() { downloadMarkdown(`${literature.value.originalName}-精读报告`, literature.value.readingGuideSummary) }
async function downloadFile() {
  try {
    const url = `/api/literature/${literature.value.id}/download`
    const response = await fetch(url, { method: 'HEAD' })
    if (!response.ok || response.headers.get('content-type')?.includes('application/json')) throw new Error('原文件下载失败，请核对文件是否存在')
    const link = document.createElement('a')
    link.href = url
    link.download = literature.value.originalName
    link.click()
  } catch (failure) { ElMessage.error(failure.message) }
}
function formatSize(bytes) { return bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(1)} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB` }
watch(() => route.params.id, async (id) => {
  source.value = null
  sourceQuery.value = ''
  sourceError.value = ''
  sourceVisible.value = false
  await literatureStore.fetchLiteratureDetail(id)
}, { immediate: true })
</script>

<style scoped>
.literature-detail-view { max-width: 1200px; margin: auto; }
.detail-header { display: flex; align-items: center; gap: 20px; margin-bottom: 24px; }
.detail-header h2 { margin: 0; font-size: 24px; overflow-wrap: anywhere; }
.detail-header p, .reading-context p { color: #64748b; font-size: 14px; line-height: 1.7; }
.reading-context { padding: 16px 20px; background: #edf4fa; border-radius: 10px; }
.reading-context strong { line-height: 1.7; }
.reading-context p { margin-bottom: 0; }
.document-summary { padding: 20px 0; line-height: 1.8; }
.document-summary .el-tag { margin: 0 8px 8px 0; }
.document-summary details { font-size: 13px; color: #64748b; }
summary { cursor: pointer; }
.detail-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 18px; }
.detail-actions .el-button { margin-left: 0; }
.detail-alert { margin: 16px 0; }
.source-warnings { padding-left: 20px; line-height: 1.8; color: #64748b; font-size: 13px; }
.source-count { font-size: 13px; color: #64748b; }
.source-block { padding: 18px; border: 1px solid #e3e8ee; border-radius: 8px; margin: 16px 0; scroll-margin-top: 20px; }
.source-block.highlighted { border-color: #2776b2; background: #eff6fc; }
.source-block-header { display: flex; justify-content: space-between; gap: 10px; align-items: center; font-size: 14px; }
.source-block pre { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; font-family: inherit; font-size: 15px; }
@media (max-width: 600px) { .detail-header { align-items: start; flex-direction: column; gap: 12px; } .detail-header h2 { font-size: 20px; } .source-block-header { flex-wrap: wrap; } }
</style>
