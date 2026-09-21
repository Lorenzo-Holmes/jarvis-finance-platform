# R28 构建设计

`updateProfile` 先归一化 displayName/avatar/signature/contact，再与实体当前值及三个 boolean 比较。无变化直接返回 profile；仅有真实差异才进入原保存链路。
