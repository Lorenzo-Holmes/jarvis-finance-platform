# R44 构建设计

CommunityPage 计算 `activeTabIndex`，通过 CSS 变量传给 tablist。桌面端使用 `::before` 作为 25% 宽 selection lens，transform 由 index 驱动。700px 以下关闭 lens，保留原 active 背景保证横向滚动稳定。
