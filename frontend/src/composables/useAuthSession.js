import { computed, ref } from 'vue'
import { api } from '../api/client'

export function useAuthSession() {
  const user = ref(null)
  const restoring = ref(false)
  const isLoggedIn = computed(() => Boolean(user.value))

  function acceptLogin(value) {
    user.value = value || null
  }

  async function restore() {
    if (restoring.value) return user.value
    restoring.value = true
    try {
      const response = await api.me()
      user.value = response.code === 200 && response.data ? response.data : null
    } catch (_) {
      user.value = null
    } finally {
      restoring.value = false
    }
    return user.value
  }

  async function logout() {
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
