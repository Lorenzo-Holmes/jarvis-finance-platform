<script setup>
import { computed } from 'vue'
import { renderMarkdown } from '../../utils/markdown'

const props = defineProps({
  content: { type: [String, Number], default: '' },
})

const rendered = computed(() => renderMarkdown(props.content))
</script>

<template>
  <div class="md-content" v-html="rendered"></div>
</template>

<style scoped>
.md-content { min-width: 0; overflow-wrap: anywhere; white-space: normal; }
.md-content :deep(> :first-child) { margin-top: 0; }
.md-content :deep(> :last-child) { margin-bottom: 0; }
.md-content :deep(h1), .md-content :deep(h2), .md-content :deep(h3), .md-content :deep(h4) {
  margin: 1em 0 .45em; color: var(--text); font-weight: 700; line-height: 1.4;
}
.md-content :deep(h1) { font-size: 1.35em; }
.md-content :deep(h2) { font-size: 1.2em; }
.md-content :deep(h3), .md-content :deep(h4) { font-size: 1.08em; }
.md-content :deep(p), .md-content :deep(ul), .md-content :deep(ol), .md-content :deep(blockquote), .md-content :deep(pre) {
  margin: .65em 0;
}
.md-content :deep(ul), .md-content :deep(ol) { padding-left: 1.5em; }
.md-content :deep(li + li) { margin-top: .28em; }
.md-content :deep(a) { color: var(--accent-strong); text-decoration: underline; text-underline-offset: 2px; }
.md-content :deep(.md-image-alt) { color: var(--muted); font-size: .92em; }
.md-content :deep(blockquote) { border-left: 2px solid var(--accent); padding: .15em 0 .15em .85em; color: var(--muted); }
.md-content :deep(code) {
  padding: .12em .38em; border: 1px solid var(--line); border-radius: 3px;
  background: var(--panel-raised, var(--surface)); color: var(--accent-strong);
  font-family: "IBM Plex Mono", "Noto Sans Mono CJK SC", monospace; font-size: .92em;
}
.md-content :deep(pre) { max-width: 100%; overflow: auto; padding: .8em .9em; border: 1px solid var(--line); background: var(--panel-raised, var(--surface)); }
.md-content :deep(pre code) { padding: 0; border: 0; background: transparent; color: inherit; }
.md-content :deep(hr) { border: 0; border-top: 1px solid var(--line); margin: 1em 0; }
.md-content :deep(table) { width: 100%; border-collapse: collapse; margin: .75em 0; font-size: .95em; }
.md-content :deep(th), .md-content :deep(td) { padding: .45em .6em; border: 1px solid var(--line); text-align: left; vertical-align: top; }
.md-content :deep(th) { color: var(--text); background: var(--panel-raised, var(--surface)); }
</style>
