"""可插拔的财经资讯语义排序层。

设计原则：
- RSS 抓取、确定性去重与 V1 质量评分永远可独立工作；本模块失败时只回退，不阻断资讯流。
- Embedding 使用 OpenAI-compatible `/v1/embeddings`，便于独立部署 Qwen3-Embedding。
- Cross-Encoder rerank 使用可选的通用 `/rerank` HTTP 契约；未配置时只做 embedding relevance + MMR。
- 不在本进程直接加载 torch/transformers，避免把轻量 FastAPI 服务变成 GPU 运行时。
"""
from __future__ import annotations

import math
import os
from typing import Any, Dict, List, Optional, Sequence

import requests


def _env_bool(name: str, default: bool = False) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _env_float(name: str, default: float) -> float:
    try:
        return float(os.getenv(name, str(default)))
    except (TypeError, ValueError):
        return default


def _env_int(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, str(default)))
    except (TypeError, ValueError):
        return default


NEWS_SEMANTIC_ENABLED = _env_bool("NEWS_SEMANTIC_ENABLED", False)
NEWS_EMBEDDING_BASE_URL = os.getenv("NEWS_EMBEDDING_BASE_URL", "").rstrip("/")
NEWS_EMBEDDING_MODEL = os.getenv("NEWS_EMBEDDING_MODEL", "Qwen/Qwen3-Embedding-0.6B")
NEWS_RERANK_URL = os.getenv("NEWS_RERANK_URL", "").strip()
NEWS_RERANK_MODEL = os.getenv("NEWS_RERANK_MODEL", "Qwen/Qwen3-Reranker-0.6B")
NEWS_SEMANTIC_API_KEY = os.getenv("NEWS_SEMANTIC_API_KEY", "")
NEWS_SEMANTIC_TIMEOUT = _env_float("NEWS_SEMANTIC_TIMEOUT_SECONDS", 12.0)
NEWS_SEMANTIC_MAX_CANDIDATES = _env_int("NEWS_SEMANTIC_MAX_CANDIDATES", 60)
NEWS_MMR_LAMBDA = _env_float("NEWS_MMR_LAMBDA", 0.72)


def capabilities() -> Dict[str, Any]:
    available = bool(NEWS_SEMANTIC_ENABLED and NEWS_EMBEDDING_BASE_URL)
    return {
        "enabled": bool(NEWS_SEMANTIC_ENABLED),
        "available": available,
        "embedding_model": NEWS_EMBEDDING_MODEL,
        "rerank_model": NEWS_RERANK_MODEL if NEWS_RERANK_URL else None,
        "rerank_enabled": bool(NEWS_RERANK_URL),
        "max_candidates": max(1, NEWS_SEMANTIC_MAX_CANDIDATES),
        "mmr_lambda": max(0.0, min(1.0, NEWS_MMR_LAMBDA)),
    }


def _headers() -> Dict[str, str]:
    headers = {"Content-Type": "application/json"}
    if NEWS_SEMANTIC_API_KEY:
        headers["Authorization"] = f"Bearer {NEWS_SEMANTIC_API_KEY}"
    return headers


def _embedding_url() -> str:
    base = NEWS_EMBEDDING_BASE_URL.rstrip("/")
    if base.endswith("/v1"):
        return f"{base}/embeddings"
    return f"{base}/v1/embeddings"


def _embed_texts(texts: Sequence[str]) -> List[List[float]]:
    response = requests.post(
        _embedding_url(),
        headers=_headers(),
        json={
            "model": NEWS_EMBEDDING_MODEL,
            "input": list(texts),
            "encoding_format": "float",
        },
        timeout=(3, NEWS_SEMANTIC_TIMEOUT),
    )
    response.raise_for_status()
    payload = response.json()
    raw = payload.get("data") if isinstance(payload, dict) else None
    if not isinstance(raw, list) or len(raw) != len(texts):
        raise RuntimeError("embedding 响应数量不匹配")
    ordered = sorted(raw, key=lambda item: int(item.get("index", 0)) if isinstance(item, dict) else 0)
    vectors: List[List[float]] = []
    for item in ordered:
        vector = item.get("embedding") if isinstance(item, dict) else None
        if not isinstance(vector, list) or not vector:
            raise RuntimeError("embedding 响应缺少向量")
        parsed = [float(value) for value in vector]
        vectors.append(parsed)
    dimensions = {len(vector) for vector in vectors}
    if len(dimensions) != 1:
        raise RuntimeError("embedding 向量维度不一致")
    return vectors


def _cosine(left: Sequence[float], right: Sequence[float]) -> float:
    if len(left) != len(right) or not left:
        return 0.0
    dot = sum(a * b for a, b in zip(left, right))
    left_norm = math.sqrt(sum(value * value for value in left))
    right_norm = math.sqrt(sum(value * value for value in right))
    if left_norm <= 0 or right_norm <= 0:
        return 0.0
    return max(-1.0, min(1.0, dot / (left_norm * right_norm)))


def _article_text(article: Dict[str, Any]) -> str:
    title = str(article.get("title_zh") or article.get("title") or "").strip()
    original = str(article.get("title_original") or "").strip()
    summary = str(article.get("summary") or "").strip()
    category = str(article.get("category") or "").strip()
    tags = article.get("tags") if isinstance(article.get("tags"), list) else []
    tag_text = " ".join(str(item).strip() for item in tags if str(item).strip())
    parts = [title]
    if original and original != title:
        parts.append(original)
    parts.extend([summary, category, tag_text])
    return "\n".join(part for part in parts if part)[:4_000]


