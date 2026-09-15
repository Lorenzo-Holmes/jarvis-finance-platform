import MarkdownIt from 'markdown-it'

function isSafeLink(destination) {
  const value = String(destination || '').trim()
  const compact = value.replace(/[\u0000-\u0020\u007f]+/g, '')
  if (/^(https?:\/\/|mailto:)/i.test(compact)) return true
  if (/^[a-z][a-z\d+.-]*:/i.test(compact) || compact.startsWith('//')) return false
  return true
}

const markdown = new MarkdownIt({
  html: false,
  linkify: false,
  breaks: true,
  typographer: false,
  validateLink: isSafeLink,
})

const renderLinkOpen = markdown.renderer.rules.link_open
  || ((tokens, index, options, environment, renderer) => renderer.renderToken(tokens, index, options))

markdown.renderer.rules.link_open = (tokens, index, options, environment, renderer) => {
  const href = tokens[index].attrGet('href') || ''
  if (/^https?:\/\//i.test(href)) {
    tokens[index].attrSet('target', '_blank')
    tokens[index].attrSet('rel', 'noopener noreferrer')
  }
  return renderLinkOpen(tokens, index, options, environment, renderer)
}

markdown.renderer.rules.image = (tokens, index) => {
  const label = markdown.utils.escapeHtml(tokens[index].content || '')
  return label ? `<span class="md-image-alt">[图片：${label}]</span>` : ''
}

export function renderMarkdown(content) {
  return markdown.render(String(content ?? ''))
}
