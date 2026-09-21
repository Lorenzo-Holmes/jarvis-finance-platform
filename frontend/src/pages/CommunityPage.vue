<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/client'
import { shareToWeibo, shareToXiaohongshu } from '../utils/socialShare'

const tabs = [
  { key: 'feed', label: '社区动态' },
  { key: 'groups', label: '研究小组' },
  { key: 'users', label: '用户发现' },
  { key: 'messages', label: '私信' },
]
const activeTab = ref('feed')
const loading = ref(false)
const error = ref('')
const notice = ref('')

const feed = ref([])
const postText = ref('')
const groups = ref([])
const groupQuery = ref('')
const selectedGroup = ref(null)
const groupPosts = ref([])
const groupPostText = ref('')
const newGroup = ref({ name: '', description: '', visibility: 'OPEN' })
const inviteQuery = ref('')
const inviteCandidates = ref([])

const userQuery = ref('')
const users = ref([])
const selectedUser = ref(null)
const selectedUserActivity = ref([])

const conversations = ref([])
const messageUnreadCount = ref(0)
const selectedPartner = ref(null)
const thread = ref([])
const messageText = ref('')

const selectedGroupRole = computed(() => selectedGroup.value?.role || '')
const selectedGroupJoined = computed(() => Boolean(selectedGroup.value?.joined))

function responseData(response) { return response?.data ?? response }
function pageItems(response) { return responseData(response)?.items || [] }

function setNotice(value) {
  notice.value = value
  window.setTimeout(() => { if (notice.value === value) notice.value = '' }, 2600)
}

async function run(task) {
  loading.value = true
  error.value = ''
  try { return await task() } catch (e) { error.value = e?.message || String(e); throw e } finally { loading.value = false }
}

async function loadFeed() {
  const response = await api.communityFeed(0, 30)
  feed.value = pageItems(response)
}

async function publishPost() {
  const content = postText.value.trim()
  if (!content) return
  await run(async () => {
    await api.communityCreatePost({ content })
    postText.value = ''
    await loadFeed()
    setNotice('动态已发布')
  })
}

async function loadGroups() {
  const response = await api.communityGroups(groupQuery.value.trim(), 0, 50)
  groups.value = pageItems(response)
}

async function createGroup() {
  if (!newGroup.value.name.trim()) return
  await run(async () => {
    const response = await api.communityCreateGroup({
      name: newGroup.value.name.trim(),
      description: newGroup.value.description.trim(),
      visibility: newGroup.value.visibility,
    })
    newGroup.value = { name: '', description: '', visibility: 'OPEN' }
    await loadGroups()
    await openGroup(responseData(response)?.id)
    setNotice('研究小组已创建')
  })
}

async function openGroup(groupId) {
  if (!groupId) return
  await run(async () => {
    const [detail, posts] = await Promise.all([
      api.communityGroup(groupId), api.communityGroupPosts(groupId, 0, 30).catch(() => null),
    ])
    selectedGroup.value = responseData(detail)
    groupPosts.value = posts ? pageItems(posts) : []
  })
}

async function joinGroup(groupId) {
  await run(async () => {
    await api.communityJoinGroup(groupId)
    await Promise.all([loadGroups(), openGroup(groupId)])
    setNotice('已加入研究小组')
  })
}

async function leaveGroup(groupId) {
  await run(async () => {
    await api.communityLeaveGroup(groupId)
    selectedGroup.value = null
    groupPosts.value = []
    await loadGroups()
    setNotice('已退出研究小组')
  })
}

async function publishGroupPost() {
  const content = groupPostText.value.trim()
  if (!content || !selectedGroup.value?.id) return
  await run(async () => {
    await api.communityCreateGroupPost(selectedGroup.value.id, { content })
    groupPostText.value = ''
    await openGroup(selectedGroup.value.id)
    setNotice('小组动态已发布')
  })
}

