<script setup>
import { nextTick, onMounted, ref } from 'vue'
import { API_BASE, api } from '../api/client'
import LandingTerminalPreview from './common/LandingTerminalPreview.vue'

const emit = defineEmits(['logged-in'])
const mode = ref('login')           // login | register | reset
const email = ref('')
const password = ref('')
const displayName = ref('')
const verificationCode = ref('')
const codeSent = ref(false)
const codeSending = ref(false)
const notice = ref('')
const loading = ref(false)
const error = ref('')
const showPwd = ref(false)
const authOpen = ref(false)
const emailInput = ref(null)

async function openAuth() {
  if (mode.value !== 'login') setMode('login')
  authOpen.value = true
  await nextTick()
  emailInput.value?.focus()
}

function closeAuth() {
  if (loading.value) return
  authOpen.value = false
}

async function submit() {
  error.value = ''
  notice.value = ''
  if (!email.value || !password.value) {
    error.value = '请输入邮箱和密码'
    return
  }
  loading.value = true
  try {
    let res
    if (mode.value === 'reset') {
      if (!verificationCode.value.trim()) {
        error.value = '请先获取并填写密码重置验证码'
        return
      }
      res = await api.resetPassword(email.value, verificationCode.value.trim(), password.value)
      if (res.code === 200) {
        mode.value = 'login'
        password.value = ''
        verificationCode.value = ''
        codeSent.value = false
        notice.value = '密码已重置，请使用新密码登录'
      } else {
        error.value = res.message || '密码重置失败'
      }
      return
    }
    if (mode.value === 'register') {
      if (!verificationCode.value.trim()) {
        error.value = '请先获取并填写邮箱验证码'
        return
      }
      const verify = await api.confirmEmailVerification(email.value, verificationCode.value.trim())
      if (verify.code !== 200) {
        error.value = verify.message || '邮箱验证码验证失败'
        return
      }
      if (!displayName.value.trim()) displayName.value = email.value.split('@')[0]
      res = await api.register(email.value, password.value, displayName.value)
    } else {
      res = await api.login(email.value, password.value)
    }
    if (res.code === 200 && res.data?.user) {
      emit('logged-in', res.data.user)
    } else {
      error.value = res.message || '操作失败'
    }
  } catch (e) {
    error.value = String(e)
  } finally {
    loading.value = false
  }
}

async function sendVerificationCode() {
  error.value = ''
  notice.value = ''
  if (!email.value) {
    error.value = '请先输入邮箱'
    return
  }
  codeSending.value = true
  try {
    const res = mode.value === 'reset'
      ? await api.requestPasswordReset(email.value)
      : await api.sendEmailVerification(email.value)
    if (res.code === 200) {
      codeSent.value = true
      notice.value = mode.value === 'reset'
        ? '如果该邮箱已注册，重置验证码会发送至邮箱（有效期 10 分钟）'
        : '验证码已发送，请检查邮箱（有效期 10 分钟）'
    } else {
      error.value = res.message || '验证码发送失败'
    }
  } catch (e) {
    error.value = String(e)
  } finally {
    codeSending.value = false
  }
}

function githubLogin() {
  window.location.href = `${API_BASE}/api/auth/github/authorize`
}

function setMode(nextMode) {
  mode.value = nextMode
  error.value = ''
  notice.value = ''
  password.value = ''
  verificationCode.value = ''
  codeSent.value = false
}

function switchMode() {
  setMode(mode.value === 'login' ? 'register' : 'login')
}

onMounted(() => {
  const params = new URLSearchParams(window.location.search)
  if (params.get('oauth') === 'error') {
    error.value = 'GitHub 登录失败，请重试或使用邮箱登录'
    authOpen.value = true
  }
  if (params.get('oauth') === 'bind-required') {
    error.value = '该 GitHub 邮箱已注册，请先使用邮箱登录，再点击顶部“绑定 GitHub”'
    authOpen.value = true
  }
})
</script>

