import { computed, ref } from 'vue'
import { api } from '../api/client.js'

export function useAuthSession() {
  const user = ref(null)
  const restoring = ref(false)
  const isLoggedIn = computed(() => Boolean(user.value))
  let restoreRequestId = 0

  function acceptLogin(value) {
    // 使正在进行的会话恢复请求失效，避免旧的 401 响应覆盖刚完成的登录。
    restoreRequestId += 1
    user.value = value || null
  }

  async function restore() {
    if (restoring.value) return user.value
    const requestId = ++restoreRequestId
    restoring.value = true
    try {
      const response = await api.me()
      if (requestId !== restoreRequestId) return user.value
      user.value = response.code === 200 && response.data ? response.data : null
    } catch (_) {
      if (requestId === restoreRequestId) user.value = null
    } finally {
      restoring.value = false
    }
    return user.value
  }

  async function logout() {
    // 登出也要使尚未返回的 restore() 结果失效。
    restoreRequestId += 1
    try {
      await api.logout()
    } catch (_) {
      // 网络异常不应阻止本地会话视图退出。
    }
    user.value = null
  }

  return {
    user,
    restoring,
    isLoggedIn,
    acceptLogin,
    restore,
    logout,
  }
}
