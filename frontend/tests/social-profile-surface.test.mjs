import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { buildShareText } from '../src/utils/socialShare.js'

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
    'communityFeed:', 'communityGroups:', 'communityCreateGroup:', 'communityCreatePost:',
    'communityUpdateGroup:', 'communityJoinGroup:', 'communityAddMember:', 'communityRemoveMember:', 'socialConversations:', 'socialThread:',
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
})

test('share text is deterministic and includes the explicit source link', () => {
  assert.equal(buildShareText({ title: '标题', text: '观点', url: 'https://example.test/a' }), '标题\n\n观点\n\nhttps://example.test/a')
})
