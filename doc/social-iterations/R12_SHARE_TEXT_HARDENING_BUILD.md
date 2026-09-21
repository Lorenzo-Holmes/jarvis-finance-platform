# R12 构建设计

`socialShare.js` 新增统一 normalize：标题 100、正文 1200、URL 500 字符；去除 C0 控制字符（保留换行/制表）；URL 仅允许 http/https。微博和 Web Share 都使用归一化 payload。
