#!/usr/bin/env python3
"""AI 路由: 统一挂载到主应用 /api/ai/*"""
from typing import Annotated, List, Dict, Optional, Any, Literal
import hmac
import json
import os

from fastapi import APIRouter, Depends, Header, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from . import ai_service

INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token"
PYTHON_SERVICE_TOKEN = os.getenv("PYTHON_SERVICE_TOKEN", "")


def require_internal_service(
    x_internal_service_token: Optional[str] = Header(default=None, alias=INTERNAL_TOKEN_HEADER),
):
    """AI 接口仅允许 Java 主后端通过内部服务令牌调用。"""
    # 每次校验读取当前进程配置，既支持启动时注入，也避免测试/嵌入式运行在
    # 模块导入顺序变化时错误地沿用空 token；生产进程不会动态接受请求头中的 token。
    configured_token = os.getenv("PYTHON_SERVICE_TOKEN", PYTHON_SERVICE_TOKEN)
    if not configured_token:
        raise HTTPException(status_code=503, detail="PYTHON_SERVICE_TOKEN 未配置")
    if not x_internal_service_token or not hmac.compare_digest(
        x_internal_service_token, configured_token
    ):
        raise HTTPException(status_code=401, detail="invalid internal service token")


router = APIRouter(
    prefix="/api/ai",
    tags=["ai"],
    dependencies=[Depends(require_internal_service)],
)


class ChatMessage(BaseModel):
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=10_000)


class ChatReq(BaseModel):
    messages: List[ChatMessage] = Field(min_length=1, max_length=20)
    temperature: float = Field(default=0.7, ge=0.0, le=2.0)
    # 仅由 Java 主后端注入；Python 会在调用 LLM 前对其中行情/K线做确定性计算。
    research_context: Optional[Dict[str, Any]] = None
    metrics: Optional[Dict[str, Any]] = None


class ReportReq(BaseModel):
    content: str = Field(default="", max_length=50_000)
    text: Optional[str] = Field(default="", max_length=50_000)


class NewsTranslateReq(BaseModel):
    titles: List[Annotated[str, Field(min_length=1, max_length=500)]] = Field(min_length=1, max_length=64)


class NewsAnalysisReq(BaseModel):
    """RSS 文章的可审计分析输入；正文只允许作为有限上下文进入模型。"""
    articles: List[Dict[str, Any]] = Field(min_length=1, max_length=12)


class SentimentReq(BaseModel):
    reports: List[str] = Field(default_factory=list, min_length=1, max_length=20)


class ChainReq(BaseModel):
    node: str = Field(min_length=1, max_length=100)
    context: Optional[str] = Field(default="", max_length=10_000)


class QuoteReq(BaseModel):
    """智能询报价（FR-07）：price_data 为行情快照，closes 为历史收盘价序列。

    closes 由 Java 主后端从自营 K 线库注入并覆盖客户端传值；缺失时只返回报价解读，
    不输出趋势区间（forecast 字段省略），保持既有调用方兼容。
    样本量是否足够由确定性计算层判断，统一返回 available=False 而非 422。
    """
    price_data: Dict[str, Any] = Field(default_factory=dict)
    closes: Optional[List[float]] = Field(default=None, max_length=2000)
    horizon_days: Optional[int] = Field(default=None, ge=1, le=60)
    confidence: Optional[float] = Field(default=None, ge=0.5, le=0.99)
    symbol: Optional[str] = Field(default=None, max_length=32)
    # Phase 2 ⑧：Java 侧用**同一个快照**算好的派生指标，随请求下发；有则引用、无则回退本地。
    # 该模型同样没有开 extra=forbid，新增字段不会导致 422。
    metrics: Optional[Dict[str, Any]] = None


class RiskReq(BaseModel):
    """风险预警（FR-10）：closes 为历史收盘价序列。

    样本量是否足够由确定性计算层判断，以便统一返回 available=False，
    而不是在接口校验阶段直接返回 422。
    """
    closes: List[float] = Field(max_length=2000)
    confidence: float = Field(default=0.95, ge=0.5, le=0.99)
    portfolio_value: Optional[float] = Field(default=None, gt=0)
    symbol: Optional[str] = Field(default=None, max_length=32)
    # Phase 2 ⑧：Java 侧用**同一次服务端取数**算好的指标，随请求下发。
    # 有就直接引用（"同一组数字只有一个来源"），没有则回退到本地确定性层。
    metrics: Optional[Dict[str, Any]] = None


class StrategyReq(BaseModel):
    """个性化策略生成（FR-11）：风险偏好问卷字段。

    范围在接口层做硬校验（问卷由前端固定选项产生，越界视为客户端错误）；
    等级与配置比例的映射逻辑放在确定性计算层。
    """
    horizon_years: float = Field(ge=0.5, le=30)
    max_drawdown_pct: float = Field(ge=1, le=60)
    target_return_pct: float = Field(ge=0, le=50)
    capital: Optional[float] = Field(default=None, gt=0, le=1_000_000_000)
    experience: Literal["none", "basic", "rich"] = "basic"


class TrendReq(BaseModel):
    """市场趋势预测（FR-12）：closes 为历史收盘价序列。

    与风险/报价端点一致：closes 由 Java 主后端从自营 K 线库注入并覆盖客户端传值；
    样本量是否足够由确定性计算层判断，统一返回 available=False 而非 422。
    """
    closes: List[float] = Field(default_factory=list, max_length=2000)
    horizon_days: Optional[int] = Field(default=None, ge=1, le=60)
    confidence: Optional[float] = Field(default=None, ge=0.5, le=0.99)
    metrics: Optional[Dict[str, Any]] = None
    symbol: Optional[str] = Field(default=None, max_length=32)