async function searchInviteUsers() {
  const query = inviteQuery.value.trim()
  if (!query) { inviteCandidates.value = []; return }
  const response = await api.socialUsers(query, 0, 8)
  inviteCandidates.value = pageItems(response).filter(item =>
    !(selectedGroup.value?.members || []).some(member => member.user?.id === item.id))
}

async function addGroupMember(userId) {
  if (!userId || !selectedGroup.value?.id) return
  await run(async () => {
    await api.communityAddMember(selectedGroup.value.id, userId)
    inviteQuery.value = ''
    inviteCandidates.value = []
    await openGroup(selectedGroup.value.id)
    setNotice('成员已加入')
  })
}

async function searchUsers() {
  await run(async () => {
    const response = await api.socialUsers(userQuery.value.trim(), 0, 30)
    users.value = pageItems(response)
  })
}

async function openUser(userId) {
  await run(async () => {
    const profile = await api.socialUser(userId)
    selectedUser.value = responseData(profile)
    selectedUserActivity.value = []
    if (selectedUser.value?.activityPublic) {
      try {
        const activity = await api.socialUserActivity(userId, 0, 20)
        selectedUserActivity.value = pageItems(activity)
      } catch (_) { /* private activity is represented by an empty list */ }
    }
  })
}

async function loadConversations() {
  const [response, unread] = await Promise.all([api.socialConversations(), api.socialMessageUnreadCount()])
  conversations.value = responseData(response) || []
  messageUnreadCount.value = Number(responseData(unread)?.count || 0)
}

async function openConversation(user) {
  if (!user?.id) return
  activeTab.value = 'messages'
  selectedPartner.value = user
  await run(async () => {
    const response = await api.socialThread(user.id)
    thread.value = responseData(response) || []
    await loadConversations()
  })
}

async function sendMessage() {
  const content = messageText.value.trim()
  if (!content || !selectedPartner.value?.id) return
  await run(async () => {
    await api.socialSendMessage(selectedPartner.value.id, content)
    messageText.value = ''
    const response = await api.socialThread(selectedPartner.value.id)
    thread.value = responseData(response) || []
    await loadConversations()
  })
}

async function switchTab(key) {
  activeTab.value = key
  error.value = ''
  if (key === 'feed' && !feed.value.length) await loadFeed()
  if (key === 'groups' && !groups.value.length) await loadGroups()
  if (key === 'users' && !users.value.length) await searchUsers()
  if (key === 'messages') await loadConversations()
}

async function share(post, platform) {
  const payload = {
    title: `${post.author?.displayName || 'JARVIS 用户'} · JARVIS 研究分享`,
    text: post.content,
    url: window.location.href,
  }
  const result = platform === 'weibo' ? await shareToWeibo(payload) : await shareToXiaohongshu(payload)
  if (result?.copied) setNotice(platform === 'weibo' ? '分享窗口未打开，文案已复制' : '小红书分享文案已复制')
}

function formatTime(value) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(date)
}

onMounted(async () => {
  await Promise.all([loadFeed(), loadGroups(), searchUsers(), loadConversations()])
})
</script>

