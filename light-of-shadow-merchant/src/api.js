import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 15000 })

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('merchant_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) return body
      if (body.code === 401) {
        localStorage.removeItem('merchant_token')
        location.hash = '#/login'
      }
      return Promise.reject(new Error(body.message || `code=${body.code}`))
    }
    return body
  },
  (err) => Promise.reject(err)
)

export async function login(account, password) {
  return http.post('/auth/login', { account, password })
}

export async function listProducts(params) {
  return http.get('/merchant/products', { params })
}

export async function getProduct(id) {
  return http.get(`/merchant/products/${id}`)
}

export async function createProduct(data) {
  return http.post('/merchant/products', data)
}

export async function updateProduct(id, data) {
  return http.patch(`/merchant/products/${id}`, data)
}

export async function deleteProduct(id) {
  return http.delete(`/merchant/products/${id}`)
}

/** 申请预签名上传 URL */
export async function requestUpload(contentType) {
  return http.post('/merchant/images/upload-request', { contentType })
}

/** 直传对象存储 */
export async function uploadToStorage(uploadUrl, file) {
  return axios.put(uploadUrl, file, {
    headers: { 'Content-Type': file.type },
    timeout: 60000
  })
}
