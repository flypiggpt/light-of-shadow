"""FAQ 工具：购物指南常见问题。

降级链：RAG 混合检索（主路径，语料 app/data/faq_corpus.json）→
本地关键词知识库 → 商品搜索拿事实数据。未命中标准答案时
由上层 LLM 组织回答（不编造价格库存）。
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from app.clients.java_api import JavaApiClient
from app.tools.base import BaseTool, ToolContext

# 本地 FAQ 知识库：关键词命中计分，分数高者胜出
_FAQ_KB: tuple[dict[str, Any], ...] = (
    {
        "question": "支持哪些支付方式？",
        "keywords": ("支付", "付款", "微信", "支付宝", "银行卡", "pay"),
        "answer": "支持微信支付、支付宝与银行卡支付，下单后在订单页完成支付即可。",
    },
    {
        "question": "多久发货？运费怎么算？",
        "keywords": ("发货", "运费", "快递", "配送", "包邮", "物流"),
        "answer": "现货商品 48 小时内发货，满 99 元包邮；不满 99 元收取 8 元运费。",
    },
    {
        "question": "可以退换货吗？",
        "keywords": ("退货", "换货", "退款", "退换", "七天", "无理由"),
        "answer": "支持 7 天无理由退换货（商品需不影响二次销售），在订单页发起售后即可。",
    },
    {
        "question": "可以开发票吗？",
        "keywords": ("发票", "开票", "报销", "税"),
        "answer": "支持电子发票，下单时填写发票信息，发货后 3 个工作日内开具。",
    },
    {
        "question": "怎么查订单和物流？",
        "keywords": ("订单", "物流", "快递到哪", "查单", "运单"),
        "answer": "在“我的-订单”中查看订单状态与物流轨迹，发货后会推送运单号。",
    },
    {
        "question": "商品有保修吗？",
        "keywords": ("保修", "售后", "维修", "质保", "坏了"),
        "answer": "电子产品享受一年质保，非人为损坏可申请售后维修或换新。",
    },
)

_FAQ_MIN_SCORE = 2


class FaqArgs(BaseModel):
    """FAQ 参数。"""

    question: str = Field(min_length=1, max_length=200, description="用户的问题原文")


class FaqTool(BaseTool):
    name = "faq"
    description = (
        "查询购物常见问题（支付、运费、退换货、发票、物流、保修等）。"
        "未命中知识库时回退商品搜索，返回事实数据供组织回答"
    )
    args_model = FaqArgs

    def __init__(self, client: JavaApiClient | None = None, retriever: Any = None,
                 **base_kwargs: Any) -> None:
        super().__init__(**base_kwargs)
        self._client = client or JavaApiClient()
        self._retriever = retriever

    async def _invoke(self, args: FaqArgs, ctx: ToolContext) -> Any:
        # 1) RAG 混合检索（生产主路径）：召回语料片段供上层 LLM 组织回答
        if self._retriever is not None:
            result = self._retriever.retrieve(args.question, top_k=3)
            if result.items:
                documents = [
                    {
                        "doc_id": item.chunk.doc_id,
                        "title": (item.chunk.metadata or {}).get("title", ""),
                        "text": item.chunk.text,
                        "score": item.score,
                    }
                    for item in result.items
                ]
                return {
                    "type": "rag",
                    "question": args.question,
                    "documents": documents,
                }

        # 2) 关键词知识库兜底（标准答案）
        best = self._match_faq(args.question)
        if best is not None:
            return {
                "type": "faq",
                "question": best["question"],
                "answer": best["answer"],
                "score": best["score"],
            }

        # 知识库未命中：回退商品搜索，价格库存以 Java 返回为准
        if not ctx.user_token:
            return {
                "type": "none",
                "answer": "抱歉，这个问题我还答不上来，可以换个说法或直接描述想买的商品。",
            }
        products = await self._client.search_products(
            keyword=args.question,
            page=1,
            size=5,
            user_token=ctx.user_token,
            request_id=ctx.request_id,
        )
        return {
            "type": "products",
            "answer": "没有找到直接答案，以下是相关的商品信息，可据此回答用户问题。",
            "products": products,
        }

    @staticmethod
    def _match_faq(question: str) -> dict[str, Any] | None:
        text = question.lower()
        best: dict[str, Any] | None = None
        for entry in _FAQ_KB:
            score = sum(1 for kw in entry["keywords"] if kw.lower() in text)
            if score >= _FAQ_MIN_SCORE and (best is None or score > best["score"]):
                best = {**entry, "score": score}
        return best
