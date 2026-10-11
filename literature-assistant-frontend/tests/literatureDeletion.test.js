import { test, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { createPinia, setActivePinia } from 'pinia'
import axios from 'axios'
import { useLiteratureStore } from '../src/stores/literatureStore.js'

const originalAdapter = axios.defaults.adapter
afterEach(() => { axios.defaults.adapter = originalAdapter })

function setupStore() {
  setActivePinia(createPinia())
  const store = useLiteratureStore()
  store.literatureList = [{ id: 11, tags: [] }]
  store.currentLiterature = { id: 11 }
  store.total = 11
  store.updateQueryParams({ page: 2, keyword: 'test' })
  return store
}

test('删除末页最后一条后重新查询上一页并保留筛选条件', async () => {
  const store = setupStore()
  const requestedPages = []
  axios.defaults.adapter = async config => {
    if (config.method === 'delete') {
      assert.equal(config.url, '/api/literature/11')
      return { data: { success: true } }
    }
    const query = JSON.parse(config.data)
    assert.equal(query.keyword, 'test')
    requestedPages.push(query.pageNum)
    return { data: { success: true, data: {
      records: query.pageNum === 2 ? [] : [{ id: 10, tags: ['test'] }], total: 10
    } } }
  }
  await store.deleteLiterature(11)
  assert.deepEqual(requestedPages, [2, 1])
  assert.equal(store.queryParams.page, 1)
  assert.equal(store.literatureList[0].id, 10)
  assert.equal(store.currentLiterature, null)
  assert.equal(store.total, 10)
  assert.equal(store.loading, false)
})

test('业务删除失败不会移除列表记录或清空详情', async () => {
  const store = setupStore()
  axios.defaults.adapter = async () => ({ data: { success: false, message: '文献不存在或已删除' } })
  await assert.rejects(store.deleteLiterature(11), /文献不存在或已删除/)
  assert.equal(store.literatureList[0].id, 11)
  assert.equal(store.currentLiterature.id, 11)
  assert.equal(store.total, 11)
})

test('网络失败向调用者传播且保留列表记录', async () => {
  const store = setupStore()
  axios.defaults.adapter = async () => { throw new Error('connection refused') }
  await assert.rejects(store.deleteLiterature(11), /connection refused/)
  assert.equal(store.literatureList[0].id, 11)
})

test('删除全部记录后停留第一页并显示空列表', async () => {
  const store = setupStore()
  store.updateQueryParams({ page: 1 })
  store.total = 1
  axios.defaults.adapter = async config => ({ data: config.method === 'delete'
    ? { success: true }
    : { success: true, data: { records: [], total: 0 } } })
  await store.deleteLiterature(11)
  assert.equal(store.queryParams.page, 1)
  assert.equal(store.total, 0)
  assert.deepEqual(store.literatureList, [])
})

test('批量删除一次提交选中的ID，删除末页后回到上一页并保留筛选条件', async () => {
  const store = setupStore()
  store.literatureList.push({ id: 12, tags: [] })
  const requestedPages = []
  let deleteRequests = 0
  axios.defaults.adapter = async config => {
    if (config.method === 'delete') {
      deleteRequests++
      assert.equal(config.url, '/api/literature/batch')
      assert.deepEqual(JSON.parse(config.data), { ids: [11, 12] })
      return { data: { success: true } }
    }
    const query = JSON.parse(config.data)
    assert.equal(query.keyword, 'test')
    requestedPages.push(query.pageNum)
    return { data: { success: true, data: {
      records: query.pageNum === 2 ? [] : [{ id: 10, tags: ['test'] }], total: 10
    } } }
  }
  await store.deleteLiteratures([11, 12])
  assert.equal(deleteRequests, 1)
  assert.deepEqual(requestedPages, [2, 1])
  assert.equal(store.queryParams.page, 1)
  assert.equal(store.currentLiterature, null)
  assert.equal(store.literatureList[0].id, 10)
  assert.equal(store.total, 10)
})

test('批量删除失败保留全部记录和详情且不重新查询', async () => {
  const store = setupStore()
  store.literatureList.push({ id: 12, tags: [] })
  axios.defaults.adapter = async config => {
    assert.equal(config.method, 'delete')
    return { data: { success: false, message: '部分文献不存在或已删除，本次批量删除已取消' } }
  }
  await assert.rejects(store.deleteLiteratures([11, 12]), /本次批量删除已取消/)
  assert.deepEqual(store.literatureList.map(item => item.id), [11, 12])
  assert.equal(store.currentLiterature.id, 11)
  assert.equal(store.total, 11)
})

test('批量删除网络异常向上传播并保留记录', async () => {
  const store = setupStore()
  axios.defaults.adapter = async () => { throw new Error('connection refused') }
  await assert.rejects(store.deleteLiteratures([11]), /connection refused/)
  assert.equal(store.literatureList[0].id, 11)
  assert.equal(store.currentLiterature.id, 11)
})

test('批量删除全部文献后显示空列表并保留未被删除的详情', async () => {
  const store = setupStore()
  store.currentLiterature = { id: 20 }
  store.updateQueryParams({ page: 1 })
  axios.defaults.adapter = async config => ({ data: config.method === 'delete'
    ? { success: true }
    : { success: true, data: { records: [], total: 0 } } })
  await store.deleteLiteratures([11])
  assert.equal(store.queryParams.page, 1)
  assert.equal(store.total, 0)
  assert.deepEqual(store.literatureList, [])
  assert.equal(store.currentLiterature.id, 20)
})
