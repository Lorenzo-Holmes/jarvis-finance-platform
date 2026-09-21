export function buildShareText({ title = 'JARVIS 研究分享', text = '', url = '' } = {}) {
  const body = String(text || '').trim()
  const link = String(url || '').trim()
  return [String(title || '').trim(), body, link].filter(Boolean).join('\n\n')
}

async function copyText(text) {
  if (navigator?.clipboard?.writeText) {
    await navigator.clipboard.writeText(text)
    return true
  }
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.select()
  const copied = document.execCommand('copy')
  textarea.remove()
  return copied
}

export async function shareToWeibo(payload = {}) {
  const text = buildShareText(payload)
  const target = new URL('https://service.weibo.com/share/share.php')
  if (payload.url) target.searchParams.set('url', payload.url)
  target.searchParams.set('title', [payload.title, payload.text].filter(Boolean).join('｜'))
  const popup = window.open(target.toString(), '_blank', 'noopener,noreferrer,width=760,height=640')
  if (!popup) {
    await copyText(text)
    return { method: 'clipboard', copied: true }
  }
  return { method: 'weibo-intent', copied: false }
}

export async function shareToXiaohongshu(payload = {}) {
  const text = buildShareText(payload)
  if (navigator?.share) {
    try {
      await navigator.share({ title: payload.title || 'JARVIS 研究分享', text: payload.text || '', url: payload.url || '' })
      return { method: 'web-share', copied: false }
    } catch (error) {
      if (error?.name === 'AbortError') return { method: 'cancelled', copied: false }
    }
  }
  await copyText(text)
  return { method: 'clipboard', copied: true }
}

export { copyText }
