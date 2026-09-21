# N01 需求：资讯排序模式前端契约

后端已经支持 `ranking=smart/latest`，但前端 API client 仍无法传递该模式，导致 UI 无法安全切换智能精选与时间流。

目标：扩展 `newsDaily`，默认保持 `smart`，并显式传递 ranking；不改变现有调用方默认行为。
