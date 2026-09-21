# R24 构建设计

`groupView(includeMembers=true)` 先截取最多 50 条 membership，收集 userId，调用 `userRepository.findAllById` 建立 map，再组装 member DTO。缺失用户记录安全跳过。
