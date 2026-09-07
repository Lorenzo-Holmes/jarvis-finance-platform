from pathlib import Path


DOC_DIR = Path(__file__).resolve().parent
SOURCE = DOC_DIR / "DeepSeek大模型金融投研系统-PRD.md"
OUTPUT = DOC_DIR / "01_JARVIS金融投研平台_Software Requirement Specification_V1.0.md"


def build_markdown() -> str:
    source = SOURCE.read_text(encoding="utf-8")
    marker = "## 1. 产品概述"
    start = source.index(marker)
    prd_body = source[start:].strip()

    cover = """# 01 JARVIS金融投研平台

## Software Requirement Specification

| 项目 | 内容 |
| --- | --- |
| 文档版本 | V1.0 |
| 项目名称 | JARVIS金融投研平台（DeepSeek大模型金融投研系统） |
| 小组序号 | 01 |
| 文档日期 | 2026-09-07 |
| 文档状态 | 小组内部评审通过版本 |
| 编制依据 | 内部评审通过的 PRD、需求调研报告和项目实施计划 |

> 安全声明：本文档只包含需求、架构和技术选型。任何 API Key、OAuth Secret、内部服务令牌或数据库密码均不得写入本文档、前端代码或版本库。

---

## 目录

- [1. 产品概述](#1-产品概述)
- [2. 目标用户与使用场景](#2-目标用户与使用场景)
- [3. 竞品分析与差异化](#3-竞品分析与差异化)
- [4. 功能需求](#4-功能需求)
- [5. 非功能需求](#5-非功能需求)
- [6. 信息架构与页面规划](#6-信息架构与页面规划)
- [7. 视觉与交互设计规范](#7-视觉与交互设计规范)
- [8. 里程碑与迭代计划](#8-里程碑与迭代计划)
- [9. 数据需求](#9-数据需求)
- [10. 风险与开放问题](#10-风险与开放问题)
- [11. 项目总体架构与技术选型](#11-项目总体架构与技术选型)
- [12. SRS模板补充说明](#12-srs模板补充说明)
- [附录 A：需求追溯矩阵](#附录-a需求追溯矩阵)

---

"""

    architecture = """## 11. 项目总体架构与技术选型

### 11.1 系统总体架构

系统采用前后端分离和内部 AI 服务隔离架构。浏览器和移动端只访问 Java 业务后端；Java 负责认证、账户、CRUD、行情、交易、回测、风控、审计和统一 API 边界；Python 仅作为内部 AI 与数值计算服务。所有服务端密钥通过环境变量或部署平台密钥注入。

![JARVIS金融投研平台系统总体架构](uml/架构图_系统总体架构.png)

```mermaid
flowchart LR
    U[Web / 移动端<br/>Vue 3 + Vite + ECharts] --> G[Nginx / Cloudflare<br/>HTTPS]
    G --> J[Java 业务后端<br/>Spring Boot 3.3]
    J --> DB[(PostgreSQL<br/>Flyway)]
    J --> O[GitHub OAuth]
    J --> R[Resend 邮箱服务]
    J --> P[Python AI 服务<br/>FastAPI]
    P --> M[DeepSeek 兼容 Provider]
    J --> D[行情数据源\n缓存与备用源]
```

### 11.2 产品技术选型

| 层次 | 技术选型 | 主要职责 | 选型理由与约束 |
| --- | --- | --- | --- |
| 前端 | Vue 3 + Vite + ECharts；原生 CSS 设计系统 | 登录注册、行情/K线、AI交互、回测、模拟盘和管理员界面 | 与现有 frontend 工程一致；前端只调用 Java `/api`，不直连 Python 或第三方服务 |
| 后端（业务/CRUD） | Java 17 + Spring Boot 3.3 + Spring Security + JWT HttpOnly Cookie + Spring Data JPA + PostgreSQL/Flyway（本地 H2） | 认证、RBAC、用户/配额/审计、行情、K线、回测、模拟交易、风控和统一 API 网关 | 事务、并发控制和数据一致性边界清晰；金额、数量和价格使用 BigDecimal |
| AI 端 | Python 3.10+ + FastAPI + Uvicorn + Pydantic + requests；DeepSeek 兼容 Chat Completions Provider | AI 推理、流式输出适配、财报/研报/产业链分析、数值计算和数据预处理 | 仅监听内网地址并校验内部服务令牌；模型不负责未经 Python 计算的金融数值 |
| 数据与外部服务 | PostgreSQL、H2、GitHub OAuth、Resend、行情数据源、Nginx/Cloudflare | 持久化、第三方身份、邮箱验证、行情采集和 HTTPS 入口 | 第三方密钥只放服务端环境变量或密钥管理；外部行情失败时使用缓存和备用源 |

### 11.3 核心数据流与安全边界

- 用户请求：前端通过 HTTPS 访问 Java API，Java 完成 JWT、CSRF、账户状态、RBAC 和功能权限校验。
- AI 请求：Java 原子扣减用户配额后，通过内部服务令牌调用 Python；Python 再访问 AI Provider，浏览器永不接触 AI API Key。
- 行情请求：Java 统一采集和缓存行情，前端仅消费 Java 返回的数据；指标和数值计算由 Python 或 Java 确定性服务完成并记录口径。
- 管理审计：账户启停、配额调整、权限变更和高风险操作记录操作者、目标、时间、来源 IP、变更摘要和原因。

### 11.4 OAuth 回调配置

OAuth 回调地址只允许配置为白名单值，Secret/API Key 不写入本文档：

| 环境 | GitHub OAuth Callback URL |
| --- | --- |
| 生产 | `https://agent.shengxia.me/api/auth/github/callback` |
| 本地 | `http://localhost:8200/api/auth/github/callback` |

前端登录入口：生产使用 `https://f.shengxia.me`，本地使用 `http://localhost:5173`。

## 12. SRS模板补充说明

### 12.1 数据字典

| 实体 | 关键字段/说明 |
| --- | --- |
| User | 用户账号、状态、注册方式、最近登录时间 |
| OAuthAccount | Provider、第三方用户 ID、绑定时间 |
| EmailVerificationCode | 验证码摘要、用途、有效期、尝试次数、消费状态 |
| Role / Permission / UserRole | 管理员角色、功能权限及用户关联 |
| AiQuota / AiUsage | 用户或用户组配额、周期、消耗、调整和剩余量 |
| AdminAuditLog | 管理员、目标对象、操作时间、来源 IP、变更摘要和原因 |
| PriceSnapshot / KlineDaily | 标的、价格、涨跌、OHLCV、时间和数据源 |
| SimAccount / SimPosition / SimTrade | 模拟账户、持仓、订单与成交记录 |
| BacktestTask / BacktestResult | 回测参数、任务状态、收益、回撤和净值曲线 |
| ResearchDocument / AnalysisResult | 研报或财报原文、解析状态、结构化结果和来源 |

### 12.2 E-R关系说明

- 用户与角色为多对多关系；用户与登录身份、AI 配额、订单、持仓、回测任务、研报分析和审计日志为一对多关系。
- 订单与成交记录为一对多关系；回测任务与回测结果为一对一关系。
- 管理操作必须关联操作者、目标对象、变更前后摘要和时间，并通过后端 RBAC 强制校验。

### 12.3 非功能性基线

| 类别 | 基线要求 |
| --- | --- |
| 性能 | 首屏 ≤ 3 秒；普通 CRUD API P95 ≤ 500ms；行情查询 P95 ≤ 1 秒；AI 首 token 目标 ≤ 5 秒 |
| 可用性 | 核心 API 月度可用性目标 99.5%；具备健康检查、缓存、有限重试、降级提示和备份恢复 |
| 安全 | HttpOnly Cookie JWT、CSRF、OAuth state、服务端密钥、RBAC、验证码限流和关键操作审计 |
| 可维护性 | 分层模块化、统一日志/异常/配置/迁移、请求追踪、监控告警、接口契约和版本记录 |
| 兼容性 | 支持 Chrome/Edge 最新两个大版本；桌面优先，窄屏降级为单列布局 |
| 合规 | 模拟盘使用虚拟资金并明示“不构成投资建议”；金融数据和个人信息遵循最小化、脱敏和权限隔离原则 |

---

"""

    return cover + prd_body + "\n\n---\n\n" + architecture + "\n*本文档由内部评审通过的 PRD 与 SRS 模板补充生成，具体实现以代码仓库为准。*\n"


def main() -> None:
    OUTPUT.write_text(build_markdown(), encoding="utf-8", newline="\n")
    print(f"Markdown generated: {OUTPUT}")


if __name__ == "__main__":
    main()
