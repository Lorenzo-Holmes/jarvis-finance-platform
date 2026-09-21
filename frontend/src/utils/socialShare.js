function cleanPart(value, maxLength) {
  const cleaned = String(value || '')
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, '')
    .trim()
  return cleaned.length <= maxLength ? cleaned : `${cleaned.slice(0, Math.max(0, maxLength - 1))}…`
}

export function normalizeSharePayload({ title = 'JARVIS 研究分享', text = '', url = '' } = {}) {
  const safeUrl = cleanPart(url, 500)
  return {
    title: cleanPart(title, 100) || 'JARVIS 研究分享',
    text: cleanPart(text, 1200),
    url: /^https?:\/\//i.test(safeUrl) ? safeUrl : '',
  }
}

export function buildShareText(payload = {}) {
  const normalized = normalizeSharePayload(payload)
  return [normalized.title, normalized.text, normalized.url].filter(Boolean).join('\n\n')
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
  const normalized = normalizeSharePayload(payload)
  const text = buildShareText(normalized)
  const target = new URL('https://service.weibo.com/share/share.php')
  if (normalized.url) target.searchParams.set('url', normalized.url)
  target.searchParams.set('title', [normalized.title, normalized.text].filter(Boolean).join('｜'))
  const popup = window.open(target.toString(), '_blank', 'noopener,noreferrer,width=760,height=640')
  if (!popup) {
    await copyText(text)
    return { method: 'clipboard', copied: true }
  }
  return { method: 'weibo-intent', copied: false }
}

export async function shareToXiaohongshu(payload = {}) {
  const normalized = normalizeSharePayload(payload)
  const text = buildShareText(normalized)
  if (navigator?.share) {
    try {
      await navigator.share(normalized)
      return { method: 'web-share', copied: false }
    } catch (error) {
      if (error?.name === 'AbortError') return { method: 'cancelled', copied: false }
    }
  }
  await copyText(text)
  return { method: 'clipboard', copied: true }
}

export { copyText }
