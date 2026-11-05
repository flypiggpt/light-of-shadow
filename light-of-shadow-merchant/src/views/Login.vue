<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <h2>商家管理后台</h2>
      <p class="sub">音影寻光 · Light of Shadow</p>
      <el-form @submit.prevent="onSubmit">
        <el-form-item label="账号">
          <el-input v-model="account" placeholder="用户名 / 邮箱" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="password" type="password" show-password placeholder="密码" />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="loading" style="width:100%">
          登录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login } from '../api'

const router = useRouter()
const account = ref('')
const password = ref('')
const loading = ref(false)

async function onSubmit() {
  if (!account.value || !password.value) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  loading.value = true
  try {
    const body = await login(account.value.trim(), password.value)
    const data = body.data || {}
    localStorage.setItem('merchant_token', data.accessToken || '')
    localStorage.setItem('merchant_name', data.nickname || account.value)
    ElMessage.success('登录成功')
    router.push('/products')
  } catch (e) {
    ElMessage.error(e.message || '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-wrap {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;
}
.login-card {
  width: 360px;
}
.sub {
  color: #909399;
  margin: 0 0 16px;
}
</style>
