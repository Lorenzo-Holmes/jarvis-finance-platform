# R26 需求：小组目录批量组装

问题：目录中的每个小组都会单独查询成员数、动态数、OWNER 和当前用户 membership，20 个小组可放大为数十次 SQL。

目标：按当前分页批量读取统计、OWNER 与 membership，一页固定数量查询完成组装。

验收：目录字段不变；分页内成员数/动态数/OWNER/membership 均使用批量查询；不再逐组 count/find。
