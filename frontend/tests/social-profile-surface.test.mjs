import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { buildShareText, normalizeSharePayload } from '../src/utils/socialShare.js'

const here = path.dirname(fileURLToPath(import.meta.url))
const src = path.resolve(here, '../src')
const read = relative => fs.readFileSync(path.join(src, relative), 'utf8')

test('community and profile are first-class workspace modules', () => {
  const modules = read('analysis-os/data/modules.js')
  const app = read('App.vue')
  const tabs = read('composables/useWorkspaceTabs.js')
  assert.match(modules, /labelZh: '社区'/)
  assert.match(modules, /labelZh: '个人中心'/)
  assert.match(app, /workspaceRenderRoute === '社区'/)
  assert.match(app, /workspaceRenderRoute === '个人中心'/)
  assert.match(tabs, /'社区'/)
  assert.match(tabs, /'个人中心'/)
})
test('frontend client exposes social, group, messaging, profile and achievement APIs', () => {
  const client = read('api/client.js')
  for (const token of [
    'socialProfile:', 'updateSocialProfile:', 'socialAchievements:', 'socialUsers:',
    'communityFeed:', 'communityGroups:', 'communityCreateGroup:', 'communityCreatePost:', 'communityDeletePost:',
    'communityUpdateGroup:', 'communityDeleteGroup:', 'communityJoinGroup:', 'communityAddMember:', 'communityRemoveMember:', 'socialConversations:', 'socialThread:',
    'socialMessageUnreadCount:', 'socialSendMessage:',
  ]) assert.match(client, new RegExp(token))
})

test('community surface contains groups, user discovery, direct messages and explicit third-party share actions', () => {
  const page = read('pages/CommunityPage.vue')
  assert.match(page, /研究小组/)
  assert.match(page, /用户发现/)
  assert.match(page, /私信/)
  assert.match(page, /messageUnreadCount/)
  assert.match(page, /searchInviteUsers/)
  assert.match(page, /按昵称搜索成员/)
  assert.match(page, /feedHasMore/)
  assert.match(page, /加载更早动态/)
  assert.match(page, /groupPostHasMore/)
  assert.match(page, /加载更早小组动态/)
  assert.match(page, /usersHasMore/)
  assert.match(page, /加载更多用户/)
  assert.match(page, /@keydown\.meta\.enter\.prevent="sendMessage"/)
  assert.match(page, /message\.readAt \? '已读' : '已发送'/)
  assert.match(page, /threadHasMore/)
  assert.match(page, /加载更早消息/)
  assert.match(page, /删除小组/)
  assert.match(page, /groupDetailSeq/)
  assert.match(page, /userSearchSeq/)
  assert.match(page, /conversationSeq/)
  assert.match(page, /seq !== userDetailSeq/)
  assert.match(page, /shareToWeibo/)
  assert.match(page, /shareToXiaohongshu/)
})

test('profile surface exposes privacy switches and achievement progress', () => {
  const page = read('pages/ProfilePage.vue')
  assert.match(page, /form\.profilePublic/)
  assert.match(page, /form\.contactPublic/)
  assert.match(page, /form\.activityPublic/)
  assert.match(page, /研究成就/)
  assert.match(page, /连续登录/)
  assert.match(page, /avatarUrlValid/)
  assert.match(page, /previewAvatarUrl/)
  assert.match(page, /privacyExposureNote/)
  assert.match(page, /联系方式仍会单独公开/)
  assert.match(page, /achievementCategories/)
  assert.match(page, /categoryAchievements/)
  assert.match(page, /displayedAchievements/)
  assert.match(page, /IN_PROGRESS/)
  assert.match(page, /activityTypes/)
  assert.match(page, /displayedActivities/)
  assert.match(read('pages/CommunityPage.vue'), /role="tablist"/)
  assert.match(read('pages/CommunityPage.vue'), /role="log"/)
  assert.match(read('pages/ProfilePage.vue'), /aria-live="polite"/)
  assert.match(read('pages/CommunityPage.vue'), /@media \(max-width: 620px\)/)
  assert.match(read('pages/ProfilePage.vue'), /achievement-filters.*overflow-x: auto/s)
})

test('share text is deterministic and includes the explicit source link', () => {
  assert.equal(buildShareText({ title: '标题', text: '观点', url: 'https://example.test/a' }), '标题\n\n观点\n\nhttps://example.test/a')
})

test('share payload removes control characters, rejects unsafe links and bounds long text', () => {
  const normalized = normalizeSharePayload({ title: 'A\u0000B', text: 'x'.repeat(1400), url: 'javascript:alert(1)' })
  assert.equal(normalized.title, 'AB')
  assert.equal(normalized.url, '')
  assert.equal(normalized.text.length, 1200)
  assert.ok(normalized.text.endsWith('…'))
})

test('all social avatar images suppress referrers and decode lazily', () => {
  for (const relative of ['pages/CommunityPage.vue', 'pages/ProfilePage.vue']) {
    const tags = read(relative).match(/<img\b[^>]*>/g) || []
    assert.ok(tags.length > 0)
    for (const tag of tags) {
      assert.match(tag, /referrerpolicy="no-referrer"/)
      assert.match(tag, /decoding="async"/)
      assert.match(tag, /loading="lazy"/)
    }
  }
})

test('social surfaces inherit workspace motion tokens and respect reduced motion', () => {
  for (const relative of ['pages/CommunityPage.vue', 'pages/ProfilePage.vue']) {
    const source = read(relative)
    assert.match(source, /--social-motion-surface: var\(--ds-motion-surface/)
    assert.match(source, /prefers-reduced-motion: reduce/)
  }
})

test('social controls expose hover press and keyboard focus feedback', () => {
  const community = read('pages/CommunityPage.vue')
  const profile = read('pages/ProfilePage.vue')
  assert.match(community, /button:active:not\(:disabled\)/)
  assert.match(community, /button:focus-visible/)
  assert.match(profile, /button:active:not\(:disabled\)/)
  assert.match(profile, /input:focus-visible/)
})

test('social workspaces use restrained surface entry motion', () => {
  assert.match(read('pages/CommunityPage.vue'), /@keyframes social-surface-in/)
  assert.match(read('pages/CommunityPage.vue'), /post-card:nth-child\(6\)/)
  assert.match(read('pages/ProfilePage.vue'), /@keyframes social-profile-in/)
})

test('community tabs use a moving selection lens with mobile fallback', () => {
  const source = read('pages/CommunityPage.vue')
  assert.match(source, /activeTabIndex/)
  assert.match(source, /--tab-index/)
  assert.match(source, /\.community-tabs::before/)
  assert.match(source, /transform: translateX\(calc\(var\(--tab-index\) \* 100%\)\)/)
})

test('community composers expose focus-within and length progress feedback', () => {
  const source = read('pages/CommunityPage.vue')
  assert.match(source, /--composer-progress/)
  assert.match(source, /\.composer-panel:focus-within/)
  assert.match(source, /\.message-compose::after/)
})

test('post cards reveal actions through restrained hover and focus motion', () => {
  const source = read('pages/CommunityPage.vue')
  assert.match(source, /\.post-card:hover, \.post-card:focus-within/)
  assert.match(source, /\.post-card:hover > footer, \.post-card:focus-within > footer/)
  assert.match(source, /translateY\(-1px\)/)
})

test('surface alerts render as animated non-layout-shifting toasts', () => {
  for (const relative of ['pages/CommunityPage.vue', 'pages/ProfilePage.vue']) {
    const source = read(relative)
    assert.match(source, /\.surface-alert \{ position: fixed/)
    assert.match(source, /@keyframes social-toast-in/)
  }
})
