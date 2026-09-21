# R40 需求：外部头像加载隐私与性能

问题：头像允许使用用户提供的外部 HTTPS URL。浏览器默认可能向第三方头像域发送当前页面 Referer，且社区长列表会同步解码大量不在视口内的图片。

目标：所有社交头像请求使用 `referrerpolicy=no-referrer`；图片异步解码；列表和资料头像允许浏览器 lazy load。

验收：社区/个人中心所有 `<img>` 均具备 no-referrer、async decoding、lazy loading，不改变头像 URL 业务语义。
