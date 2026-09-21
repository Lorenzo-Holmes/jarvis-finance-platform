# R47 需求：浮层 Toast 反馈

问题：成功/错误提示当前插入文档流，会推动整个页面上下位移，操作连续性较差。

目标：提示改为右上角浮层 Toast，不影响页面布局；进入时轻微下滑淡入，材质与 Workspace 一致。

验收：Toast fixed 定位、不会造成 layout shift；error/notice 保持可区分；移动端宽度自适应。