def _request_rerank(query: str, documents: Sequence[str]) -> Optional[List[float]]:
    if not NEWS_RERANK_URL:
        return None
    response = requests.post(
        NEWS_RERANK_URL,
        headers=_headers(),
        json={
            "model": NEWS_RERANK_MODEL,
            "query": query,
            "documents": list(documents),
            "top_n": len(documents),
        },
        timeout=(3, NEWS_SEMANTIC_TIMEOUT),
    )
    response.raise_for_status()
    payload = response.json()
    raw = payload.get("results") if isinstance(payload, dict) else None
    if not isinstance(raw, list):
        raw = payload.get("data") if isinstance(payload, dict) else None
    if not isinstance(raw, list):
        raise RuntimeError("rerank 响应缺少 results")
    scores = [0.0] * len(documents)
    seen = set()
    for position, item in enumerate(raw):
        if not isinstance(item, dict):
            continue
        raw_index = item.get("index", item.get("document_index", position))
        try:
            index = int(raw_index)
        except (TypeError, ValueError):
            continue
        if not 0 <= index < len(documents):
            continue
        raw_score = item.get("relevance_score", item.get("score", 0.0))
        try:
            score = float(raw_score)
        except (TypeError, ValueError):
            score = 0.0
        scores[index] = max(0.0, min(1.0, score))
        seen.add(index)
    if len(seen) != len(documents):
        raise RuntimeError("rerank 响应未覆盖全部候选")
    return scores


def _mmr_order(hybrid_scores: Sequence[float], vectors: Sequence[Sequence[float]]) -> List[int]:
    if not hybrid_scores:
        return []
    lam = max(0.0, min(1.0, NEWS_MMR_LAMBDA))
    remaining = set(range(len(hybrid_scores)))
    selected: List[int] = []
    while remaining:
        best_index = None
        best_score = float("-inf")
        for index in remaining:
            redundancy = 0.0
            if selected:
                redundancy = max(_cosine(vectors[index], vectors[chosen]) for chosen in selected)
                redundancy = max(0.0, redundancy)
            score = lam * (hybrid_scores[index] / 100.0) - (1.0 - lam) * redundancy
            if score > best_score or (score == best_score and (best_index is None or index < best_index)):
                best_score = score
                best_index = index
        assert best_index is not None
        selected.append(best_index)
        remaining.remove(best_index)
    return selected


def rerank_articles(query: str, articles: Sequence[Dict[str, Any]]) -> Dict[str, Any]:
    """对 V1 候选做 semantic relevance + optional cross-encoder + MMR。

    任何 provider 错误都返回 `available=false` 并原样带回候选，调用方必须回退 V1。
    """
    original = [dict(article) for article in articles if isinstance(article, dict)]
    caps = capabilities()
    if not caps["available"]:
        return {**caps, "available": False, "reason": "semantic_disabled", "items": original}
    cleaned_query = str(query or "").strip()[:1_000]
    if not cleaned_query or not original:
        return {**caps, "available": False, "reason": "semantic_empty_input", "items": original}

    cap = max(1, NEWS_SEMANTIC_MAX_CANDIDATES)
    candidates = original[:cap]
    tail = original[cap:]
    documents = [_article_text(article) for article in candidates]
    try:
        vectors = _embed_texts([cleaned_query, *documents])
        query_vector, document_vectors = vectors[0], vectors[1:]
        semantic = [max(0.0, _cosine(query_vector, vector)) for vector in document_vectors]
        rerank_scores = _request_rerank(cleaned_query, documents)

        relevance: List[float] = []
        hybrid: List[float] = []
        for index, article in enumerate(candidates):
            semantic_score = semantic[index] * 100.0
            if rerank_scores is None:
                relevance_score = semantic_score
            else:
                relevance_score = (rerank_scores[index] * 100.0) * 0.72 + semantic_score * 0.28
            try:
                deterministic = float(article.get("rank_score") or 0.0)
            except (TypeError, ValueError):
                deterministic = 0.0
            deterministic = max(0.0, min(100.0, deterministic))
            hybrid_score = relevance_score * 0.60 + deterministic * 0.40
            relevance.append(relevance_score)
            hybrid.append(hybrid_score)

        order = _mmr_order(hybrid, document_vectors)
        ranked: List[Dict[str, Any]] = []
        for index in order:
            article = dict(candidates[index])
            article["semantic_score"] = round(semantic[index] * 100.0, 2)
            article["hybrid_score"] = round(hybrid[index], 2)
            article["semantic_model"] = NEWS_EMBEDDING_MODEL
            article["ranking_stage"] = "rerank_mmr" if rerank_scores is not None else "embedding_mmr"
            if rerank_scores is not None:
                article["rerank_score"] = round(rerank_scores[index] * 100.0, 2)
                article["rerank_model"] = NEWS_RERANK_MODEL
            reasons = article.get("selection_reason")
            reasons = list(reasons) if isinstance(reasons, list) else []
            reasons.append(f"语义相关度 {int(round(relevance[index]))}")
            article["selection_reason"] = reasons[:5]
            ranked.append(article)

        return {
            "available": True,
            "reason": None,
            "items": ranked + tail,
            "embedding_model": NEWS_EMBEDDING_MODEL,
            "rerank_model": NEWS_RERANK_MODEL if rerank_scores is not None else None,
            "ranking_stage": "rerank_mmr" if rerank_scores is not None else "embedding_mmr",
        }
    except (requests.RequestException, RuntimeError, TypeError, ValueError) as exc:
        return {
            **caps,
            "available": False,
            "reason": "semantic_provider_error",
            "error": str(exc)[:300],
            "items": original,
        }
