# R39 构建设计

CommunityPage 为 groups、group detail、users、user detail、invite search、conversation 各维护独立 sequence counter。发请求时捕获 token，await 后先比较 token，再写 ref。无需引入 AbortController，不改变 API client。
