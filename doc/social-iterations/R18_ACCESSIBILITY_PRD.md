# R18 需求：社交页面可访问性语义

问题：社区 Tab、状态提示和私信消息流主要依赖视觉样式，读屏软件无法准确识别当前状态。

目标：补充 tablist/tab、aria-selected、alert/status、live message log 等语义。

验收：当前 Tab 可被辅助技术识别；错误即时播报；成功提示礼貌播报；新私信在 log 区域可感知。
