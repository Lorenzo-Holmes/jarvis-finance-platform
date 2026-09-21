# R57 需求：隐私开关视觉重构

问题：隐私设置仍使用浏览器默认 checkbox，与整体 Workspace Control 语言不一致，开/关状态的可读性也偏弱。

目标：将三个 checkbox 纯 CSS 重绘为 compact toggle；开启时 accent wash + knob 位移，关闭时保持低对比。

验收：仍使用原生 checkbox 语义和键盘操作；checked 状态清晰；focus-visible 可见；不引入自定义 JS。
