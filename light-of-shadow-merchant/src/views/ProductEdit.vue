<template>
  <el-container class="page">
    <el-header class="header">
      <el-button link @click="$router.push('/products')">← 返回列表</el-button>
      <span class="title">{{ isEdit ? '编辑商品' : '新增商品' }}</span>
    </el-header>
    <el-main>
      <el-form :model="form" label-width="90px" style="max-width: 560px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="255" placeholder="商品名称" />
        </el-form-item>
        <el-form-item label="价格（元）" required>
          <el-input-number
            v-model="priceYuan"
            :min="0"
            :precision="2"
            :step="1"
            controls-position="right"
            style="width: 200px"
          />
        </el-form-item>
        <el-form-item label="库存" required>
          <el-input-number v-model="form.stock" :min="0" :step="1" controls-position="right" style="width: 200px" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="2000" />
        </el-form-item>
        <el-form-item label="封面图">
          <div>
            <el-image
              v-if="form.coverUrl"
              :src="form.coverUrl"
              style="width: 160px; height: 160px; border-radius: 6px"
              fit="cover"
            />
            <div style="margin-top: 8px">
              <input ref="fileInput" type="file" accept="image/jpeg,image/png,image/webp" @change="onFileChange" />
            </div>
            <div class="tip">
              支持 jpg/png/webp，不超过 2MB；上传后自动填入封面地址
            </div>
          </div>
        </el-form-item>
        <el-form-item label="封面 URL">
          <el-input v-model="form.coverUrl" placeholder="可手动粘贴 /media/... 或 https://..." />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
        </el-form-item>
      </el-form>
    </el-main>
  </el-container>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getProduct, createProduct, updateProduct, requestUpload, uploadToStorage } from '../api'

const route = useRoute()
const router = useRouter()
const isEdit = computed(() => !!route.params.id)
const form = ref({ name: '', description: '', priceCent: 0, stock: 0, coverUrl: '' })
const priceYuan = computed({
  get: () => (form.value.priceCent || 0) / 100,
  set: (v) => { form.value.priceCent = Math.round((v || 0) * 100) }
})
const saving = ref(false)
const uploading = ref(false)
const fileInput = ref(null)

async function load() {
  if (!isEdit.value) return
  try {
    const body = await getProduct(route.params.id)
    const data = body.data || body.data?.product || body.data
    form.value = {
      name: data.name || '',
      description: data.description || '',
      priceCent: data.priceCent || 0,
      stock: data.stock || 0,
      coverUrl: data.coverUrl || ''
    }
  } catch (e) {
    ElMessage.error(e.message || '加载商品失败')
  }
}

async function onFileChange(e) {
  const file = e.target.files?.[0]
  if (!file) return
  if (file.size > 2 * 1024 * 1024) {
    ElMessage.warning('图片不能超过 2MB')
    return
  }
  uploading.value = true
  try {
    // 1. 申请预签名 URL
    const req = await requestUpload(file.type)
    const d = req.data
    // 2. 直传对象存储
    await uploadToStorage(d.uploadUrl, file)
    // 3. 回填访问地址
    form.value.coverUrl = d.publicUrl
    ElMessage.success('图片上传成功')
  } catch (err) {
    ElMessage.error(err.message || '图片上传失败')
  } finally {
    uploading.value = false
  }
}

async function save() {
  if (!form.value.name) {
    ElMessage.warning('请输入商品名称')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.value.name,
      description: form.value.description,
      priceCent: form.value.priceCent,
      stock: form.value.stock,
      coverUrl: form.value.coverUrl
    }
    if (isEdit.value) {
      await updateProduct(route.params.id, payload)
    } else {
      await createProduct(payload)
    }
    ElMessage.success('已保存')
    router.push('/products')
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page { min-height: 100vh; background: #f5f7fa; }
.header {
  display: flex;
  align-items: center;
  gap: 16px;
  background: #fff;
  border-bottom: 1px solid #ebeef5;
}
.title { font-size: 18px; font-weight: 600; }
.tip { color: #909399; font-size: 12px; margin-top: 4px; }
</style>
