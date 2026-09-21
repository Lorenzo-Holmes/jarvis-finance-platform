# R42 需求：统一控件交互反馈

问题：社区和个人中心的按钮/输入反馈不一致，hover、press、focus 层级弱，导致界面“能用但不精致”。

目标：所有核心按钮、输入框、筛选器具备一致 hover / press / focus-visible 反馈；不改变业务逻辑。

验收：按钮按压有轻微 scale；hover 有材质与边框变化；键盘焦点使用 accent focus ring；disabled 不参与 press。
