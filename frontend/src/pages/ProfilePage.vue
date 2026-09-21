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
const achievementCategory = ref('ALL')
const achievementStatus = ref('ALL')
const activityType = ref('ALL')
const form = ref({
  displayName: '', avatarUrl: '', signature: '', contactInfo: '',
  profilePublic: true, contactPublic: false, activityPublic: true,
})

function responseData(response) { return response?.data ?? response }
function pageItems(response) { return responseData(response)?.items || [] }
const unlockedCount = computed(() => achievements.value.items?.filter(item => item.unlocked).length || 0)
const achievementCategories = computed(() => ['ALL', ...new Set((achievements.value.items || []).map(item => item.category).filter(Boolean))])
const categoryAchievements = computed(() => achievementCategory.value === 'ALL'
  ? achievements.value.items || []
  : (achievements.value.items || []).filter(item => item.category === achievementCategory.value))
const displayedAchievements = computed(() => {
  if (achievementStatus.value === 'UNLOCKED') return categoryAchievements.value.filter(item => item.unlocked)
  if (achievementStatus.value === 'IN_PROGRESS') return categoryAchievements.value.filter(item => !item.unlocked)
  return categoryAchievements.value
})
const activityTypes = computed(() => ['ALL', ...new Set(activities.value.map(item => item.type).filter(Boolean))])
const displayedActivities = computed(() => activityType.value === 'ALL'
  ? activities.value
  : activities.value.filter(item => item.type === activityType.value))
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
      <button type="button" :class="{ active: editing }" @click="editing = !editing">{{ editing ? '取消编辑' : '编辑资料' }}</button>
    </header>

    <div v-if="error" class="surface-alert error" role="alert">{{ error }}</div>
    <div v-if="notice" class="surface-alert notice" role="status" aria-live="polite">{{ notice }}</div>

    <div v-if="profile" class="profile-grid">
      <main>
        <section class="identity-card">
          <div class="avatar large"><img v-if="previewAvatarUrl" :src="previewAvatarUrl" alt="个人头像" loading="lazy" decoding="async" referrerpolicy="no-referrer" /><b v-else>{{ (profile.displayName || '?').slice(0, 1) }}</b></div>
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
          <nav class="achievement-filters" aria-label="成就分类">
            <button v-for="category in achievementCategories" :key="category" type="button" :aria-pressed="achievementCategory === category" :class="{ active: achievementCategory === category }" @click="achievementCategory = category">{{ category === 'ALL' ? '全部' : category }}</button>
          </nav>
          <nav class="achievement-status" aria-label="成就状态">
            <button type="button" :class="{ active: achievementStatus === 'ALL' }" @click="achievementStatus = 'ALL'">全部</button>
            <button type="button" :class="{ active: achievementStatus === 'UNLOCKED' }" @click="achievementStatus = 'UNLOCKED'">已解锁</button>
            <button type="button" :class="{ active: achievementStatus === 'IN_PROGRESS' }" @click="achievementStatus = 'IN_PROGRESS'">进行中</button>
          </nav>
          <div class="achievement-grid">
            <article v-for="item in displayedAchievements" :key="item.key" :class="{ unlocked: item.unlocked }">
              <div class="badge-mark"><span>{{ item.category }}</span><b>{{ item.unlocked ? '◆' : '◇' }}</b></div>
              <h4>{{ item.title }}</h4><p>{{ item.description }}</p>
              <div class="progress"><i><em :style="{ width: `${Math.min(100, ((item.progress || 0) / Math.max(1, item.target || 1)) * 100)}%` }"></em></i><small>{{ item.progress || 0 }}/{{ item.target || 1 }}</small></div>
              <time v-if="item.unlockedAt">{{ formatTime(item.unlockedAt) }}</time>
            </article>
          </div>
        </section>

        <section class="activity-section"><header><div><span>RECENT ACTIVITY</span><h3>最近动态</h3></div></header><nav class="activity-filters" aria-label="动态类型"><button v-for="type in activityTypes" :key="type" type="button" :class="{ active: activityType === type }" @click="activityType = type">{{ type === 'ALL' ? '全部' : type }}</button></nav><div class="activity-list"><article v-for="item in displayedActivities" :key="item.id"><span>{{ item.type }}</span><p>{{ item.summary }}</p><time>{{ formatTime(item.createdAt) }}</time></article><div v-if="!displayedActivities.length" class="empty-state">当前筛选下暂无动态。</div></div></section>
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
.profile-page { --social-motion-press: var(--ds-motion-press, 100ms); --social-motion-state: var(--ds-motion-state, 160ms); --social-motion-surface: var(--ds-motion-surface, 220ms); --social-motion-layout: var(--ds-motion-layout, 340ms); --social-ease: var(--ds-ease, cubic-bezier(.22,1,.36,1)); display: grid; gap: 14px; min-height: 620px; color: var(--text); }
.page-head { display: flex; align-items: end; justify-content: space-between; gap: 18px; padding-bottom: 12px; border-bottom: 1px solid var(--line); }
.page-head > div > span, .identity-copy > span, .edit-panel header span, .achievement-section header span, .activity-section header span { color: var(--subtle); font: 650 8px/1.2 ui-monospace, monospace; letter-spacing: .1em; }
.page-head h2 { margin: 5px 0 4px; font-size: 24px; letter-spacing: -.025em; }.page-head p { margin: 0; color: var(--muted); font-size: 11px; }
.page-head > button, .edit-panel header button { border: 1px solid var(--line-strong); border-radius: 7px; background: var(--workspace-accent-wash); color: var(--text); padding: 8px 12px; cursor: pointer; font-size: 9px; }
.page-head > button.active { color: var(--accent-strong); border-color: color-mix(in srgb, var(--accent) 30%, var(--line-strong)); background: color-mix(in srgb, var(--workspace-accent-wash) 72%, transparent); box-shadow: inset 0 1px 0 rgba(255,255,255,.025); }
.profile-page button { transition: color var(--social-motion-state) ease, background var(--social-motion-state) ease, border-color var(--social-motion-state) ease, box-shadow var(--social-motion-state) ease, transform var(--social-motion-press) ease; }
.profile-page button:active:not(:disabled) { transform: scale(.98); }
.profile-page button:focus-visible, .profile-page input:focus-visible, .profile-page textarea:focus-visible { outline: 0; box-shadow: var(--ds-focus-shadow, 0 0 0 3px color-mix(in srgb, var(--accent) 11%, transparent)); }
.surface-alert { position: fixed; z-index: 80; top: 74px; right: 24px; max-width: min(420px, calc(100vw - 32px)); padding: 10px 13px; border: 1px solid var(--line-strong); border-radius: 10px; background: color-mix(in srgb, var(--material-elevated, var(--surface)) 92%, transparent); box-shadow: var(--material-shadow, 0 16px 40px rgba(0,0,0,.16)); backdrop-filter: blur(18px); font-size: 10px; animation: social-toast-in var(--social-motion-surface) var(--social-ease) both; }.surface-alert.error { color: var(--bad); border-color: color-mix(in srgb, var(--bad) 24%, var(--line)); }.surface-alert.notice { color: var(--accent-strong); border-color: color-mix(in srgb, var(--accent) 24%, var(--line)); }
.profile-grid { display: grid; grid-template-columns: minmax(0, 1fr) 230px; gap: 14px; }.profile-grid > main { display: grid; gap: 12px; align-content: start; }.profile-grid > aside { display: grid; gap: 9px; align-content: start; }
.identity-card, .edit-panel, .achievement-section, .activity-section, .metric-card, .privacy-summary { border: 1px solid var(--line); border-radius: 11px; background: color-mix(in srgb, var(--surface) 92%, transparent); }
.identity-card, .edit-panel, .achievement-section, .activity-section, .metric-card, .privacy-summary { animation: social-profile-in var(--social-motion-surface) var(--social-ease) both; }
.achievement-section { animation-delay: 35ms; }.activity-section { animation-delay: 70ms; }.profile-grid > aside > :nth-child(2) { animation-delay: 35ms; }.profile-grid > aside > :nth-child(3) { animation-delay: 70ms; }.profile-grid > aside > :nth-child(4) { animation-delay: 105ms; }
.identity-card { position: relative; overflow: hidden; display: grid; grid-template-columns: auto minmax(0, 1fr) 160px; align-items: center; gap: 16px; padding: 18px; background: radial-gradient(circle at 7% 16%, color-mix(in srgb, var(--workspace-accent-wash) 72%, transparent), transparent 42%), linear-gradient(135deg, color-mix(in srgb, var(--surface-2, var(--surface)) 56%, transparent), color-mix(in srgb, var(--surface) 94%, transparent)); box-shadow: inset 0 1px 0 rgba(255,255,255,.028); }
.identity-card::after { content: ''; position: absolute; right: 18px; bottom: 0; width: 120px; height: 1px; background: linear-gradient(90deg, transparent, color-mix(in srgb, var(--accent) 42%, transparent)); pointer-events: none; }
.avatar { display: grid; place-items: center; overflow: hidden; border: 1px solid var(--line-strong); border-radius: 50%; background: var(--workspace-accent-wash); color: var(--accent-strong); }.avatar.large { width: 68px; height: 68px; box-shadow: 0 0 0 5px color-mix(in srgb, var(--workspace-accent-wash) 58%, transparent), 0 12px 30px rgba(0,0,0,.10); transition: transform var(--social-motion-surface) var(--social-ease), box-shadow var(--social-motion-surface) ease; }.identity-card:hover .avatar.large { transform: scale(1.025); box-shadow: 0 0 0 6px color-mix(in srgb, var(--workspace-accent-wash) 68%, transparent), 0 14px 34px rgba(0,0,0,.13); }.avatar img { width: 100%; height: 100%; object-fit: cover; }.avatar b { font-size: 18px; }
.identity-copy h3 { margin: 5px 0; font-size: 21px; letter-spacing: -.025em; }.identity-copy p { margin: 0 0 6px; color: var(--muted); font-size: 10px; }.identity-copy small { color: var(--subtle); font-size: 8px; }
.completion { display: grid; gap: 7px; padding: 11px; border: 1px solid color-mix(in srgb, var(--line) 82%, transparent); border-radius: 9px; background: color-mix(in srgb, var(--panel) 46%, transparent); }.completion span { color: var(--subtle); font-size: 8px; }.completion b { font-size: 19px; font-variant-numeric: tabular-nums; }.completion > i, .progress > i { display: block; height: 4px; border-radius: 999px; background: var(--line); overflow: hidden; }.completion em, .progress em { display: block; height: 100%; border-radius: inherit; background: linear-gradient(90deg, color-mix(in srgb, var(--accent) 68%, var(--muted)), var(--accent)); transition: width var(--social-motion-layout) var(--social-ease); }
.edit-panel { position: relative; overflow: hidden; padding: 14px; display: grid; gap: 13px; animation: social-editor-in 260ms var(--social-ease) both; }.edit-panel::before { content: ''; position: absolute; top: 0; left: 14px; right: 14px; height: 1px; background: linear-gradient(90deg, transparent, color-mix(in srgb, var(--accent) 52%, transparent), transparent); }.edit-panel > header, .achievement-section > header, .activity-section > header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }.edit-panel h3, .achievement-section h3, .activity-section h3 { margin: 4px 0 0; font-size: 14px; }
.edit-panel .form-grid label, .edit-panel .privacy-grid label, .edit-panel .privacy-note { animation: social-editor-field-in var(--social-motion-surface) var(--social-ease) both; }.edit-panel .form-grid label:nth-child(2) { animation-delay: 20ms; }.edit-panel .form-grid label:nth-child(3) { animation-delay: 40ms; }.edit-panel .form-grid label:nth-child(4) { animation-delay: 60ms; }.edit-panel .privacy-grid label { animation-delay: 70ms; }.edit-panel .privacy-note { animation-delay: 90ms; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }.form-grid label { display: grid; gap: 6px; }.form-grid label.wide { grid-column: 1 / -1; }.form-grid label > span { color: var(--muted); font-size: 8px; }.field-error { color: var(--bad); font-size: 7px; }
input, textarea { width: 100%; box-sizing: border-box; border: 1px solid var(--line); border-radius: 8px; background: var(--workspace-control-bg, var(--panel)); color: var(--text); padding: 9px 10px; font: inherit; outline: none; }input:focus, textarea:focus { border-color: color-mix(in srgb, var(--accent) 45%, var(--line)); }
input, textarea { transition: border-color var(--social-motion-state) ease, background var(--social-motion-state) ease, box-shadow var(--social-motion-state) ease; }
input:hover, textarea:hover { border-color: color-mix(in srgb, var(--line-strong) 72%, var(--accent)); }
input:focus, textarea:focus { background: color-mix(in srgb, var(--workspace-control-bg, var(--panel)) 88%, var(--workspace-accent-wash)); }
.privacy-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }.privacy-grid label { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 10px; border: 1px solid var(--line); border-radius: 8px; transition: border-color var(--social-motion-state) ease, background var(--social-motion-state) ease; }.privacy-grid label:hover, .privacy-grid label:focus-within { border-color: color-mix(in srgb, var(--accent) 20%, var(--line-strong)); background: color-mix(in srgb, var(--workspace-accent-wash) 24%, transparent); }.privacy-grid label > span { display: grid; gap: 4px; }.privacy-grid b { font-size: 9px; }.privacy-grid small { color: var(--subtle); font-size: 7px; line-height: 1.4; }.privacy-grid input { position: relative; width: 30px; height: 18px; flex: 0 0 auto; appearance: none; border: 1px solid var(--line-strong); border-radius: 999px; background: color-mix(in srgb, var(--panel) 86%, transparent); cursor: pointer; transition: border-color var(--social-motion-state) ease, background var(--social-motion-state) ease, box-shadow var(--social-motion-state) ease; }.privacy-grid input::before { content: ''; position: absolute; top: 2px; left: 2px; width: 12px; height: 12px; border-radius: 50%; background: var(--muted); box-shadow: 0 1px 4px rgba(0,0,0,.16); transition: transform var(--social-motion-surface) var(--social-ease), background var(--social-motion-state) ease; }.privacy-grid input:checked { border-color: color-mix(in srgb, var(--accent) 42%, var(--line-strong)); background: color-mix(in srgb, var(--accent) 20%, var(--workspace-accent-wash)); }.privacy-grid input:checked::before { transform: translateX(12px); background: var(--accent-strong); }.privacy-grid input:focus-visible { box-shadow: var(--ds-focus-shadow, 0 0 0 3px color-mix(in srgb, var(--accent) 11%, transparent)); }
.privacy-note { margin: -3px 0 0; padding: 8px 10px; border-left: 2px solid var(--accent); background: var(--workspace-accent-wash); color: var(--muted); font-size: 8px; line-height: 1.5; }
.achievement-section, .activity-section { padding: 14px; }.achievement-section header strong { color: var(--accent-strong); font: 650 11px/1 ui-monospace, monospace; }
.achievement-filters { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 10px; }.achievement-filters button { border: 1px solid var(--line); border-radius: 999px; background: transparent; color: var(--subtle); padding: 5px 8px; cursor: pointer; font-size: 7px; }.achievement-filters button.active { color: var(--accent-strong); border-color: color-mix(in srgb, var(--accent) 36%, var(--line)); background: var(--workspace-accent-wash); }
.achievement-status { display: flex; gap: 6px; margin-top: 7px; }.achievement-status button { border: 0; background: transparent; color: var(--subtle); padding: 3px 0; cursor: pointer; font-size: 8px; }.achievement-status button.active { color: var(--accent-strong); text-decoration: underline; text-underline-offset: 3px; }
.achievement-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 8px; margin-top: 12px; }.achievement-grid article { min-height: 135px; display: grid; align-content: start; gap: 7px; padding: 12px; border: 1px solid var(--line); border-radius: 9px; opacity: .52; }.achievement-grid article.unlocked { opacity: 1; border-color: color-mix(in srgb, var(--accent) 28%, var(--line)); background: color-mix(in srgb, var(--workspace-accent-wash) 28%, transparent); }.badge-mark { display: flex; align-items: center; justify-content: space-between; color: var(--subtle); font: 650 7px/1 ui-monospace, monospace; }.badge-mark b { color: var(--accent-strong); font-size: 12px; }.achievement-grid h4 { margin: 0; font-size: 10px; }.achievement-grid p { margin: 0; color: var(--muted); font-size: 8px; line-height: 1.5; }.achievement-grid time { color: var(--subtle); font-size: 7px; }.progress { display: grid; grid-template-columns: 1fr auto; align-items: center; gap: 7px; }.progress small { color: var(--subtle); font-size: 7px; }
.activity-list { display: grid; margin-top: 8px; }.activity-list article { display: grid; grid-template-columns: 140px 1fr auto; gap: 9px; padding: 10px 0; border-bottom: 1px solid var(--line); }.activity-list span, .activity-list time { color: var(--subtle); font-size: 8px; }.activity-list p { margin: 0; color: var(--muted); font-size: 9px; }
.activity-filters { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 9px; }.activity-filters button { border: 1px solid var(--line); border-radius: 999px; background: transparent; color: var(--subtle); padding: 5px 8px; cursor: pointer; font-size: 7px; }.activity-filters button.active { color: var(--accent-strong); background: var(--workspace-accent-wash); }
.metric-card { padding: 14px; display: grid; grid-template-columns: 1fr auto; gap: 5px; }.metric-card > span { color: var(--subtle); font-size: 8px; }.metric-card strong { grid-row: 2; font-size: 28px; line-height: 1; }.metric-card small { grid-row: 2; align-self: end; color: var(--accent-strong); font: 650 7px/1 ui-monospace, monospace; }.metric-card p { grid-column: 1 / -1; margin: 5px 0 0; color: var(--muted); font-size: 8px; line-height: 1.5; }.metric-card.primary { background: color-mix(in srgb, var(--workspace-accent-wash) 42%, var(--surface)); }
.privacy-summary { padding: 13px; display: grid; gap: 9px; }.privacy-summary > span { color: var(--subtle); font: 650 8px/1 ui-monospace, monospace; letter-spacing: .08em; }.privacy-summary div { display: flex; justify-content: space-between; gap: 8px; }.privacy-summary b { font-size: 8px; }.privacy-summary i { color: var(--subtle); font-size: 8px; font-style: normal; }.privacy-summary i.on { color: var(--accent-strong); }
.empty-state { padding: 16px; border: 1px dashed color-mix(in srgb, var(--line-strong) 62%, transparent); border-radius: 8px; background: color-mix(in srgb, var(--workspace-accent-wash) 22%, transparent); color: var(--subtle); font-size: 8px; text-align: center; }
@keyframes social-profile-in { from { opacity: .72; transform: translateY(5px); } to { opacity: 1; transform: translateY(0); } }
@keyframes social-toast-in { from { opacity: 0; transform: translateY(-8px) scale(.985); } to { opacity: 1; transform: translateY(0) scale(1); } }
@keyframes social-editor-in { from { opacity: 0; transform: translateY(-5px) scale(.995); } to { opacity: 1; transform: translateY(0) scale(1); } }
@keyframes social-editor-field-in { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }
@media (max-width: 980px) { .profile-grid { grid-template-columns: 1fr; }.profile-grid > aside { grid-template-columns: repeat(3, 1fr); }.privacy-summary { grid-column: 1 / -1; } }
@media (max-width: 700px) { .surface-alert { top: 64px; left: 12px; right: 12px; max-width: none; }.page-head { align-items: flex-start; flex-direction: column; }.page-head > button { min-height: 40px; }.identity-card { grid-template-columns: auto 1fr; }.completion { grid-column: 1 / -1; }.form-grid, .privacy-grid, .profile-grid > aside { grid-template-columns: 1fr; }.activity-list article { grid-template-columns: 1fr; }.achievement-filters, .activity-filters { flex-wrap: nowrap; overflow-x: auto; padding-bottom: 3px; }.achievement-filters button, .activity-filters button { flex: 0 0 auto; min-height: 34px; } }
@media (prefers-reduced-motion: reduce) {
  .profile-page, .profile-page * { scroll-behavior: auto !important; }
  .profile-page *, .profile-page *::before, .profile-page *::after { animation-duration: .001ms !important; animation-iteration-count: 1 !important; transition-duration: .001ms !important; }
}
</style>
