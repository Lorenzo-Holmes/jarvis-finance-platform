<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/client'

const emit = defineEmits(['profile-updated'])
const profile = ref(null)
const achievements = ref({ streakDays: 0, researchCompleted: 0, automationCount: 0, items: [] })
const activities = ref([])
const editing = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const form = ref({
  displayName: '', avatarUrl: '', signature: '', contactInfo: '',
  profilePublic: true, contactPublic: false, activityPublic: true,
})

function responseData(response) { return response?.data ?? response }
function pageItems(response) { return responseData(response)?.items || [] }
const unlockedCount = computed(() => achievements.value.items?.filter(item => item.unlocked).length || 0)
const profileProgress = computed(() => {
  const fields = [form.value.displayName, form.value.avatarUrl, form.value.signature, form.value.contactInfo]
  return Math.round(fields.filter(value => String(value || '').trim()).length / fields.length * 100)
})
const avatarUrlValid = computed(() => {
  const value = form.value.avatarUrl.trim()
  return !value || /^https:\/\/[^\s]+$/i.test(value)
})
const previewAvatarUrl = computed(() => editing.value && avatarUrlValid.value
  ? form.value.avatarUrl.trim()
  : profile.value?.avatarUrl || '')
const privacyExposureNote = computed(() => {
  if (form.value.contactPublic && !form.value.profilePublic) {
    return '个人资料当前为私密，但联系方式仍会单独公开给其他用户。'
  }
  if (!form.value.profilePublic && !form.value.activityPublic && !form.value.contactPublic) {
    return '当前为最小公开模式：其他用户只会看到你的昵称和用户 ID。'
  }
  if (!form.value.activityPublic) {
    return '研究动态与成就仅自己可见；其它资料按各自开关决定。'
  }
  return '公开范围由三个开关独立控制，修改后立即影响其他用户可见内容。'
})

function syncForm(value) {
  form.value = {
    displayName: value?.displayName || '', avatarUrl: value?.avatarUrl || '',
    signature: value?.signature || '', contactInfo: value?.contactInfo || '',
    profilePublic: value?.profilePublic !== false,
    contactPublic: Boolean(value?.contactPublic), activityPublic: value?.activityPublic !== false,
  }
}

async function load() {
  error.value = ''
  try {
    const [profileResponse, achievementResponse] = await Promise.all([
      api.socialProfile(), api.socialAchievements(),
    ])
    profile.value = responseData(profileResponse)
    achievements.value = responseData(achievementResponse) || achievements.value
    syncForm(profile.value)
    const activity = await api.socialUserActivity(profile.value.id, 0, 30)
    activities.value = pageItems(activity)
  } catch (e) { error.value = e?.message || String(e) }
}

async function save() {
  if (!form.value.displayName.trim()) return
  if (!avatarUrlValid.value) {
    error.value = '头像地址必须使用 HTTPS。'
    return
  }
  saving.value = true
  error.value = ''
  try {
    const response = await api.updateSocialProfile({
      ...form.value,
      displayName: form.value.displayName.trim(),
      avatarUrl: form.value.avatarUrl.trim(),
      signature: form.value.signature.trim(),
      contactInfo: form.value.contactInfo.trim(),
    })
    profile.value = responseData(response)
    editing.value = false
    notice.value = '资料与隐私设置已保存'
    emit('profile-updated', profile.value)
    await load()
    window.setTimeout(() => { notice.value = '' }, 2400)
  } catch (e) { error.value = e?.message || String(e) } finally { saving.value = false }
}

function formatTime(value) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(date)
}

onMounted(load)
</script>

