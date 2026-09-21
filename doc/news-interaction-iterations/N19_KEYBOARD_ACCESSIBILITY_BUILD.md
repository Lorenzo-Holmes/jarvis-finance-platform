# N19 构建设计

- `feedSearchRef` + window keydown handler。
- 非输入状态按 `/` 聚焦；检索框内按 Esc 清空并失焦。
- onBeforeUnmount 移除 listener。
- NewsCenter/MarketNewsBoard 增加 focus-visible 与移动端 control sizing。
