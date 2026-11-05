<template>
  <el-container class="page">
    <el-header class="header">
      <span class="title">商品管理</span>
      <div>
        <span class="user">{{ userName }}</span>
        <el-button link @click="logout">退出</el-button>
      </div>
    </el-header>
    <el-main>
      <div class="toolbar">
        <el-input
          v-model="keyword"
          placeholder="搜索商品名称 / 描述"
          clearable
          style="width: 260px"
          @keyup.enter="load(1)"
          @clear="load(1)"
        />
        <el-button type="primary" @click="load(1)">搜索</el-button>
        <el-button type="success" @click="$router.push('/products/new')">新增商品</el-button>
      </div>

      <el-table v-loading="loading" :data="items" border stripe>
        <el-table-column label="封面" width="110">
          <template #default="{ row }">
            <el-image
              v-if="row.coverUrl"
              :src="resolveUrl(row.coverUrl)"
              style="width: 80px; height: 80px"
              fit="cover"
              :preview-src-list="[resolveUrl(row.coverUrl)]"
              preview-teleported
            />
            <span v-else class="muted">无图</span>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column label="价格" width="110">
          <template #default="{ row }">¥{{ (row.priceCent / 100).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column prop="stock" label="库存" width="90" />
        <el-table-column prop="description" label="描述" min-width="180" show-overflow-tooltip />
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="$router.push(`/products/${row.id}`)">编辑</el-button>
            <el-popconfirm title="确定删除该商品？" @confirm="onDelete(row)">
              <template #reference>
                <el-button size="small" type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          layout="total, prev, pager, next"
          :total="total"
          :page-size="pageSize"
          :current-page="page"
          @current-change="load"
        />
      </div>
    </el-main>
  </el-container>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listProducts, deleteProduct } from '../api'

const router = useRouter()
const items = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 10
const keyword = ref('')
const loading = ref(false)
const userName = localStorage.getItem('merchant_name') || '商家'

function resolveUrl(url) {
  if (!url) return ''
  if (url.startsWith('http')) return url
  return url
}

async function load(p = 1) {
  loading.value = true
  try {
    const body = await listProducts({ page: p, size: pageSize, keyword: keyword.value || undefined })
    const data = body.data || {}
    items.value = data.items || []
    total.value = Number(data.total || items.value.length)
    page.value = p
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function onDelete(row) {
  try {
    await deleteProduct(row.id)
    ElMessage.success('已删除')
    load(page.value)
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

function logout() {
  localStorage.removeItem('merchant_token')
  localStorage.removeItem('merchant_name')
  router.push('/login')
}

onMounted(() => load(1))
</script>

<style scoped>
.page { min-height: 100vh; background: #f5f7fa; }
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #ebeef5;
}
.title { font-size: 18px; font-weight: 600; }
.user { margin-right: 8px; color: #606266; }
.toolbar { margin-bottom: 16px; display: flex; gap: 8px; }
.pager { margin-top: 16px; display: flex; justify-content: flex-end; }
.muted { color: #c0c4cc; }
</style>
