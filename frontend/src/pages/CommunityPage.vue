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
const feedPage = ref(0)
const feedHasMore = ref(false)
const postText = ref('')
const groups = ref([])
const groupQuery = ref('')
const selectedGroup = ref(null)
const editingGroup = ref(false)
const groupEdit = ref({ name: '', description: '', visibility: 'OPEN' })
const groupPosts = ref([])
const groupPostPage = ref(0)
const groupPostHasMore = ref(false)
const groupPostText = ref('')
const newGroup = ref({ name: '', description: '', visibility: 'OPEN' })
const inviteQuery = ref('')
const inviteCandidates = ref([])

const userQuery = ref('')
const users = ref([])
const usersPage = ref(0)
const usersHasMore = ref(false)
const selectedUser = ref(null)
const selectedUserActivity = ref([])

const conversations = ref([])
const messageUnreadCount = ref(0)
const selectedPartner = ref(null)
const thread = ref([])
const threadPage = ref(0)
const threadHasMore = ref(false)
const messageText = ref('')

let groupListSeq = 0
let groupDetailSeq = 0
let userSearchSeq = 0
let userDetailSeq = 0
let inviteSearchSeq = 0
let conversationSeq = 0

const selectedGroupRole = computed(() => selectedGroup.value?.role || '')
const selectedGroupJoined = computed(() => Boolean(selectedGroup.value?.joined))
const activeTabIndex = computed(() => Math.max(0, tabs.findIndex(tab => tab.key === activeTab.value)))

function responseData(response) { return response?.data ?? response }
function pageItems(response) { return responseData(response)?.items || [] }
function pageMeta(response) { return responseData(response) || {} }

function setNotice(value) {
  notice.value = value
  window.setTimeout(() => { if (notice.value === value) notice.value = '' }, 2600)
}

async function run(task) {
  loading.value = true
  error.value = ''
  try { return await task() } catch (e) { error.value = e?.message || String(e); throw e } finally { loading.value = false }
}

async function loadFeed(reset = true) {
  const nextPage = reset ? 0 : feedPage.value + 1
  const response = await api.communityFeed(nextPage, 20)
  const data = pageMeta(response)
  feed.value = reset ? pageItems(response) : [...feed.value, ...pageItems(response)]
  feedPage.value = Number(data.page || nextPage)
  feedHasMore.value = feedPage.value + 1 < Number(data.totalPages || 0)
}

async function publishPost() {
  const content = postText.value.trim()
  if (!content) return
  await run(async () => {
    await api.communityCreatePost({ content })
    postText.value = ''
    await loadFeed(true)
    setNotice('动态已发布')
  })
}

async function deletePost(post) {
  if (!post?.id || !post.mine) return
  await run(async () => {
    await api.communityDeletePost(post.id)
    if (selectedGroup.value?.id && post.groupId === selectedGroup.value.id) await openGroup(selectedGroup.value.id)
    await loadFeed(true)
    setNotice('动态已删除')
  })
}

async function loadGroups() {
  const seq = ++groupListSeq
  const response = await api.communityGroups(groupQuery.value.trim(), 0, 50)
  if (seq !== groupListSeq) return
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
  const seq = ++groupDetailSeq
  await run(async () => {
    const detail = await api.communityGroup(groupId)
    if (seq !== groupDetailSeq) return
    selectedGroup.value = responseData(detail)
    groupEdit.value = {
      name: selectedGroup.value?.name || '',
      description: selectedGroup.value?.description || '',
      visibility: selectedGroup.value?.visibility || 'OPEN',
    }
    await loadGroupPosts(groupId, true)
  })
}

async function loadGroupPosts(groupId = selectedGroup.value?.id, reset = true) {
  if (!groupId) return
  const nextPage = reset ? 0 : groupPostPage.value + 1
  try {
    const response = await api.communityGroupPosts(groupId, nextPage, 15)
    const data = pageMeta(response)
    groupPosts.value = reset ? pageItems(response) : [...groupPosts.value, ...pageItems(response)]
    groupPostPage.value = Number(data.page || nextPage)
    groupPostHasMore.value = groupPostPage.value + 1 < Number(data.totalPages || 0)
  } catch (_) {
    if (reset) groupPosts.value = []
    groupPostHasMore.value = false
  }
}