class ResearchReportReq(BaseModel):
    """研究任务报告（Phase 2 AI Research Core）。

    与其它端点最重要的区别：**metrics 由 Java 确定性计算层算好传进来，Python 不重算**。
    报告里的数字必须与任务详情页展示的数字逐字相同，否则同一份研究会有两个口径。
    warnings 同理——"缺了什么数据"由程序判断，不让模型自己猜自己缺什么。
    """
    task_type: Literal["REPORT", "SENTIMENT", "CHAIN", "RISK", "TREND", "STRATEGY"] = "REPORT"
    title: Optional[str] = Field(default=None, max_length=200)
    question: Optional[str] = Field(default=None, max_length=2000)
    market: Optional[str] = Field(default=None, max_length=20)
    symbol: Optional[str] = Field(default=None, max_length=32)
    metrics: Dict[str, Any] = Field(default_factory=dict)
    quote: Optional[Dict[str, Any]] = None
    warnings: List[str] = Field(default_factory=list, max_length=20)


def _guard(fn, **kw):
    """执行并统一把 RuntimeError 转 502"""
    try:
        return fn(**kw)
    except RuntimeError as e:
        raise HTTPException(status_code=502, detail=str(e))


@router.get("/capabilities")
def get_capabilities():
    return {"code": 200, "message": "ok", "data": ai_service.capabilities()}


@router.post("/chat")
def chat(req: ChatReq):
    messages = [message.model_dump() for message in req.messages]
    return {"code": 200, "message": "ok", "data": _guard(
        ai_service.chat,
        messages=messages,
        temperature=req.temperature,
        research_context=req.research_context,
        metrics=req.metrics,
    )}


@router.post("/chat/stream")
def chat_stream(req: ChatReq):
    messages = [message.model_dump() for message in req.messages]
    try:
        events = ai_service.open_chat_stream(
            messages=messages,
            temperature=req.temperature,
            research_context=req.research_context,
            metrics=req.metrics,
        )
    except RuntimeError as e:
        raise HTTPException(status_code=502, detail=str(e))

    def sse_events():
        for event in events:
            event_type = str(event.get("type", "message"))
            data = json.dumps(event, ensure_ascii=False, separators=(",", ":"))
            yield f"event: {event_type}\ndata: {data}\n\n"

    return StreamingResponse(
        sse_events(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache, no-transform",
            "X-Accel-Buffering": "no",
        },
    )


@router.post("/news/translate")
def news_translate(req: NewsTranslateReq):
    return {"code": 200, "message": "ok", "data": _guard(ai_service.translate_news_titles, titles=req.titles)}


@router.post("/analyze/news")
def analyze_news(req: NewsAnalysisReq):
    if sum(len(str(item.get("title") or "")) + len(str(item.get("body") or item.get("summary") or ""))
           for item in req.articles) > 30_000:
        raise HTTPException(status_code=413, detail="RSS 分析上下文不能超过30000字符")
    return {"code": 200, "message": "ok", "data": _guard(
        ai_service.analyze_news_articles, articles=req.articles)}


@router.post("/financial/report")
def financial_report(req: ReportReq):
    return {"code": 200, "message": "ok", "data": _guard(ai_service.financial_report, content=req.content or req.text or "")}


@router.post("/analyze/sentiment")
def analyze_sentiment(req: SentimentReq):
    if sum(len(item) for item in req.reports) > 100_000:
        raise HTTPException(status_code=413, detail="研报文本总长度不能超过100000字符")
    return {"code": 200, "message": "ok", "data": _guard(ai_service.analyze_sentiment, reports=req.reports)}


@router.post("/analyze/chain")
def analyze_chain(req: ChainReq):
    return {"code": 200, "message": "ok", "data": _guard(ai_service.analyze_chain, node=req.node, context=req.context or "")}


@router.post("/analyze/risk")
def analyze_risk(req: RiskReq):
    return {"code": 200, "message": "ok",
            "data": _guard(ai_service.analyze_risk,
                           closes=req.closes,
                           confidence=req.confidence,
                           portfolio_value=req.portfolio_value,
                           symbol=req.symbol,
                           metrics=req.metrics)}


@router.post("/analyze/strategy")
def analyze_strategy(req: StrategyReq):
    return {"code": 200, "message": "ok",
            "data": _guard(ai_service.generate_strategy,
                           horizon_years=req.horizon_years,
                           max_drawdown_pct=req.max_drawdown_pct,
                           target_return_pct=req.target_return_pct,
                           capital=req.capital,
                           experience=req.experience)}


@router.post("/analyze/trend")
def analyze_trend(req: TrendReq):
    return {"code": 200, "message": "ok",
            "data": _guard(ai_service.market_trend,
                           closes=req.closes,
                           horizon_days=req.horizon_days,
                           confidence=req.confidence,
                           symbol=req.symbol,
                           metrics=req.metrics)}


@router.post("/quote")
def smart_quote(req: QuoteReq):
    return {"code": 200, "message": "ok",
            "data": _guard(ai_service.smart_quote,
                           price_data=req.price_data,
                           closes=req.closes,
                           horizon_days=req.horizon_days,
                           confidence=req.confidence,
                           symbol=req.symbol,
                           metrics=req.metrics)}


@router.post("/research/report")
def research_report(req: ResearchReportReq):
    return {"code": 200, "message": "ok",
            "data": _guard(ai_service.research_report,
                           task=req.model_dump(include={
                               "task_type", "title", "question", "market", "symbol",
                           }),
                           metrics=req.metrics,
                           quote=req.quote,
                           warnings=req.warnings)}