<template>
  <div class="landing-page">
    <div class="ambient ambient-one" aria-hidden="true"></div>
    <div class="ambient ambient-two" aria-hidden="true"></div>

    <header class="landing-header">
      <a class="landing-brand" href="/" aria-label="JARVIS 金融投研首页">
        <img src="/favicon.svg" alt="" />
        <span>JARVIS</span>
        <small>RESEARCH</small>
      </a>
      <div class="header-actions">
        <span class="secure-note">PRIVATE WORKSPACE</span>
        <button type="button" class="quiet-btn" @click="openAuth">登录</button>
      </div>
    </header>

    <main class="landing-main">
      <section class="hero-copy">
        <div class="eyebrow"><span></span> INTELLIGENCE FOR MARKETS</div>
        <h1>看清市场，<br /><em>再做判断。</em></h1>
        <p class="hero-description">实时行情、跨市场研究与 AI 分析，集中在一个工作台。</p>
        <div class="hero-actions">
          <button type="button" class="cta cta-primary" @click="openAuth">
            进入研究终端 <span aria-hidden="true">→</span>
          </button>
          <button type="button" class="cta cta-ghost" @click="githubLogin">GitHub 登录</button>
        </div>
        <div class="capability-row" aria-label="平台能力">
          <span>多市场</span><i></i><span>策略回测</span><i></i><span>AI 研究</span>
        </div>
      </section>

      <section class="preview-wrap" aria-label="产品界面预览">
        <div class="preview-kicker">LIVE RESEARCH WORKSPACE</div>
        <LandingTerminalPreview />
      </section>
    </main>

    <div class="landing-tape" aria-label="示意市场数据">
      <span class="tape-label">DEMO</span>
      <div><small>SSE</small><b>3,326.18</b><em class="up">+0.82%</em></div>
      <div><small>NASDAQ</small><b>18,398.45</b><em class="down">-0.21%</em></div>
      <div><small>GOLD</small><b>2,728.15</b><em class="up">+0.34%</em></div>
      <div><small>BTC/USD</small><b>67,420.32</b><em class="up">+2.31%</em></div>
      <span class="tape-end">ONE CONTEXT · EVERYWHERE</span>
    </div>

    <footer class="landing-footer">
      <span>JARVIS Research Terminal</span>
      <span>仅供研究参考，不构成投资建议</span>
    </footer>

    <div v-if="authOpen" class="auth-overlay" @click.self="closeAuth">
      <section class="auth-card" role="dialog" aria-modal="true" aria-labelledby="auth-title">
        <div class="auth-topline">
          <div class="auth-mini-brand">
            <img src="/favicon.svg" alt="" />
            <span>JARVIS ACCESS</span>
          </div>
          <button type="button" class="close-btn" aria-label="关闭登录窗口" @click="closeAuth">×</button>
        </div>

        <div class="auth-brand">
          <div class="auth-eyebrow">SECURE RESEARCH WORKSPACE</div>
          <h2 id="auth-title">{{ mode === 'login' ? '登录研究终端' : mode === 'reset' ? '重置访问密码' : '创建研究账户' }}</h2>
          <p>{{ mode === 'login' ? '继续你的市场研究工作区。' : mode === 'reset' ? '验证邮箱后设置新密码。' : '创建账户后自动开通模拟盘。' }}</p>
        </div>

        <form class="auth-form" @submit.prevent="submit">
          <div v-if="mode === 'register'" class="field">
            <label for="auth-display-name">昵称</label>
            <input id="auth-display-name" v-model="displayName" type="text" placeholder="如何称呼你" />
          </div>
          <div class="field">
            <label for="auth-email">邮箱</label>
            <input id="auth-email" ref="emailInput" v-model="email" type="email" placeholder="you@example.com" autocomplete="email" />
          </div>
          <div class="field">
            <label for="auth-password">密码</label>
            <div class="pwd-row">
              <input id="auth-password" v-model="password" :type="showPwd ? 'text' : 'password'"
                     :placeholder="mode === 'login' ? '请输入密码' : (mode === 'reset' ? '设置至少10位新密码' : '至少10位')"
                     :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" />
              <button type="button" class="eye" :aria-label="showPwd ? '隐藏密码' : '显示密码'" @click="showPwd = !showPwd">
                {{ showPwd ? '隐藏' : '显示' }}
              </button>
            </div>
          </div>
          <div v-if="mode !== 'login'" class="field">
            <label for="auth-code">{{ mode === 'reset' ? '密码重置验证码' : '邮箱验证码' }}</label>
            <div class="code-row">
              <input id="auth-code" v-model="verificationCode" inputmode="numeric" maxlength="6" placeholder="6位验证码" />
              <button type="button" class="btn code-btn" :disabled="codeSending" @click="sendVerificationCode">
                {{ codeSending ? '发送中...' : (codeSent ? '重新发送' : '获取验证码') }}
              </button>
            </div>
          </div>

          <div v-if="error" class="error" role="alert">{{ error }}</div>
          <div v-if="notice" class="notice" role="status" aria-live="polite">{{ notice }}</div>

          <button type="submit" class="btn primary big" :disabled="loading">
            {{ loading ? '处理中...' : (mode === 'login' ? '进入 JARVIS' : mode === 'reset' ? '重置密码' : '创建账户') }}
          </button>
        </form>

        <template v-if="mode !== 'reset'">
          <div class="oauth-divider"><span>或</span></div>
          <button type="button" class="btn github-btn" @click="githubLogin">使用 GitHub 登录 / 注册</button>

          <div class="switch-row">
            <span>{{ mode === 'login' ? '还没有账号？' : '已有账号？' }}</span>
            <button type="button" class="switch-link" @click="switchMode">{{ mode === 'login' ? '注册' : '去登录' }}</button>
            <button v-if="mode === 'login'" type="button" class="switch-link push-right" @click="setMode('reset')">忘记密码</button>
          </div>
        </template>
        <div v-else class="switch-row">
          <span>已经想起密码？</span>
          <button type="button" class="switch-link" @click="setMode('login')">返回登录</button>
        </div>
        <div class="hint">{{ mode === 'reset' ? '重置后旧登录凭证立即失效' : '注册自动开通 $100,000 模拟账户' }}</div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.landing-page {
  position: relative;
  min-height: 100vh;
  overflow: hidden;
  color: var(--text);
  background:
    linear-gradient(rgba(255,255,255,.014) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255,255,255,.012) 1px, transparent 1px),
    #080a0b;
  background-size: 72px 72px, 72px 72px, auto;
}
.landing-page::before {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(90deg, #080a0b 0%, rgba(8,10,11,.94) 34%, rgba(8,10,11,.48) 66%, rgba(8,10,11,.84) 100%);
  pointer-events: none;
}
.ambient { position: absolute; border-radius: 50%; filter: blur(1px); pointer-events: none; opacity: .72; }
.ambient-one { width: 760px; height: 760px; right: -270px; top: -390px; background: radial-gradient(circle, rgba(211,173,91,.12), transparent 66%); animation: ambient-drift 16s ease-in-out infinite; }
.ambient-two { width: 520px; height: 520px; left: 22%; bottom: -390px; background: radial-gradient(circle, rgba(211,173,91,.08), transparent 68%); animation: ambient-drift 20s ease-in-out infinite reverse; }
.landing-header,
.landing-main,
.landing-tape,
.landing-footer { position: relative; z-index: 2; width: min(1500px, calc(100% - 64px)); margin-inline: auto; }
.landing-header { height: 78px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid rgba(255,255,255,.08); }
.landing-brand { display: inline-flex; align-items: center; gap: 9px; color: var(--text); text-decoration: none; font-size: 14px; font-weight: 700; letter-spacing: .07em; }
.landing-brand img { width: 27px; height: 27px; }
.landing-brand small { color: #686e71; font-size: 8px; font-weight: 650; letter-spacing: .18em; border-left: 1px solid #303436; padding-left: 9px; }
.header-actions { display: flex; align-items: center; gap: 18px; }
.secure-note { color: #555b5e; font-size: 8px; letter-spacing: .16em; }
.quiet-btn { color: #c6c9c8; background: transparent; border: 1px solid #323638; border-radius: 4px; padding: 7px 15px; font-size: 11px; cursor: pointer; }
.quiet-btn:hover { color: #fff; border-color: #5c5f5f; }
.landing-main { min-height: calc(100vh - 188px); display: grid; grid-template-columns: minmax(340px, .78fr) minmax(620px, 1.38fr); align-items: center; gap: clamp(44px, 5vw, 92px); padding: 68px 0 56px; }
.hero-copy { align-self: center; max-width: 570px; }
.eyebrow { display: flex; align-items: center; gap: 10px; color: #7e8385; font-size: 9px; letter-spacing: .19em; }
.eyebrow span { width: 26px; height: 1px; background: #ba9650; }
.hero-copy h1 { margin: 24px 0 20px; color: #f5f2ea; font-size: clamp(58px, 6.2vw, 102px); line-height: .93; font-weight: 520; letter-spacing: -.065em; }
.hero-copy h1 em { color: #d9b76e; font-style: normal; }
.hero-description { max-width: 470px; margin: 0; color: #989d9e; font-size: clamp(14px, 1.1vw, 17px); line-height: 1.8; }
.hero-actions { display: flex; align-items: center; gap: 10px; margin-top: 32px; }
.cta { min-height: 42px; border-radius: 4px; padding: 0 18px; font-size: 12px; font-weight: 600; cursor: pointer; transition: transform .16s ease, background .16s ease, border-color .16s ease, color .16s ease; }
.cta:hover { transform: translateY(-1px); }
.cta-primary { color: #17130b; background: #d9b76e; border: 1px solid #d9b76e; }
.cta-primary:hover { background: #e6c77f; border-color: #e6c77f; }
.cta-primary span { margin-left: 16px; }
.cta-ghost { color: #c8ccca; background: rgba(255,255,255,.015); border: 1px solid #35393b; }
.cta-ghost:hover { color: #fff; border-color: #555a5c; background: rgba(255,255,255,.035); }
.capability-row { display: flex; align-items: center; gap: 11px; margin-top: 30px; color: #606669; font-size: 9px; letter-spacing: .04em; }
.capability-row i { width: 2px; height: 2px; border-radius: 50%; background: #7b6742; }
.preview-wrap { min-width: 0; position: relative; justify-self: end; width: 100%; }
.preview-kicker { margin: 0 0 12px 8px; color: #565c5f; font-size: 8px; letter-spacing: .18em; text-align: right; }
.landing-tape { display: grid; grid-template-columns: auto repeat(4, minmax(130px, 1fr)) auto; align-items: center; gap: 0; min-height: 52px; border-top: 1px solid rgba(255,255,255,.08); border-bottom: 1px solid rgba(255,255,255,.06); }
.tape-label { padding-right: 18px; color: #917645; font-size: 8px; letter-spacing: .15em; }
.landing-tape div { display: flex; align-items: baseline; gap: 9px; padding: 0 16px; border-left: 1px solid rgba(255,255,255,.055); font-variant-numeric: tabular-nums; }
.landing-tape small { color: #6c7274; font-size: 8px; }
.landing-tape b { color: #d3d5d2; font-size: 10px; font-weight: 600; }
.landing-tape em { font-size: 8px; font-style: normal; }
.up { color: #47bf83; }
.down { color: #e06b6b; }
.tape-end { justify-self: end; color: #454a4d; font-size: 7px; letter-spacing: .15em; }
.landing-footer { min-height: 58px; display: flex; align-items: center; justify-content: space-between; gap: 20px; color: #4f5558; font-size: 8px; letter-spacing: .04em; }

.auth-overlay { position: fixed; inset: 0; z-index: 50; display: grid; place-items: center; padding: 24px; background: rgba(3,4,5,.76); backdrop-filter: blur(14px); animation: overlay-in .18s ease both; }
.auth-card { width: min(100%, 420px); max-height: calc(100vh - 48px); overflow-y: auto; background: #101314; border: 1px solid #34383a; border-radius: 8px; padding: 18px 26px 24px; box-shadow: 0 32px 100px rgba(0,0,0,.58); animation: card-in .2s cubic-bezier(.2,.7,.2,1) both; }
.auth-topline { display: flex; align-items: center; justify-content: space-between; padding-bottom: 15px; border-bottom: 1px solid #262a2c; }
.auth-mini-brand { display: flex; align-items: center; gap: 7px; color: #8f9495; font-size: 8px; font-weight: 650; letter-spacing: .13em; }
.auth-mini-brand img { width: 17px; height: 17px; }
.close-btn { width: 28px; height: 28px; border: 0; background: transparent; color: #72787a; font-size: 20px; line-height: 1; cursor: pointer; }
.close-btn:hover { color: #fff; }
.auth-brand { margin: 25px 0 22px; }
.auth-eyebrow { color: #786a4b; font-size: 8px; letter-spacing: .15em; }
.auth-brand h2 { color: #f2efe7; font-size: 22px; font-weight: 600; letter-spacing: -.025em; margin: 7px 0 5px; }
.auth-brand p { color: #72787a; font-size: 11px; margin: 0; }
.auth-form { display: flex; flex-direction: column; gap: 14px; }
.field label { display: block; color: var(--muted); font-size: 12px; margin-bottom: 6px; }
.field input {
  width: 100%; min-height: 42px; background: #0b0e0f; border: 1px solid #35393b; color: var(--text);
  border-radius: 4px; padding: 10px 12px; font-size: 13px;
}
.field input:focus { outline: none; border-color: #72684f; background: #141619; }
.pwd-row { position: relative; }
.code-row { display: flex; gap: 8px; }
.code-row input { flex: 1; min-width: 0; }
.code-btn { white-space: nowrap; padding: 10px 12px; font-size: 12px; }
.eye {
  position: absolute; right: 8px; top: 50%; transform: translateY(-50%); padding: 5px 6px;
  background: none; border: none; color: #777d7f; cursor: pointer; font-size: 10px;
}
.btn { border-radius: 4px; cursor: pointer; }
.btn.primary.big { margin-top: 3px; min-height: 43px; padding: 10px; color: #17130b; background: #d9b76e; border: 1px solid #d9b76e; font-size: 13px; font-weight: 650; }
.btn.primary.big:hover:not(:disabled) { background: #e6c77f; border-color: #e6c77f; }
.error { color: #ee8582; background: rgba(224,94,94,.08); border: 1px solid rgba(224,94,94,.16); border-radius: 4px; padding: 8px 10px; font-size: 11px; }
.notice { color: #65c996; background: rgba(78,190,132,.07); border: 1px solid rgba(78,190,132,.14); border-radius: 4px; padding: 8px 10px; font-size: 11px; }
.oauth-divider { display: flex; align-items: center; gap: 10px; color: var(--subtle); font-size: 11px; margin: 20px 0 10px; }
.oauth-divider::before, .oauth-divider::after { content: ''; height: 1px; background: var(--line); flex: 1; }
.github-btn { width: 100%; min-height: 41px; padding: 9px 11px; color: #cbd0ce; border: 1px solid #34383a; background: #151819; font-size: 12px; }
.github-btn:hover { border-color: #555a60; background: #222529; }
.switch-row { display: flex; align-items: center; gap: 7px; color: #747a7c; font-size: 11px; margin-top: 16px; }
.switch-link { border: 0; background: transparent; color: #d8b970; cursor: pointer; padding: 0; font-size: inherit; }
.push-right { margin-left: auto; color: #858b8d; }
.hint { color: #51575a; font-size: 9px; margin-top: 8px; }
@keyframes overlay-in { from { opacity: 0; } }
@keyframes card-in { from { opacity: 0; transform: translateY(10px) scale(.99); } }
@keyframes ambient-drift { 0%, 100% { transform: translate3d(0,0,0) scale(1); } 50% { transform: translate3d(-28px,20px,0) scale(1.05); } }

@media (max-width: 1180px) {
  .landing-main { grid-template-columns: minmax(300px, .72fr) minmax(520px, 1.28fr); gap: 40px; }
  .landing-tape { grid-template-columns: auto repeat(4, 1fr); }
  .tape-end { display: none; }
}
@media (max-width: 900px) {
  .landing-header, .landing-main, .landing-tape, .landing-footer { width: min(100% - 36px, 760px); }
  .landing-main { grid-template-columns: 1fr; min-height: auto; padding: 64px 0 52px; gap: 58px; }
  .hero-copy { max-width: 640px; }
  .hero-copy h1 { font-size: clamp(58px, 12vw, 88px); }
  .preview-wrap { justify-self: stretch; }
  .landing-tape { grid-template-columns: auto repeat(2, 1fr); padding: 8px 0; }
  .landing-tape div:nth-of-type(n+3) { display: none; }
}
@media (max-width: 560px) {
  .landing-header, .landing-main, .landing-tape, .landing-footer { width: calc(100% - 28px); }
  .landing-header { height: 66px; }
  .secure-note, .landing-brand small { display: none; }
  .landing-main { padding-top: 50px; gap: 44px; }
  .eyebrow { font-size: 8px; }
  .hero-copy h1 { margin-top: 20px; font-size: clamp(52px, 16vw, 76px); }
  .hero-description { font-size: 13px; line-height: 1.7; }
  .hero-actions { align-items: stretch; flex-direction: column; max-width: 240px; }
  .capability-row { margin-top: 24px; }
  .preview-kicker { text-align: left; margin-left: 0; }
  .landing-tape { grid-template-columns: auto 1fr; }
  .landing-tape div:nth-of-type(n+2) { display: none; }
  .landing-footer { min-height: 70px; align-items: flex-start; justify-content: center; flex-direction: column; gap: 4px; }
  .auth-overlay { align-items: end; padding: 0; }
  .auth-card { width: 100%; max-height: 92vh; border-radius: 10px 10px 0 0; padding: 18px 20px 24px; }
}
</style>
