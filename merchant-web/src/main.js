import { createApp } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import Login from './views/Login.vue'
import ProductList from './views/ProductList.vue'
import ProductEdit from './views/ProductEdit.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', component: Login },
    { path: '/', redirect: '/products' },
    { path: '/products', component: ProductList },
    { path: '/products/new', component: ProductEdit },
    { path: '/products/:id', component: ProductEdit }
  ]
})

router.beforeEach((to) => {
  const token = localStorage.getItem('merchant_token')
  if (to.path !== '/login' && !token) return '/login'
  return true
})

createApp(App).use(ElementPlus).use(router).mount('#app')
