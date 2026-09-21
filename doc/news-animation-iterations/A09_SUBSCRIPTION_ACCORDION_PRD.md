# A09 需求：订阅配置 Accordion 动画

问题：订阅配置使用 v-show 直接隐藏/显示，面板高度瞬时跳变。

目标：展开/收起同时过渡 opacity、短距离位移和 max-height，保留已有 draft state。