<template>
  <section class="profile-page">
    <header class="page-head">
      <div><span>IDENTITY / RESEARCH HABIT</span><h2>个人中心</h2><p>管理公开身份、隐私边界与长期研究成就。</p></div>
      <button type="button" @click="editing = !editing">{{ editing ? '取消编辑' : '编辑资料' }}</button>
    </header>

    <div v-if="error" class="surface-alert error">{{ error }}</div>
    <div v-if="notice" class="surface-alert notice">{{ notice }}</div>

    <div v-if="profile" class="profile-grid">
      <main>
        <section class="identity-card">
          <div class="avatar large"><img v-if="previewAvatarUrl" :src="previewAvatarUrl" alt="个人头像" /><b v-else>{{ (profile.displayName || '?').slice(0, 1) }}</b></div>
          <div class="identity-copy"><span>USER / {{ profile.id }}</span><h3>{{ profile.displayName }}</h3><p>{{ profile.signature || '还没有个人签名。' }}</p><small>{{ profile.email }}</small></div>
          <div class="completion"><span>资料完整度</span><b>{{ profileProgress }}%</b><i><em :style="{ width: `${profileProgress}%` }"></em></i></div>
        </section>

        <section v-if="editing" class="edit-panel">
          <header><div><span>PROFILE EDITOR</span><h3>档案与隐私</h3></div><button type="button" :disabled="saving" @click="save">{{ saving ? '保存中' : '保存变更' }}</button></header>
          <div class="form-grid">
            <label><span>显示名称</span><input v-model="form.displayName" maxlength="60" /></label>
            <label><span>头像 HTTPS URL</span><input v-model="form.avatarUrl" maxlength="500" placeholder="https://…" :aria-invalid="!avatarUrlValid" /><small v-if="!avatarUrlValid" class="field-error">仅允许 HTTPS 图片地址</small></label>
            <label class="wide"><span>个人签名</span><textarea v-model="form.signature" maxlength="160" rows="3" /></label>
            <label class="wide"><span>联系方式</span><input v-model="form.contactInfo" maxlength="200" placeholder="邮箱 / 社交账号 / 其它自愿公开方式" /></label>
          </div>
          <div class="privacy-grid">
            <label><span><b>公开个人资料</b><small>允许其他用户查看头像与签名</small></span><input v-model="form.profilePublic" type="checkbox" /></label>
            <label><span><b>公开联系方式</b><small>单独控制联系方式是否可见</small></span><input v-model="form.contactPublic" type="checkbox" /></label>
            <label><span><b>公开动态与成就</b><small>控制研究动态和勋章展示</small></span><input v-model="form.activityPublic" type="checkbox" /></label>
          </div>
          <p class="privacy-note">{{ privacyExposureNote }}</p>
        </section>

        <section class="achievement-section">
          <header><div><span>ACHIEVEMENTS</span><h3>研究成就</h3></div><strong>{{ unlockedCount }}/{{ achievements.items?.length || 0 }}</strong></header>
          <div class="achievement-grid">
            <article v-for="item in achievements.items || []" :key="item.key" :class="{ unlocked: item.unlocked }">
              <div class="badge-mark"><span>{{ item.category }}</span><b>{{ item.unlocked ? '◆' : '◇' }}</b></div>
              <h4>{{ item.title }}</h4><p>{{ item.description }}</p>
              <div class="progress"><i><em :style="{ width: `${Math.min(100, ((item.progress || 0) / Math.max(1, item.target || 1)) * 100)}%` }"></em></i><small>{{ item.progress || 0 }}/{{ item.target || 1 }}</small></div>
              <time v-if="item.unlockedAt">{{ formatTime(item.unlockedAt) }}</time>
            </article>
          </div>
        </section>

        <section class="activity-section"><header><div><span>RECENT ACTIVITY</span><h3>最近动态</h3></div></header><div class="activity-list"><article v-for="item in activities" :key="item.id"><span>{{ item.type }}</span><p>{{ item.summary }}</p><time>{{ formatTime(item.createdAt) }}</time></article><div v-if="!activities.length" class="empty-state">暂无动态。</div></div></section>
      </main>

      <aside>
        <section class="metric-card primary"><span>连续登录</span><strong>{{ achievements.streakDays || 0 }}</strong><small>DAYS</small><p>保持稳定研究节律，而不是追求无意义签到。</p></section>
        <section class="metric-card"><span>研究任务完成</span><strong>{{ achievements.researchCompleted || 0 }}</strong><small>REPORTS</small></section>
        <section class="metric-card"><span>有效自动化</span><strong>{{ achievements.automationCount || 0 }}</strong><small>TASKS</small></section>
        <section class="privacy-summary"><span>公开边界</span><div><b>个人资料</b><i :class="profile.profilePublic ? 'on' : ''">{{ profile.profilePublic ? '公开' : '私密' }}</i></div><div><b>联系方式</b><i :class="profile.contactPublic ? 'on' : ''">{{ profile.contactPublic ? '公开' : '私密' }}</i></div><div><b>动态成就</b><i :class="profile.activityPublic ? 'on' : ''">{{ profile.activityPublic ? '公开' : '私密' }}</i></div></section>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.profile-page { display: grid; gap: 14px; min-height: 620px; color: var(--text); }