<template>
  <section class="community-page">
    <header class="page-head">
      <div><span>JARVIS NETWORK</span><h2>研究社区</h2><p>围绕研究对象建立小组、公开观点和一对一连接。</p></div>
      <div class="head-status"><i></i><span>社交层与交易权限隔离</span></div>
    </header>

    <nav class="community-tabs" aria-label="社区功能">
      <button v-for="tab in tabs" :key="tab.key" type="button" :class="{ active: activeTab === tab.key }" @click="switchTab(tab.key)">
        {{ tab.label }}<span v-if="tab.key === 'messages' && messageUnreadCount" class="tab-badge">{{ messageUnreadCount > 99 ? '99+' : messageUnreadCount }}</span>
      </button>
    </nav>

    <div v-if="error" class="surface-alert error">{{ error }}</div>
    <div v-if="notice" class="surface-alert notice">{{ notice }}</div>

    <div v-if="activeTab === 'feed'" class="feed-layout">
      <main>
        <section class="composer-panel">
          <div class="section-label">PUBLIC NOTE</div>
          <textarea v-model="postText" maxlength="4000" placeholder="发布一条研究观察、反方证据或市场笔记…" rows="4"></textarea>
          <footer><span>{{ postText.length }}/4000</span><button type="button" :disabled="loading || !postText.trim()" @click="publishPost">发布动态</button></footer>
        </section>
        <section class="feed-list">
          <article v-for="post in feed" :key="post.id" class="post-card">
            <header>
              <button type="button" class="author" @click="openUser(post.author?.id); activeTab = 'users'">
                <span class="avatar"><img v-if="post.author?.avatarUrl" :src="post.author.avatarUrl" alt="" /><b v-else>{{ (post.author?.displayName || '?').slice(0, 1) }}</b></span>
                <span><strong>{{ post.author?.displayName || '匿名用户' }}</strong><small>{{ formatTime(post.createdAt) }}</small></span>
              </button>
              <span v-if="post.group" class="group-chip">{{ post.group.name }}</span>
            </header>
            <p>{{ post.content }}</p>
            <footer><button type="button" @click="share(post, 'weibo')">微博</button><button type="button" @click="share(post, 'xiaohongshu')">小红书</button></footer>
          </article>
          <div v-if="!feed.length && !loading" class="empty-state">还没有公开研究动态。</div>
        </section>
      </main>
      <aside class="network-rail">
        <div class="section-label">RESEARCH GROUPS</div>
        <button v-for="group in groups.slice(0, 6)" :key="group.id" type="button" @click="activeTab = 'groups'; openGroup(group.id)">
          <span><strong>{{ group.name }}</strong><small>{{ group.memberCount }} 成员 · {{ group.postCount }} 动态</small></span><i>→</i>
        </button>
      </aside>
    </div>

    <div v-else-if="activeTab === 'groups'" class="groups-layout">
      <aside class="group-directory">
        <div class="search-row group-search"><input v-model="groupQuery" placeholder="搜索研究小组" @keyup.enter="loadGroups" /><button type="button" @click="loadGroups">搜索</button></div>
        <section class="create-group">
          <div class="section-label">CREATE GROUP</div>
          <input v-model="newGroup.name" maxlength="80" placeholder="小组名称" />
          <textarea v-model="newGroup.description" maxlength="500" rows="3" placeholder="研究范围与协作说明"></textarea>
          <select v-model="newGroup.visibility"><option value="OPEN">开放加入</option><option value="CLOSED">仅邀请</option></select>
          <button type="button" :disabled="loading || !newGroup.name.trim()" @click="createGroup">创建研究小组</button>
        </section>
        <div class="group-list">
          <button v-for="group in groups" :key="group.id" type="button" :class="{ active: selectedGroup?.id === group.id }" @click="openGroup(group.id)">
            <span><strong>{{ group.name }}</strong><small>{{ group.visibility === 'OPEN' ? 'OPEN' : 'CLOSED' }} · {{ group.memberCount }} 人</small></span><i>{{ group.joined ? '已加入' : '查看' }}</i>
          </button>
        </div>
      </aside>
      <main v-if="selectedGroup" class="group-room">
        <header><div><span>{{ selectedGroup.visibility }}</span><h3>{{ selectedGroup.name }}</h3><p>{{ selectedGroup.description || '暂无小组简介。' }}</p></div><div class="group-actions"><button v-if="!selectedGroupJoined && selectedGroup.visibility === 'OPEN'" type="button" @click="joinGroup(selectedGroup.id)">加入</button><button v-else-if="selectedGroupJoined && selectedGroupRole !== 'OWNER'" type="button" @click="leaveGroup(selectedGroup.id)">退出</button></div></header>
        <div class="group-metrics"><div><span>成员</span><b>{{ selectedGroup.memberCount }}</b></div><div><span>动态</span><b>{{ selectedGroup.postCount }}</b></div><div><span>我的角色</span><b>{{ selectedGroup.role || '访客' }}</b></div></div>
        <section v-if="selectedGroupRole === 'OWNER'" class="invite-panel">
          <div class="invite-row"><input v-model="inviteQuery" placeholder="按昵称搜索成员" @keyup.enter="searchInviteUsers" /><button type="button" @click="searchInviteUsers">搜索</button></div>
          <div v-if="inviteCandidates.length" class="invite-candidates">
            <button v-for="candidate in inviteCandidates" :key="candidate.id" type="button" @click="addGroupMember(candidate.id)">
              <span class="avatar"><img v-if="candidate.avatarUrl" :src="candidate.avatarUrl" alt="" /><b v-else>{{ (candidate.displayName || '?').slice(0,1) }}</b></span>
              <span><strong>{{ candidate.displayName }}</strong><small>{{ candidate.signature || '添加为小组成员' }}</small></span><i>添加</i>
            </button>
          </div>
        </section>
        <section v-if="selectedGroupJoined" class="group-composer"><textarea v-model="groupPostText" maxlength="4000" rows="3" placeholder="在小组内发布研究记录…"></textarea><button type="button" @click="publishGroupPost">发布到小组</button></section>
        <div class="feed-list compact"><article v-for="post in groupPosts" :key="post.id" class="post-card"><header><button class="author" type="button" @click="openUser(post.author?.id); activeTab='users'"><span><strong>{{ post.author?.displayName }}</strong><small>{{ formatTime(post.createdAt) }}</small></span></button></header><p>{{ post.content }}</p></article><div v-if="!groupPosts.length" class="empty-state">当前没有可展示的小组动态。</div></div>
      </main>
      <main v-else class="empty-panel">从左侧选择一个研究小组。</main>
    </div>

    <div v-else-if="activeTab === 'users'" class="users-layout">
      <aside class="user-directory">
        <div class="search-row"><input v-model="userQuery" placeholder="搜索昵称" @keyup.enter="searchUsers" /><button type="button" @click="searchUsers">搜索</button></div>
        <button v-for="item in users" :key="item.id" type="button" :class="{ active: selectedUser?.id === item.id }" @click="openUser(item.id)"><span class="avatar"><img v-if="item.avatarUrl" :src="item.avatarUrl" alt="" /><b v-else>{{ (item.displayName || '?').slice(0,1) }}</b></span><span><strong>{{ item.displayName }}</strong><small>{{ item.signature || (item.profilePublic ? '暂无签名' : '资料未公开') }}</small></span></button>
      </aside>
      <main v-if="selectedUser" class="profile-preview">
        <header><span class="avatar large"><img v-if="selectedUser.avatarUrl" :src="selectedUser.avatarUrl" alt="" /><b v-else>{{ (selectedUser.displayName || '?').slice(0,1) }}</b></span><div><span>USER / {{ selectedUser.id }}</span><h3>{{ selectedUser.displayName }}</h3><p>{{ selectedUser.signature || '该用户未公开个人签名。' }}</p></div><button v-if="!selectedUser.self" type="button" @click="openConversation(selectedUser)">发私信</button></header>
        <section v-if="selectedUser.contactInfo" class="contact-card"><span>公开联系方式</span><strong>{{ selectedUser.contactInfo }}</strong></section>
        <section class="achievement-strip"><article v-for="badge in selectedUser.achievements || []" :key="badge.key"><span>ACHIEVEMENT</span><b>{{ badge.title }}</b><small>{{ badge.description }}</small></article><div v-if="!(selectedUser.achievements || []).length" class="empty-state">该用户没有公开成就。</div></section>
        <section class="activity-list"><div class="section-label">PUBLIC ACTIVITY</div><article v-for="item in selectedUserActivity" :key="item.id"><span>{{ item.type }}</span><p>{{ item.summary }}</p><small>{{ formatTime(item.createdAt) }}</small></article><div v-if="!selectedUserActivity.length" class="empty-state">没有可见动态。</div></section>
      </main>
      <main v-else class="empty-panel">选择一个用户查看公开资料。</main>
    </div>

    <div v-else class="messages-layout">
      <aside class="conversation-list"><button v-for="item in conversations" :key="item.partner.id" type="button" :class="{ active: selectedPartner?.id === item.partner.id }" @click="openConversation(item.partner)"><span class="avatar"><img v-if="item.partner.avatarUrl" :src="item.partner.avatarUrl" alt="" /><b v-else>{{ (item.partner.displayName || '?').slice(0,1) }}</b></span><span><strong>{{ item.partner.displayName }}</strong><small>{{ item.lastMessage?.content }}</small></span><i v-if="item.unreadCount">{{ item.unreadCount }}</i></button><div v-if="!conversations.length" class="empty-state">暂无私信会话。</div></aside>
      <main v-if="selectedPartner" class="message-room"><header><span>PRIVATE CHANNEL</span><h3>{{ selectedPartner.displayName }}</h3></header><div class="message-thread"><article v-for="message in thread" :key="message.id" :class="{ mine: message.mine }"><p>{{ message.content }}</p><small>{{ formatTime(message.createdAt) }}</small></article></div><footer><textarea v-model="messageText" maxlength="2000" rows="3" placeholder="发送站内私信…" @keydown.ctrl.enter.prevent="sendMessage"></textarea><button type="button" :disabled="!messageText.trim()" @click="sendMessage">发送</button></footer></main>
      <main v-else class="empty-panel">选择一个会话，或从“用户发现”中发起私信。</main>
    </div>
  </section>
