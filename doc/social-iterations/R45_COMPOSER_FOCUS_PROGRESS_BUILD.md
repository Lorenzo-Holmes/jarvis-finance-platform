# R45 构建设计

Public composer、group composer、message compose 分别注入 `--composer-progress`。CSS 通过 `::after` 绘制 2px 底部进度线，focus-within 提高 border/background，并让进度线从低透明度切换到 accent。
