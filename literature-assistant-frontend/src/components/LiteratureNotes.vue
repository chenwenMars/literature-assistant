<template>
  <el-card class="notes-card" shadow="never">
    <template #header>
      <div class="notes-header">
        <div><h3>我的借鉴笔记</h3><p>记录写法、方法和待探索的思路，逐步积累论文参考材料。</p></div>
        <el-button :disabled="!notes.length" @click="exportNotes">导出笔记</el-button>
      </div>
    </template>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-button v-if="error" link @click="loadNotes">重新加载笔记</el-button>
    <el-skeleton v-if="loading" :rows="3" animated />
    <el-empty v-else-if="!error && !notes.length" description="从报告目录旁的＋或选中文字开始记录" />
    <div v-for="note in notes" :key="note.id" class="note-item">
      <div class="notes-header">
        <h4>{{ note.title }}</h4>
        <el-tag>{{ categoryLabel(note.category) }}</el-tag>
      </div>
      <p class="note-location">{{ note.sourceLocation }}</p>
      <details><summary>查看摘录或概述</summary><p class="note-text">{{ note.excerpt }}</p></details>
      <p class="note-text">{{ note.reflection || '尚未填写个人思考' }}</p>
      <el-button size="small" @click="open(note)">编辑</el-button>
      <el-button size="small" type="danger" :disabled="saving" @click="deleteNote(note)">删除</el-button>
    </div>
  </el-card>
  <el-dialog v-model="editorVisible" :title="editingId ? '编辑借鉴笔记' : '记录借鉴笔记'" width="min(680px, 94vw)" :close-on-click-modal="false">
    <el-alert v-if="editorError" :title="editorError" type="error" :closable="false" class="editor-alert" />
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
      <el-form-item label="笔记名称" prop="title"><el-input v-model="form.title" maxlength="200" show-word-limit /></el-form-item>
      <el-form-item label="类型" prop="category">
        <el-radio-group v-model="form.category"><el-radio-button v-for="item in noteCategories" :key="item.value" :value="item.value">{{ item.label }}</el-radio-button></el-radio-group>
      </el-form-item>
      <el-form-item label="来源位置（原文章节或报告章节，可补充片段编号）" prop="sourceLocation"><el-input v-model="form.sourceLocation" maxlength="500" /></el-form-item>
      <el-form-item label="摘录或概述（保留事实与建议的标识）" prop="excerpt"><el-input v-model="form.excerpt" type="textarea" :rows="6" maxlength="20000" show-word-limit /></el-form-item>
      <el-form-item label="我的思考、适用条件与下一步行动" prop="reflection"><el-input v-model="form.reflection" type="textarea" :rows="4" maxlength="20000" placeholder="这条思路能帮助我完成什么？需要什么资料？还有什么不能确定？" /></el-form-item>
    </el-form>
    <template #footer><el-button :disabled="saving" @click="editorVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveNote">保存笔记</el-button></template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, watch, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { literatureRequest } from '@/utils/literatureApi'
import { noteCategories, notesMarkdown, downloadMarkdown } from '@/utils/guide'

const props = defineProps({ literatureId: { type: [String, Number], required: true }, originalName: { type: String, required: true } })
const notes = ref([])
const loading = ref(false)
const error = ref('')
const saving = ref(false)
const editorVisible = ref(false)
const editorError = ref('')
const editingId = ref(null)
const formRef = ref(null)
const form = reactive({ title: '', category: 'WRITING', sourceLocation: '', excerpt: '', reflection: '' })
const required = { required: true, whitespace: true, message: '请填写此项', trigger: 'blur' }
const rules = { title: [required], category: [required], sourceLocation: [required], excerpt: [required] }
const categoryLabel = (value) => noteCategories.find(item => item.value === value)?.label || value

async function loadNotes() {
  loading.value = true
  error.value = ''
  try { notes.value = await literatureRequest('get', `${props.literatureId}/notes`) }
  catch (failure) { error.value = `笔记加载失败：${failure.message}` }
  finally { loading.value = false }
}

function open(note) {
  editingId.value = note.id ?? null
  Object.assign(form, { title: note.title, category: note.category ?? 'WRITING', sourceLocation: note.sourceLocation,
    excerpt: note.excerpt, reflection: note.reflection ?? '' })
  editorError.value = ''
  editorVisible.value = true
  nextTick(() => formRef.value?.clearValidate())
}

async function saveNote() {
  if (!await formRef.value.validate().catch(() => false)) return
  saving.value = true
  editorError.value = ''
  try {
    const suffix = editingId.value ? `/notes/${editingId.value}` : '/notes'
    await literatureRequest(editingId.value ? 'put' : 'post', `${props.literatureId}${suffix}`, { ...form })
    editorVisible.value = false
    ElMessage.success('借鉴笔记已保存')
    await loadNotes()
  } catch (failure) { editorError.value = `笔记保存失败：${failure.message}` }
  finally { saving.value = false }
}

async function deleteNote(note) {
  try { await ElMessageBox.confirm(`删除笔记“${note.title}”？`, '删除笔记', { type: 'warning' }) }
  catch (action) { if (action === 'cancel' || action === 'close') return; throw action }
  saving.value = true
  error.value = ''
  try {
    await literatureRequest('delete', `${props.literatureId}/notes/${note.id}`)
    await loadNotes()
  } catch (failure) { error.value = `笔记删除失败：${failure.message}` }
  finally { saving.value = false }
}

function exportNotes() { downloadMarkdown(`${props.originalName}-借鉴笔记`, notesMarkdown(props.originalName, notes.value)) }
watch(() => props.literatureId, loadNotes, { immediate: true })
defineExpose({ open })
</script>

<style scoped>
.notes-card { margin-top: 24px; }
.notes-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
h3, h4 { margin: 0; }
.notes-header p, .note-location { color: #64748b; font-size: 13px; }
.note-item { border-bottom: 1px solid #e3e8ee; padding: 20px 0; }
.note-text { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; }
summary { cursor: pointer; color: #1b639a; }
.editor-alert { margin-bottom: 16px; }
@media(max-width: 600px) { .notes-header { flex-wrap: wrap; } }
</style>