async function saveGroupEdit() {
  if (!selectedGroup.value?.id || !groupEdit.value.name.trim()) return
  await run(async () => {
    await api.communityUpdateGroup(selectedGroup.value.id, {
      name: groupEdit.value.name.trim(),
      description: groupEdit.value.description.trim(),
      visibility: groupEdit.value.visibility,
    })
    editingGroup.value = false
    await Promise.all([openGroup(selectedGroup.value.id), loadGroups()])
    setNotice('小组资料已更新')
  })
}

async function deleteGroup() {
  if (!selectedGroup.value?.id || selectedGroupRole.value !== 'OWNER') return
  if (!window.confirm(`确定删除研究小组「${selectedGroup.value.name}」？组内动态将同时删除。`)) return
  const groupId = selectedGroup.value.id
  await run(async () => {
    await api.communityDeleteGroup(groupId)
    selectedGroup.value = null
    groupPosts.value = []
    await loadGroups()
    setNotice('研究小组已删除')
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
  const seq = ++inviteSearchSeq
  const response = await api.socialUsers(query, 0, 8)
  if (seq !== inviteSearchSeq) return
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

async function removeGroupMember(userId) {
  if (!selectedGroup.value?.id || !userId) return
  await run(async () => {
    await api.communityRemoveMember(selectedGroup.value.id, userId)
    await openGroup(selectedGroup.value.id)
    setNotice('成员已移除')
  })
}

async function searchUsers(reset = true) {
  if (typeof reset !== 'boolean') reset = true
  const seq = ++userSearchSeq
  await run(async () => {
    const nextPage = reset ? 0 : usersPage.value + 1
    const response = await api.socialUsers(userQuery.value.trim(), nextPage, 20)
    if (seq !== userSearchSeq) return
    const data = pageMeta(response)
    users.value = reset ? pageItems(response) : [...users.value, ...pageItems(response)]
    usersPage.value = Number(data.page || nextPage)
    usersHasMore.value = usersPage.value + 1 < Number(data.totalPages || 0)
  })
}

async function openUser(userId) {
  const seq = ++userDetailSeq
  await run(async () => {
    const profile = await api.socialUser(userId)
    if (seq !== userDetailSeq) return
    selectedUser.value = responseData(profile)
    selectedUserActivity.value = []
    if (selectedUser.value?.activityPublic) {
      try {
        const activity = await api.socialUserActivity(userId, 0, 20)
        if (seq !== userDetailSeq) return
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
  const seq = ++conversationSeq
  activeTab.value = 'messages'
  selectedPartner.value = user
  await run(async () => {
    const response = await api.socialThread(user.id, 0, 40)
    if (seq !== conversationSeq) return
    const data = pageMeta(response)
    thread.value = pageItems(response)
    threadPage.value = Number(data.page || 0)
    threadHasMore.value = threadPage.value + 1 < Number(data.totalPages || 0)
    await loadConversations()
  })
}

async function loadOlderMessages() {
  if (!selectedPartner.value?.id || !threadHasMore.value) return
  await run(async () => {
    const nextPage = threadPage.value + 1
    const response = await api.socialThread(selectedPartner.value.id, nextPage, 40)
    const data = pageMeta(response)
    thread.value = [...pageItems(response), ...thread.value]
    threadPage.value = Number(data.page || nextPage)
    threadHasMore.value = threadPage.value + 1 < Number(data.totalPages || 0)
  })
}

async function sendMessage() {
  const content = messageText.value.trim()
  if (!content || !selectedPartner.value?.id) return
  await run(async () => {
    await api.socialSendMessage(selectedPartner.value.id, content)
    messageText.value = ''
    const response = await api.socialThread(selectedPartner.value.id, 0, 40)
    const data = pageMeta(response)
    thread.value = pageItems(response)
    threadPage.value = Number(data.page || 0)
    threadHasMore.value = threadPage.value + 1 < Number(data.totalPages || 0)
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

    <nav class="community-tabs" role="tablist" aria-label="社区功能" :style="{ '--tab-index': activeTabIndex }">
      <button v-for="tab in tabs" :key="tab.key" type="button" role="tab" :aria-selected="activeTab === tab.key" :class="{ active: activeTab === tab.key }" @click="switchTab(tab.key)">
        {{ tab.label }}<span v-if="tab.key === 'messages' && messageUnreadCount" class="tab-badge">{{ messageUnreadCount > 99 ? '99+' : messageUnreadCount }}</span>
      </button>
    </nav>

    <div v-if="error" class="surface-alert error" role="alert">{{ error }}</div>
    <div v-if="notice" class="surface-alert notice" role="status" aria-live="polite">{{ notice }}</div>

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
                <span class="avatar"><img v-if="post.author?.avatarUrl" :src="post.author.avatarUrl" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer" /><b v-else>{{ (post.author?.displayName || '?').slice(0, 1) }}</b></span>
                <span><strong>{{ post.author?.displayName || '匿名用户' }}</strong><small>{{ formatTime(post.createdAt) }}</small></span>
              </button>
              <span v-if="post.group" class="group-chip">{{ post.group.name }}</span>
            </header>
            <p>{{ post.content }}</p>
            <footer><button type="button" @click="share(post, 'weibo')">微博</button><button type="button" @click="share(post, 'xiaohongshu')">小红书</button><button v-if="post.mine" class="danger-link" type="button" @click="deletePost(post)">删除</button></footer>
          </article>
          <div v-if="!feed.length && !loading" class="empty-state">还没有公开研究动态。</div>
          <button v-if="feedHasMore" class="load-more" type="button" :disabled="loading" @click="loadFeed(false)">加载更早动态</button>
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
        <header><div v-if="!editingGroup"><span>{{ selectedGroup.visibility }}</span><h3>{{ selectedGroup.name }}</h3><p>{{ selectedGroup.description || '暂无小组简介。' }}</p></div><div v-else class="group-edit-fields"><input v-model="groupEdit.name" maxlength="80" /><textarea v-model="groupEdit.description" maxlength="500" rows="2"></textarea><select v-model="groupEdit.visibility"><option value="OPEN">OPEN</option><option value="CLOSED">CLOSED</option></select></div><div class="group-actions"><button v-if="selectedGroupRole === 'OWNER' && !editingGroup" type="button" @click="editingGroup = true">编辑</button><button v-if="selectedGroupRole === 'OWNER' && !editingGroup" class="danger-action" type="button" @click="deleteGroup">删除小组</button><button v-if="selectedGroupRole === 'OWNER' && editingGroup" type="button" @click="saveGroupEdit">保存</button><button v-if="selectedGroupRole === 'OWNER' && editingGroup" type="button" @click="editingGroup = false">取消</button><button v-if="!selectedGroupJoined && selectedGroup.visibility === 'OPEN'" type="button" @click="joinGroup(selectedGroup.id)">加入</button><button v-else-if="selectedGroupJoined && selectedGroupRole !== 'OWNER'" type="button" @click="leaveGroup(selectedGroup.id)">退出</button></div></header>
        <div class="group-metrics"><div><span>成员</span><b>{{ selectedGroup.memberCount }}</b></div><div><span>动态</span><b>{{ selectedGroup.postCount }}</b></div><div><span>我的角色</span><b>{{ selectedGroup.role || '访客' }}</b></div></div>
        <section v-if="selectedGroupRole === 'OWNER'" class="invite-panel">
          <div class="invite-row"><input v-model="inviteQuery" placeholder="按昵称搜索成员" @keyup.enter="searchInviteUsers" /><button type="button" @click="searchInviteUsers">搜索</button></div>
          <div v-if="inviteCandidates.length" class="invite-candidates">
            <button v-for="candidate in inviteCandidates" :key="candidate.id" type="button" @click="addGroupMember(candidate.id)">
              <span class="avatar"><img v-if="candidate.avatarUrl" :src="candidate.avatarUrl" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer" /><b v-else>{{ (candidate.displayName || '?').slice(0,1) }}</b></span>
              <span><strong>{{ candidate.displayName }}</strong><small>{{ candidate.signature || '添加为小组成员' }}</small></span><i>添加</i>
            </button>
          </div>
        </section>
        <section class="group-members-panel">
          <div class="section-label">MEMBERS</div>
          <div class="group-members-list">
            <article v-for="member in selectedGroup.members || []" :key="member.user?.id">
              <span class="avatar"><img v-if="member.user?.avatarUrl" :src="member.user.avatarUrl" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer" /><b v-else>{{ (member.user?.displayName || '?').slice(0,1) }}</b></span>
              <span><strong>{{ member.user?.displayName }}</strong><small>{{ member.role }}</small></span>
              <button v-if="selectedGroupRole === 'OWNER' && member.role !== 'OWNER'" type="button" @click="removeGroupMember(member.user?.id)">移除</button>
            </article>
          </div>
        </section>
        <section v-if="selectedGroupJoined" class="group-composer"><textarea v-model="groupPostText" maxlength="4000" rows="3" placeholder="在小组内发布研究记录…"></textarea><button type="button" @click="publishGroupPost">发布到小组</button></section>
        <div class="feed-list compact"><article v-for="post in groupPosts" :key="post.id" class="post-card"><header><button class="author" type="button" @click="openUser(post.author?.id); activeTab='users'"><span><strong>{{ post.author?.displayName }}</strong><small>{{ formatTime(post.createdAt) }}</small></span></button></header><p>{{ post.content }}</p><footer v-if="post.mine"><button class="danger-link" type="button" @click="deletePost(post)">删除</button></footer></article><div v-if="!groupPosts.length" class="empty-state">当前没有可展示的小组动态。</div><button v-if="groupPostHasMore" class="load-more" type="button" :disabled="loading" @click="loadGroupPosts(selectedGroup.id, false)">加载更早小组动态</button></div>
      </main>
      <main v-else class="empty-panel">从左侧选择一个研究小组。</main>
    </div>

    <div v-else-if="activeTab === 'users'" class="users-layout">
      <aside class="user-directory">
        <div class="search-row"><input v-model="userQuery" placeholder="搜索昵称" @keyup.enter="searchUsers" /><button type="button" @click="searchUsers">搜索</button></div>
        <button v-for="item in users" :key="item.id" type="button" :class="{ active: selectedUser?.id === item.id }" @click="openUser(item.id)"><span class="avatar"><img v-if="item.avatarUrl" :src="item.avatarUrl" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer" /><b v-else>{{ (item.displayName || '?').slice(0,1) }}</b></span><span><strong>{{ item.displayName }}</strong><small>{{ item.signature || (item.profilePublic ? '暂无签名' : '资料未公开') }}</small></span></button>
        <button v-if="usersHasMore" class="directory-load-more" type="button" :disabled="loading" @click="searchUsers(false)">加载更多用户</button>
      </aside>
      <main v-if="selectedUser" class="profile-preview">
        <header><span class="avatar large"><img v-if="selectedUser.avatarUrl" :src="selectedUser.avatarUrl" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer" /><b v-else>{{ (selectedUser.displayName || '?').slice(0,1) }}</b></span><div><span>USER / {{ selectedUser.id }}</span><h3>{{ selectedUser.displayName }}</h3><p>{{ selectedUser.signature || '该用户未公开个人签名。' }}</p></div><button v-if="!selectedUser.self" type="button" @click="openConversation(selectedUser)">发私信</button></header>
        <section v-if="selectedUser.contactInfo" class="contact-card"><span>公开联系方式</span><strong>{{ selectedUser.contactInfo }}</strong></section>
        <section class="achievement-strip"><article v-for="badge in selectedUser.achievements || []" :key="badge.key"><span>ACHIEVEMENT</span><b>{{ badge.title }}</b><small>{{ badge.description }}</small></article><div v-if="!(selectedUser.achievements || []).length" class="empty-state">该用户没有公开成就。</div></section>
        <section class="activity-list"><div class="section-label">PUBLIC ACTIVITY</div><article v-for="item in selectedUserActivity" :key="item.id"><span>{{ item.type }}</span><p>{{ item.summary }}</p><small>{{ formatTime(item.createdAt) }}</small></article><div v-if="!selectedUserActivity.length" class="empty-state">没有可见动态。</div></section>
      </main>
      <main v-else class="empty-panel">选择一个用户查看公开资料。</main>
    </div>

    <div v-else class="messages-layout">
      <aside class="conversation-list"><button v-for="item in conversations" :key="item.partner.id" type="button" :class="{ active: selectedPartner?.id === item.partner.id }" @click="openConversation(item.partner)"><span class="avatar"><img v-if="item.partner.avatarUrl" :src="item.partner.avatarUrl" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer" /><b v-else>{{ (item.partner.displayName || '?').slice(0,1) }}</b></span><span><strong>{{ item.partner.displayName }}</strong><small>{{ item.lastMessage?.content }}</small></span><i v-if="item.unreadCount">{{ item.unreadCount }}</i></button><div v-if="!conversations.length" class="empty-state">暂无私信会话。</div></aside>
      <main v-if="selectedPartner" class="message-room"><header><span>PRIVATE CHANNEL</span><h3>{{ selectedPartner.displayName }}</h3></header><div class="message-thread" role="log" aria-live="polite" aria-relevant="additions text"><button v-if="threadHasMore" class="load-older-messages" type="button" :disabled="loading" @click="loadOlderMessages">加载更早消息</button><article v-for="message in thread" :key="message.id" :class="{ mine: message.mine }"><p>{{ message.content }}</p><small>{{ formatTime(message.createdAt) }}<template v-if="message.mine"> · {{ message.readAt ? '已读' : '已发送' }}</template></small></article></div><footer><div class="message-compose"><textarea v-model="messageText" maxlength="2000" rows="3" :aria-label="`给 ${selectedPartner.displayName} 发送私信`" placeholder="发送站内私信…" @keydown.ctrl.enter.prevent="sendMessage" @keydown.meta.enter.prevent="sendMessage"></textarea><small>{{ messageText.length }}/2000 · Ctrl/⌘ + Enter 发送</small></div><button type="button" :disabled="!messageText.trim()" @click="sendMessage">发送</button></footer></main>
      <main v-else class="empty-panel">选择一个会话，或从“用户发现”中发起私信。</main>
    </div>
  </section>
</template>

<style scoped>
.community-page { --social-motion-press: var(--ds-motion-press, 100ms); --social-motion-state: var(--ds-motion-state, 160ms); --social-motion-surface: var(--ds-motion-surface, 220ms); --social-motion-layout: var(--ds-motion-layout, 340ms); --social-ease: var(--ds-ease, cubic-bezier(.22,1,.36,1)); display: grid; gap: 14px; min-height: 620px; color: var(--text); }
.page-head { display: flex; align-items: end; justify-content: space-between; gap: 18px; padding-bottom: 12px; border-bottom: 1px solid var(--line); }
.page-head > div:first-child > span, .section-label, .group-room > header span, .profile-preview > header div > span, .message-room > header span { color: var(--subtle); font: 650 8px/1.2 ui-monospace, monospace; letter-spacing: .1em; }
.page-head h2 { margin: 5px 0 4px; font-size: 24px; letter-spacing: -.025em; }
.page-head p { margin: 0; color: var(--muted); font-size: 11px; }
.head-status { display: flex; align-items: center; gap: 7px; color: var(--muted); font-size: 9px; }
.head-status i { width: 6px; height: 6px; border-radius: 50%; background: var(--ok); }
.community-tabs { position: relative; display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); justify-self: start; min-width: 360px; padding: 3px; border: 1px solid var(--line); border-radius: 9px; background: var(--surface); overflow: hidden; }
.community-tabs::before { content: ''; position: absolute; z-index: 0; inset: 3px auto 3px 3px; width: calc((100% - 6px) / 4); border: 1px solid color-mix(in srgb, var(--line-strong) 72%, transparent); border-radius: 6px; background: linear-gradient(180deg, color-mix(in srgb, var(--workspace-accent-wash) 82%, transparent), color-mix(in srgb, var(--workspace-accent-wash) 54%, transparent)); box-shadow: inset 0 1px 0 rgba(255,255,255,.035); transform: translateX(calc(var(--tab-index) * 100%)); transition: transform var(--social-motion-layout) var(--social-ease), background var(--social-motion-surface) ease; pointer-events: none; }
.community-tabs button { position: relative; z-index: 1; min-height: 31px; border: 0; border-radius: 6px; background: transparent; color: var(--muted); padding: 0 13px; cursor: pointer; font-size: 10px; }
.community-tabs button.active { color: var(--text); background: transparent; }
.tab-badge { display: inline-grid; place-items: center; min-width: 15px; height: 15px; margin-left: 6px; padding: 0 3px; border-radius: 999px; background: var(--accent); color: var(--bg); font-size: 7px; }
.surface-alert { padding: 9px 11px; border: 1px solid var(--line); border-radius: 8px; font-size: 10px; }
.surface-alert.error { color: var(--bad); }.surface-alert.notice { color: var(--accent-strong); }
.feed-layout { display: grid; grid-template-columns: minmax(0, 1fr) 260px; gap: 14px; min-height: 0; }
.feed-layout, .groups-layout, .users-layout, .messages-layout { animation: social-surface-in var(--social-motion-surface) var(--social-ease) both; }
.feed-layout > main, .group-room, .profile-preview, .message-room { min-width: 0; display: grid; align-content: start; gap: 12px; }
.composer-panel, .network-rail, .create-group, .group-directory, .group-room, .user-directory, .profile-preview, .conversation-list, .message-room, .empty-panel { border: 1px solid var(--line); border-radius: 11px; background: color-mix(in srgb, var(--surface) 92%, transparent); }
.composer-panel, .create-group { padding: 12px; display: grid; gap: 9px; }
textarea, input, select { width: 100%; box-sizing: border-box; border: 1px solid var(--line); border-radius: 8px; background: var(--workspace-control-bg, var(--panel)); color: var(--text); padding: 9px 10px; font: inherit; outline: none; }
textarea:focus, input:focus, select:focus { border-color: color-mix(in srgb, var(--accent) 45%, var(--line)); }
.composer-panel footer { display: flex; align-items: center; justify-content: space-between; color: var(--subtle); font-size: 8px; }
button { font: inherit; }
button { transition: color var(--social-motion-state) ease, background var(--social-motion-state) ease, border-color var(--social-motion-state) ease, box-shadow var(--social-motion-state) ease, transform var(--social-motion-press) ease; }
button:active:not(:disabled) { transform: scale(.98); }
button:focus-visible, textarea:focus-visible, input:focus-visible, select:focus-visible { outline: 0; box-shadow: var(--ds-focus-shadow, 0 0 0 3px color-mix(in srgb, var(--accent) 11%, transparent)); }
textarea, input, select { transition: border-color var(--social-motion-state) ease, background var(--social-motion-state) ease, box-shadow var(--social-motion-state) ease; }
textarea:hover, input:hover, select:hover { border-color: color-mix(in srgb, var(--line-strong) 72%, var(--accent)); }
textarea:focus, input:focus, select:focus { background: color-mix(in srgb, var(--workspace-control-bg, var(--panel)) 88%, var(--workspace-accent-wash)); }
.composer-panel button, .create-group > button, .group-actions button, .group-composer button, .invite-row button, .search-row button, .profile-preview > header > button, .message-room > footer button { border: 1px solid var(--line-strong); border-radius: 7px; background: var(--workspace-accent-wash); color: var(--text); padding: 7px 11px; cursor: pointer; font-size: 9px; }
button:disabled { opacity: .45; cursor: not-allowed; }
.feed-list { display: grid; gap: 8px; }.feed-list.compact { padding: 0 12px 12px; }
.post-card { padding: 13px 14px; border: 1px solid var(--line); border-radius: 10px; background: color-mix(in srgb, var(--panel) 84%, transparent); }
.post-card { animation: social-card-in var(--social-motion-surface) var(--social-ease) both; }
.post-card:nth-child(2) { animation-delay: 20ms; }.post-card:nth-child(3) { animation-delay: 40ms; }.post-card:nth-child(4) { animation-delay: 60ms; }.post-card:nth-child(5) { animation-delay: 80ms; }.post-card:nth-child(6) { animation-delay: 100ms; }
.post-card > header { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.author { display: flex; align-items: center; gap: 9px; border: 0; background: transparent; color: inherit; padding: 0; cursor: pointer; text-align: left; }
.author > span:last-child { display: grid; gap: 3px; }.author strong { font-size: 10px; }.author small { color: var(--subtle); font-size: 8px; }
.avatar { width: 30px; height: 30px; flex: 0 0 auto; display: grid; place-items: center; overflow: hidden; border: 1px solid var(--line-strong); border-radius: 50%; background: var(--workspace-accent-wash); color: var(--accent-strong); }
.avatar.large { width: 58px; height: 58px; }.avatar img { width: 100%; height: 100%; object-fit: cover; }.avatar b { font-size: 10px; }
.group-chip { padding: 4px 7px; border: 1px solid var(--line); border-radius: 999px; color: var(--muted); font-size: 8px; }
.post-card > p { margin: 12px 0; white-space: pre-wrap; color: var(--text); font-size: 11px; line-height: 1.72; }
.post-card > footer { display: flex; gap: 7px; }.post-card > footer button { border: 0; background: transparent; color: var(--muted); cursor: pointer; padding: 4px 0; font-size: 8px; }
.post-card > footer .danger-link { color: var(--bad); margin-left: auto; }
.network-rail { padding: 12px; align-self: start; display: grid; gap: 7px; }.network-rail > button { display: flex; align-items: center; justify-content: space-between; gap: 9px; padding: 9px 3px; border: 0; border-bottom: 1px solid var(--line); background: transparent; color: inherit; cursor: pointer; text-align: left; }.network-rail span { display: grid; gap: 4px; }.network-rail strong { font-size: 9px; }.network-rail small { color: var(--subtle); font-size: 8px; }
.groups-layout, .users-layout, .messages-layout { display: grid; grid-template-columns: 290px minmax(0, 1fr); gap: 14px; min-height: 520px; }
.group-directory, .user-directory, .conversation-list { min-height: 0; overflow: auto; }
.group-search { padding: 10px; border-bottom: 1px solid var(--line); }
.group-list, .user-directory, .conversation-list { display: grid; align-content: start; }
.group-list > button, .user-directory > button, .conversation-list > button { display: flex; align-items: center; gap: 9px; min-width: 0; padding: 10px 12px; border: 0; border-bottom: 1px solid var(--line); background: transparent; color: inherit; cursor: pointer; text-align: left; }
.group-list > button.active, .user-directory > button.active, .conversation-list > button.active { background: var(--workspace-accent-wash); }
.group-list > button > span, .user-directory > button > span:nth-child(2), .conversation-list > button > span:nth-child(2) { min-width: 0; flex: 1; display: grid; gap: 3px; }.group-list strong, .user-directory strong, .conversation-list strong { font-size: 9px; }.group-list small, .user-directory small, .conversation-list small { color: var(--subtle); font-size: 8px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.group-list i, .conversation-list i { color: var(--accent-strong); font-size: 8px; font-style: normal; }
.group-room > header, .profile-preview > header, .message-room > header { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; padding: 16px; border-bottom: 1px solid var(--line); }.group-room h3, .profile-preview h3, .message-room h3 { margin: 5px 0; font-size: 18px; }.group-room p, .profile-preview p { margin: 0; color: var(--muted); font-size: 10px; line-height: 1.6; }
.group-edit-fields { flex: 1; display: grid; gap: 7px; }
.group-actions .danger-action { color: var(--bad); }
.group-metrics { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; padding: 0 12px; }.group-metrics div { padding: 10px; border: 1px solid var(--line); border-radius: 8px; display: grid; gap: 5px; }.group-metrics span { color: var(--subtle); font-size: 8px; }.group-metrics b { font-size: 13px; }
.invite-row, .search-row { display: flex; gap: 7px; padding: 0 12px; }.invite-row input, .search-row input { flex: 1; }.group-composer { display: grid; gap: 7px; padding: 0 12px; }.group-composer button { justify-self: end; }
.invite-panel { display: grid; gap: 6px; }.invite-candidates { display: grid; margin: 0 12px; border: 1px solid var(--line); border-radius: 8px; overflow: hidden; }.invite-candidates > button { display: flex; align-items: center; gap: 8px; padding: 8px 9px; border: 0; border-bottom: 1px solid var(--line); background: var(--panel); color: inherit; cursor: pointer; text-align: left; }.invite-candidates > button:last-child { border-bottom: 0; }.invite-candidates > button > span:nth-child(2) { min-width: 0; flex: 1; display: grid; gap: 3px; }.invite-candidates strong { font-size: 9px; }.invite-candidates small { color: var(--subtle); font-size: 8px; }.invite-candidates i { color: var(--accent-strong); font-size: 8px; font-style: normal; }
.group-members-panel { display: grid; gap: 7px; padding: 0 12px; }.group-members-list { display: grid; border: 1px solid var(--line); border-radius: 8px; overflow: hidden; }.group-members-list article { display: flex; align-items: center; gap: 8px; padding: 8px 9px; border-bottom: 1px solid var(--line); }.group-members-list article:last-child { border-bottom: 0; }.group-members-list article > span:nth-child(2) { flex: 1; display: grid; gap: 2px; }.group-members-list strong { font-size: 9px; }.group-members-list small { color: var(--subtle); font-size: 7px; }.group-members-list button { border: 0; background: transparent; color: var(--bad); cursor: pointer; font-size: 8px; }
.user-directory .search-row { padding: 10px; border-bottom: 1px solid var(--line); }
.directory-load-more { justify-content: center !important; color: var(--accent-strong) !important; font-size: 8px; }
.profile-preview > header { justify-content: flex-start; }.profile-preview > header > div { flex: 1; }.contact-card { margin: 0 14px; padding: 10px; border: 1px solid var(--line); border-radius: 8px; display: grid; gap: 5px; }.contact-card span { color: var(--subtle); font-size: 8px; }.contact-card strong { font-size: 10px; }
.achievement-strip { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 8px; padding: 0 14px; }.achievement-strip article { padding: 11px; border: 1px solid var(--line); border-radius: 9px; display: grid; gap: 5px; }.achievement-strip span, .achievement-strip small { color: var(--subtle); font-size: 7px; }.achievement-strip b { font-size: 9px; }
.activity-list { display: grid; gap: 0; padding: 0 14px 14px; }.activity-list article { display: grid; grid-template-columns: 120px 1fr auto; gap: 8px; padding: 9px 0; border-bottom: 1px solid var(--line); }.activity-list article span, .activity-list article small { color: var(--subtle); font-size: 8px; }.activity-list article p { margin: 0; color: var(--muted); font-size: 9px; }
.message-room { grid-template-rows: auto minmax(280px, 1fr) auto; }.message-thread { padding: 14px; overflow: auto; display: flex; flex-direction: column; gap: 8px; }.message-thread article { max-width: 72%; align-self: flex-start; padding: 8px 10px; border: 1px solid var(--line); border-radius: 9px; background: var(--panel); }.message-thread article.mine { align-self: flex-end; background: var(--workspace-accent-wash); }.message-thread p { margin: 0 0 5px; font-size: 10px; line-height: 1.55; }.message-thread small { color: var(--subtle); font-size: 7px; }.message-room > footer { display: flex; gap: 8px; padding: 12px; border-top: 1px solid var(--line); }.message-compose { min-width: 0; flex: 1; display: grid; gap: 4px; }.message-compose small { color: var(--subtle); font-size: 7px; text-align: right; }
.load-older-messages { align-self: center; border: 1px solid var(--line); border-radius: 999px; background: transparent; color: var(--muted); padding: 6px 10px; cursor: pointer; font-size: 8px; }
.empty-state, .empty-panel { padding: 24px; color: var(--subtle); font-size: 9px; }.empty-panel { display: grid; place-items: center; }
.load-more { justify-self: center; border: 1px solid var(--line); border-radius: 999px; background: transparent; color: var(--muted); padding: 7px 13px; cursor: pointer; font-size: 8px; }
@keyframes social-surface-in { from { opacity: .72; transform: translateY(5px); } to { opacity: 1; transform: translateY(0); } }
@keyframes social-card-in { from { opacity: 0; transform: translateY(6px) scale(.995); } to { opacity: 1; transform: translateY(0) scale(1); } }
@media (max-width: 980px) { .feed-layout { grid-template-columns: 1fr; }.network-rail { display: none; }.groups-layout, .users-layout, .messages-layout { grid-template-columns: 1fr; }.group-directory, .user-directory, .conversation-list { max-height: 320px; } }
@media (max-width: 700px) {
  .page-head { align-items: flex-start; flex-direction: column; }
  .community-tabs { max-width: 100%; overflow-x: auto; scrollbar-width: none; }
  .community-tabs::-webkit-scrollbar { display: none; }
  .community-tabs button { min-height: 40px; flex: 0 0 auto; padding: 0 12px; }
  .community-tabs { display: inline-flex; min-width: 0; }
  .community-tabs::before { display: none; }
  .community-tabs button.active { background: var(--workspace-accent-wash); }
  .group-directory, .user-directory, .conversation-list { max-height: 250px; }
  .group-room > header, .profile-preview > header { flex-wrap: wrap; }
  .group-actions { display: flex; flex-wrap: wrap; gap: 6px; }
  .message-thread article { max-width: 88%; }
  .message-room > footer { align-items: stretch; flex-direction: column; }
  .message-room > footer button { min-height: 40px; }
}
@media (max-width: 620px) {
  .community-page { gap: 10px; }
  .composer-panel, .network-rail, .create-group, .group-directory, .group-room, .user-directory, .profile-preview, .conversation-list, .message-room, .empty-panel { border-radius: 8px; }
  .group-metrics { grid-template-columns: 1fr; }
  .post-card { padding: 11px; }
}
@media (prefers-reduced-motion: reduce) {
  .community-page, .community-page * { scroll-behavior: auto !important; }
  .community-page *, .community-page *::before, .community-page *::after { animation-duration: .001ms !important; animation-iteration-count: 1 !important; transition-duration: .001ms !important; }
}
</style>