.page-head { display: flex; align-items: end; justify-content: space-between; gap: 18px; padding-bottom: 12px; border-bottom: 1px solid var(--line); }
.page-head > div > span, .identity-copy > span, .edit-panel header span, .achievement-section header span, .activity-section header span { color: var(--subtle); font: 650 8px/1.2 ui-monospace, monospace; letter-spacing: .1em; }
.page-head h2 { margin: 5px 0 4px; font-size: 24px; letter-spacing: -.025em; }.page-head p { margin: 0; color: var(--muted); font-size: 11px; }
.page-head > button, .edit-panel header button { border: 1px solid var(--line-strong); border-radius: 7px; background: var(--workspace-accent-wash); color: var(--text); padding: 8px 12px; cursor: pointer; font-size: 9px; }
.surface-alert { padding: 9px 11px; border: 1px solid var(--line); border-radius: 8px; font-size: 10px; }.surface-alert.error { color: var(--bad); }.surface-alert.notice { color: var(--accent-strong); }
.profile-grid { display: grid; grid-template-columns: minmax(0, 1fr) 230px; gap: 14px; }.profile-grid > main { display: grid; gap: 12px; align-content: start; }.profile-grid > aside { display: grid; gap: 9px; align-content: start; }
.identity-card, .edit-panel, .achievement-section, .activity-section, .metric-card, .privacy-summary { border: 1px solid var(--line); border-radius: 11px; background: color-mix(in srgb, var(--surface) 92%, transparent); }
.identity-card { display: grid; grid-template-columns: auto minmax(0, 1fr) 160px; align-items: center; gap: 14px; padding: 16px; }
.avatar { display: grid; place-items: center; overflow: hidden; border: 1px solid var(--line-strong); border-radius: 50%; background: var(--workspace-accent-wash); color: var(--accent-strong); }.avatar.large { width: 66px; height: 66px; }.avatar img { width: 100%; height: 100%; object-fit: cover; }.avatar b { font-size: 18px; }
.identity-copy h3 { margin: 5px 0; font-size: 20px; }.identity-copy p { margin: 0 0 6px; color: var(--muted); font-size: 10px; }.identity-copy small { color: var(--subtle); font-size: 8px; }
.completion { display: grid; gap: 7px; }.completion span { color: var(--subtle); font-size: 8px; }.completion b { font-size: 18px; }.completion > i, .progress > i { display: block; height: 3px; border-radius: 999px; background: var(--line); overflow: hidden; }.completion em, .progress em { display: block; height: 100%; background: var(--accent); }
.edit-panel { padding: 14px; display: grid; gap: 13px; }.edit-panel > header, .achievement-section > header, .activity-section > header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }.edit-panel h3, .achievement-section h3, .activity-section h3 { margin: 4px 0 0; font-size: 14px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }.form-grid label { display: grid; gap: 6px; }.form-grid label.wide { grid-column: 1 / -1; }.form-grid label > span { color: var(--muted); font-size: 8px; }.field-error { color: var(--bad); font-size: 7px; }
input, textarea { width: 100%; box-sizing: border-box; border: 1px solid var(--line); border-radius: 8px; background: var(--workspace-control-bg, var(--panel)); color: var(--text); padding: 9px 10px; font: inherit; outline: none; }input:focus, textarea:focus { border-color: color-mix(in srgb, var(--accent) 45%, var(--line)); }
.privacy-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }.privacy-grid label { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 10px; border: 1px solid var(--line); border-radius: 8px; }.privacy-grid label > span { display: grid; gap: 4px; }.privacy-grid b { font-size: 9px; }.privacy-grid small { color: var(--subtle); font-size: 7px; line-height: 1.4; }.privacy-grid input { width: 15px; height: 15px; }
.privacy-note { margin: -3px 0 0; padding: 8px 10px; border-left: 2px solid var(--accent); background: var(--workspace-accent-wash); color: var(--muted); font-size: 8px; line-height: 1.5; }
.achievement-section, .activity-section { padding: 14px; }.achievement-section header strong { color: var(--accent-strong); font: 650 11px/1 ui-monospace, monospace; }
.achievement-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 8px; margin-top: 12px; }.achievement-grid article { min-height: 135px; display: grid; align-content: start; gap: 7px; padding: 12px; border: 1px solid var(--line); border-radius: 9px; opacity: .52; }.achievement-grid article.unlocked { opacity: 1; border-color: color-mix(in srgb, var(--accent) 28%, var(--line)); background: color-mix(in srgb, var(--workspace-accent-wash) 28%, transparent); }.badge-mark { display: flex; align-items: center; justify-content: space-between; color: var(--subtle); font: 650 7px/1 ui-monospace, monospace; }.badge-mark b { color: var(--accent-strong); font-size: 12px; }.achievement-grid h4 { margin: 0; font-size: 10px; }.achievement-grid p { margin: 0; color: var(--muted); font-size: 8px; line-height: 1.5; }.achievement-grid time { color: var(--subtle); font-size: 7px; }.progress { display: grid; grid-template-columns: 1fr auto; align-items: center; gap: 7px; }.progress small { color: var(--subtle); font-size: 7px; }
.activity-list { display: grid; margin-top: 8px; }.activity-list article { display: grid; grid-template-columns: 140px 1fr auto; gap: 9px; padding: 10px 0; border-bottom: 1px solid var(--line); }.activity-list span, .activity-list time { color: var(--subtle); font-size: 8px; }.activity-list p { margin: 0; color: var(--muted); font-size: 9px; }
.metric-card { padding: 14px; display: grid; grid-template-columns: 1fr auto; gap: 5px; }.metric-card > span { color: var(--subtle); font-size: 8px; }.metric-card strong { grid-row: 2; font-size: 28px; line-height: 1; }.metric-card small { grid-row: 2; align-self: end; color: var(--accent-strong); font: 650 7px/1 ui-monospace, monospace; }.metric-card p { grid-column: 1 / -1; margin: 5px 0 0; color: var(--muted); font-size: 8px; line-height: 1.5; }.metric-card.primary { background: color-mix(in srgb, var(--workspace-accent-wash) 42%, var(--surface)); }
.privacy-summary { padding: 13px; display: grid; gap: 9px; }.privacy-summary > span { color: var(--subtle); font: 650 8px/1 ui-monospace, monospace; letter-spacing: .08em; }.privacy-summary div { display: flex; justify-content: space-between; gap: 8px; }.privacy-summary b { font-size: 8px; }.privacy-summary i { color: var(--subtle); font-size: 8px; font-style: normal; }.privacy-summary i.on { color: var(--accent-strong); }
.empty-state { padding: 18px 0; color: var(--subtle); font-size: 8px; }
@media (max-width: 980px) { .profile-grid { grid-template-columns: 1fr; }.profile-grid > aside { grid-template-columns: repeat(3, 1fr); }.privacy-summary { grid-column: 1 / -1; } }
@media (max-width: 700px) { .identity-card { grid-template-columns: auto 1fr; }.completion { grid-column: 1 / -1; }.form-grid, .privacy-grid, .profile-grid > aside { grid-template-columns: 1fr; }.activity-list article { grid-template-columns: 1fr; } }
</style>
