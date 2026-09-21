# R44 需求：社区 Tab 连续选中镜片

问题：Tab 当前通过 active 背景瞬时变化，切换缺少连续运动轨迹。

目标：增加一个在四个 Tab 间滑动的 selection lens，按钮本身保持文字层，形成更接近 Apple segmented control 的连续反馈。

验收：切换 Tab 时选中镜片平滑横移；按钮文字不抖动；移动端横向滚动时自动退化为普通 active 背景。