</template>

<style scoped>
.community-page { display: grid; gap: 14px; min-height: 620px; color: var(--text); }
.page-head { display: flex; align-items: end; justify-content: space-between; gap: 18px; padding-bottom: 12px; border-bottom: 1px solid var(--line); }
.page-head > div:first-child > span, .section-label, .group-room > header span, .profile-preview > header div > span, .message-room > header span { color: var(--subtle); font: 650 8px/1.2 ui-monospace, monospace; letter-spacing: .1em; }
.page-head h2 { margin: 5px 0 4px; font-size: 24px; letter-spacing: -.025em; }
.page-head p { margin: 0; color: var(--muted); font-size: 11px; }
.head-status { display: flex; align-items: center; gap: 7px; color: var(--muted); font-size: 9px; }
.head-status i { width: 6px; height: 6px; border-radius: 50%; background: var(--ok); }
.community-tabs { display: inline-flex; justify-self: start; padding: 3px; border: 1px solid var(--line); border-radius: 9px; background: var(--surface); }
.community-tabs button { min-height: 31px; border: 0; border-radius: 6px; background: transparent; color: var(--muted); padding: 0 13px; cursor: pointer; font-size: 10px; }
.community-tabs button.active { color: var(--text); background: var(--workspace-accent-wash); }
.tab-badge { display: inline-grid; place-items: center; min-width: 15px; height: 15px; margin-left: 6px; padding: 0 3px; border-radius: 999px; background: var(--accent); color: var(--bg); font-size: 7px; }
.surface-alert { padding: 9px 11px; border: 1px solid var(--line); border-radius: 8px; font-size: 10px; }
.surface-alert.error { color: var(--bad); }.surface-alert.notice { color: var(--accent-strong); }
.feed-layout { display: grid; grid-template-columns: minmax(0, 1fr) 260px; gap: 14px; min-height: 0; }
.feed-layout > main, .group-room, .profile-preview, .message-room { min-width: 0; display: grid; align-content: start; gap: 12px; }
.composer-panel, .network-rail, .create-group, .group-directory, .group-room, .user-directory, .profile-preview, .conversation-list, .message-room, .empty-panel { border: 1px solid var(--line); border-radius: 11px; background: color-mix(in srgb, var(--surface) 92%, transparent); }
.composer-panel, .create-group { padding: 12px; display: grid; gap: 9px; }
textarea, input, select { width: 100%; box-sizing: border-box; border: 1px solid var(--line); border-radius: 8px; background: var(--workspace-control-bg, var(--panel)); color: var(--text); padding: 9px 10px; font: inherit; outline: none; }
textarea:focus, input:focus, select:focus { border-color: color-mix(in srgb, var(--accent) 45%, var(--line)); }
.composer-panel footer { display: flex; align-items: center; justify-content: space-between; color: var(--subtle); font-size: 8px; }
button { font: inherit; }
.composer-panel button, .create-group > button, .group-actions button, .group-composer button, .invite-row button, .search-row button, .profile-preview > header > button, .message-room > footer button { border: 1px solid var(--line-strong); border-radius: 7px; background: var(--workspace-accent-wash); color: var(--text); padding: 7px 11px; cursor: pointer; font-size: 9px; }
button:disabled { opacity: .45; cursor: not-allowed; }
.feed-list { display: grid; gap: 8px; }.feed-list.compact { padding: 0 12px 12px; }
.post-card { padding: 13px 14px; border: 1px solid var(--line); border-radius: 10px; background: color-mix(in srgb, var(--panel) 84%, transparent); }
.post-card > header { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.author { display: flex; align-items: center; gap: 9px; border: 0; background: transparent; color: inherit; padding: 0; cursor: pointer; text-align: left; }
.author > span:last-child { display: grid; gap: 3px; }.author strong { font-size: 10px; }.author small { color: var(--subtle); font-size: 8px; }
.avatar { width: 30px; height: 30px; flex: 0 0 auto; display: grid; place-items: center; overflow: hidden; border: 1px solid var(--line-strong); border-radius: 50%; background: var(--workspace-accent-wash); color: var(--accent-strong); }
.avatar.large { width: 58px; height: 58px; }.avatar img { width: 100%; height: 100%; object-fit: cover; }.avatar b { font-size: 10px; }
.group-chip { padding: 4px 7px; border: 1px solid var(--line); border-radius: 999px; color: var(--muted); font-size: 8px; }
.post-card > p { margin: 12px 0; white-space: pre-wrap; color: var(--text); font-size: 11px; line-height: 1.72; }
.post-card > footer { display: flex; gap: 7px; }.post-card > footer button { border: 0; background: transparent; color: var(--muted); cursor: pointer; padding: 4px 0; font-size: 8px; }
.network-rail { padding: 12px; align-self: start; display: grid; gap: 7px; }.network-rail > button { display: flex; align-items: center; justify-content: space-between; gap: 9px; padding: 9px 3px; border: 0; border-bottom: 1px solid var(--line); background: transparent; color: inherit; cursor: pointer; text-align: left; }.network-rail span { display: grid; gap: 4px; }.network-rail strong { font-size: 9px; }.network-rail small { color: var(--subtle); font-size: 8px; }
.groups-layout, .users-layout, .messages-layout { display: grid; grid-template-columns: 290px minmax(0, 1fr); gap: 14px; min-height: 520px; }
.group-directory, .user-directory, .conversation-list { min-height: 0; overflow: auto; }
.group-search { padding: 10px; border-bottom: 1px solid var(--line); }
.group-list, .user-directory, .conversation-list { display: grid; align-content: start; }
.group-list > button, .user-directory > button, .conversation-list > button { display: flex; align-items: center; gap: 9px; min-width: 0; padding: 10px 12px; border: 0; border-bottom: 1px solid var(--line); background: transparent; color: inherit; cursor: pointer; text-align: left; }
.group-list > button.active, .user-directory > button.active, .conversation-list > button.active { background: var(--workspace-accent-wash); }
.group-list > button > span, .user-directory > button > span:nth-child(2), .conversation-list > button > span:nth-child(2) { min-width: 0; flex: 1; display: grid; gap: 3px; }.group-list strong, .user-directory strong, .conversation-list strong { font-size: 9px; }.group-list small, .user-directory small, .conversation-list small { color: var(--subtle); font-size: 8px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.group-list i, .conversation-list i { color: var(--accent-strong); font-size: 8px; font-style: normal; }
.group-room > header, .profile-preview > header, .message-room > header { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; padding: 16px; border-bottom: 1px solid var(--line); }.group-room h3, .profile-preview h3, .message-room h3 { margin: 5px 0; font-size: 18px; }.group-room p, .profile-preview p { margin: 0; color: var(--muted); font-size: 10px; line-height: 1.6; }
.group-metrics { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; padding: 0 12px; }.group-metrics div { padding: 10px; border: 1px solid var(--line); border-radius: 8px; display: grid; gap: 5px; }.group-metrics span { color: var(--subtle); font-size: 8px; }.group-metrics b { font-size: 13px; }
.invite-row, .search-row { display: flex; gap: 7px; padding: 0 12px; }.invite-row input, .search-row input { flex: 1; }.group-composer { display: grid; gap: 7px; padding: 0 12px; }.group-composer button { justify-self: end; }
.invite-panel { display: grid; gap: 6px; }.invite-candidates { display: grid; margin: 0 12px; border: 1px solid var(--line); border-radius: 8px; overflow: hidden; }.invite-candidates > button { display: flex; align-items: center; gap: 8px; padding: 8px 9px; border: 0; border-bottom: 1px solid var(--line); background: var(--panel); color: inherit; cursor: pointer; text-align: left; }.invite-candidates > button:last-child { border-bottom: 0; }.invite-candidates > button > span:nth-child(2) { min-width: 0; flex: 1; display: grid; gap: 3px; }.invite-candidates strong { font-size: 9px; }.invite-candidates small { color: var(--subtle); font-size: 8px; }.invite-candidates i { color: var(--accent-strong); font-size: 8px; font-style: normal; }
.user-directory .search-row { padding: 10px; border-bottom: 1px solid var(--line); }
.profile-preview > header { justify-content: flex-start; }.profile-preview > header > div { flex: 1; }.contact-card { margin: 0 14px; padding: 10px; border: 1px solid var(--line); border-radius: 8px; display: grid; gap: 5px; }.contact-card span { color: var(--subtle); font-size: 8px; }.contact-card strong { font-size: 10px; }
.achievement-strip { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 8px; padding: 0 14px; }.achievement-strip article { padding: 11px; border: 1px solid var(--line); border-radius: 9px; display: grid; gap: 5px; }.achievement-strip span, .achievement-strip small { color: var(--subtle); font-size: 7px; }.achievement-strip b { font-size: 9px; }
.activity-list { display: grid; gap: 0; padding: 0 14px 14px; }.activity-list article { display: grid; grid-template-columns: 120px 1fr auto; gap: 8px; padding: 9px 0; border-bottom: 1px solid var(--line); }.activity-list article span, .activity-list article small { color: var(--subtle); font-size: 8px; }.activity-list article p { margin: 0; color: var(--muted); font-size: 9px; }
.message-room { grid-template-rows: auto minmax(280px, 1fr) auto; }.message-thread { padding: 14px; overflow: auto; display: flex; flex-direction: column; gap: 8px; }.message-thread article { max-width: 72%; align-self: flex-start; padding: 8px 10px; border: 1px solid var(--line); border-radius: 9px; background: var(--panel); }.message-thread article.mine { align-self: flex-end; background: var(--workspace-accent-wash); }.message-thread p { margin: 0 0 5px; font-size: 10px; line-height: 1.55; }.message-thread small { color: var(--subtle); font-size: 7px; }.message-room > footer { display: flex; gap: 8px; padding: 12px; border-top: 1px solid var(--line); }.message-room > footer textarea { flex: 1; }
.empty-state, .empty-panel { padding: 24px; color: var(--subtle); font-size: 9px; }.empty-panel { display: grid; place-items: center; }
@media (max-width: 980px) { .feed-layout { grid-template-columns: 1fr; }.network-rail { display: none; }.groups-layout, .users-layout, .messages-layout { grid-template-columns: 1fr; }.group-directory, .user-directory, .conversation-list { max-height: 320px; } }
</style>
