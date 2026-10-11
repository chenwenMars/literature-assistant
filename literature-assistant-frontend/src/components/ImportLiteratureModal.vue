<template>
  <el-dialog v-model="visible" title="导入文献 · 精读与写作学习" width="min(1100px, 96vw)" :close-on-click-modal="false" :close-on-press-escape="false" @closed="onClosed">
    <el-form v-if="!started" ref="formRef" :model="form" :rules="rules" label-position="top">
      <el-form-item label="文献文件" prop="file">
        <el-upload :auto-upload="false" :limit="1" :file-list="fileList" accept=".pdf,.doc,.docx,.md,.markdown" drag :on-change="changeFile" :on-remove="removeFile">
          <p>点击或拖入PDF、Word、Markdown文献</p><p>单个文件不超过10MB；扫描PDF需先完成OCR。</p>
        </el-upload>
      </el-form-item>
      <el-form-item label="Kimi API Key" prop="apiKey">
        <el-input v-model="form.apiKey" type="password" show-password placeholder="请输入Kimi API Key" />
        <p class="form-tip">沿用现有设置，密钥保存在此浏览器本地。</p><el-button link @click="clearKey">清除保存的密钥</el-button>
      </el-form-item>
      <el-alert title="报告将重点拆解整体构思、章节写法和研究方法，帮助积累MBA项目管理论文的写作思路。" type="info" :closable="false" />
    </el-form>
    <div v-else>
      <el-alert :title="progress" :type="complete ? 'success' : failure ? 'error' : 'info'" :closable="false" show-icon />
      <p v-if="failure" class="failure-message">{{ failure }}。下方内容如有显示，仅为本次生成预览；请到文献详情核对保存状态。</p>
      <GuideReader :markdown="content" :streaming="streaming" @source="sourceHint" />
    </div>
    <el-alert v-if="!started && failure" :title="failure" type="error" :closable="false" class="form-error" />
    <template #footer>
      <el-button @click="close">{{ streaming ? '取消生成并关闭' : '关闭' }}</el-button>
      <el-button v-if="!started" type="primary" @click="submit">生成精读报告</el-button>
      <el-button v-if="failure && started" @click="reset">重新选择文件</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, computed, watch, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchEventSource } from '@microsoft/fetch-event-source'
import GuideReader from '@/components/GuideReader.vue'

const props = defineProps({ modelValue: Boolean })
const emit = defineEmits(['update:modelValue', 'success'])
const visible = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const formRef = ref(null)
const form = reactive({ file: null, apiKey: '' })
const fileList = ref([])
const started = ref(false)
const streaming = ref(false)
const complete = ref(false)
const failure = ref('')
const progress = ref('')
const content = ref('')
const rules = { file: [{ required: true, message: '请选择文献' }], apiKey: [{ required: true, whitespace: true, message: '请填写API Key' }] }
const storageKey = 'literature_assistant_api_key'
let controller = null
let attempted = false

function changeFile(file) { form.file = file.raw; fileList.value = [file] }
function removeFile() { form.file = null; fileList.value = [] }
function clearKey() {
  try { localStorage.removeItem(storageKey); form.apiKey = '' }
  catch (error) { failure.value = `清除密钥失败：${error.message}` }
}
function sourceHint() { ElMessage.info('导入完成后，在文献详情中点击来源链接对照原文') }

async function submit() {
  if (!await formRef.value.validate().catch(() => false)) return
  failure.value = ''
  if (!/\.(pdf|docx?|md|markdown)$/i.test(form.file.name) || form.file.size > 10 * 1024 * 1024) {
    failure.value = '请选择支持的文献格式，文件大小不能超过10MB'
    return
  }
  if (!form.apiKey.trim().startsWith('sk-')) { failure.value = 'API Key格式错误'; return }
  try { localStorage.setItem(storageKey, form.apiKey.trim()) }
  catch (error) { failure.value = `保存本地密钥失败：${error.message}`; return }
  const body = new FormData()
  body.append('file', form.file)
  body.append('apiKey', form.apiKey.trim())
  controller = new AbortController()
  const requestController = controller
  attempted = true
  started.value = true
  streaming.value = true
  complete.value = false
  content.value = ''
  progress.value = '正在解析原文…'
  try {
    // POST生成操作不可自动重连，否则会重复上传并创建文献。
    await fetchEventSource('/api/literature/generate-guide', {
      method: 'POST', body, signal: requestController.signal, openWhenHidden: true,
      async onopen(response) {
        if (!response.ok || !response.headers.get('content-type')?.includes('text/event-stream')) {
          throw new Error(`导入请求失败：HTTP ${response.status} ${await response.text()}`)
        }
      },
      onmessage(event) {
        switch (event.event) {
          case 'start': case 'progress': progress.value = event.data; break
          case 'content': content.value += event.data.replace(/<empty-line>/g, '\n').replace(/<empty-space>/g, ' '); break
          case 'complete':
            complete.value = true
            streaming.value = false
            progress.value = event.data
            requestController.abort()
            break
          case 'error':
            failure.value = event.data
            progress.value = '分析未完整完成'
            streaming.value = false
            requestController.abort()
            break
        }
      },
      onerror(error) { throw error },
      onclose() { if (!complete.value && !failure.value) throw new Error('连接提前结束，未收到生成完成事件') }
    })
  } catch (error) {
    if (!requestController.signal.aborted) {
      console.error('文献导入失败', error)
      failure.value = error.message
      progress.value = '分析失败'
    }
  } finally { streaming.value = false }
}

function close() { controller?.abort(); visible.value = false }
function onClosed() {
  controller?.abort()
  if (attempted) emit('success') // 刷新文库以显示真实成功/失败状态，不表示分析成功。
  attempted = false
  reset()
}
function reset() {
  controller?.abort()
  started.value = false
  streaming.value = false
  complete.value = false
  failure.value = ''
  content.value = ''
  removeFile()
}
watch(() => props.modelValue, value => {
  if (!value) return
  try { form.apiKey = localStorage.getItem(storageKey) || '' }
  catch (error) { failure.value = `读取保存的密钥失败：${error.message}` }
})
onUnmounted(() => controller?.abort())
</script>

<style scoped>
.form-tip { color: #64748b; font-size: 13px; }
.form-error, .guide-reader { margin-top: 16px; }
.failure-message { color: #b42318; line-height: 1.7; }
:deep(.el-upload), :deep(.el-upload-dragger) { width: 100%; }
</style>
