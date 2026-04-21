#!/usr/bin/env python3
"""
Memory Box Demo Data Generator
生成丰富的 .memory/ 演示数据，让所有 CLI 命令都有内容可展示。

用法：python3 scripts/demo-data-generator.py [--backup]
"""

import base64
import json
import os
import shutil
import sys
from datetime import datetime, timedelta
from pathlib import Path

MEMORY_DIR = Path(__file__).resolve().parent.parent / ".memory"
SKILLS_DIR = MEMORY_DIR / "skills"

# ========== Persona ==========
PERSONA_NAME = "张明"
PERSONA_MAJOR = "计算机科学与技术"
PERSONA_INTERESTS = "分布式系统、智能对话系统、RAG技术"
PERSONA_LANGS = "Java、Python、正在学 Rust"
PERSONA_EDITOR = "IntelliJ IDEA"
PERSONA_HABIT = "喜欢晚上写代码，一般 22:00-02:00 效率最高"
PERSONA_PROJECT = "毕业设计：四层智能记忆管理系统（Memory Box）"
PERSONA_COURSE = "正在学《大语言模型应用开发》课程"


def b64url(text: str) -> str:
    """Base64 URL-safe encoding without padding, with b64: prefix."""
    return "b64:" + base64.urlsafe_b64encode(text.encode("utf-8")).rstrip(b"=").decode("ascii")


def dt(offset_days: float = 0, offset_hours: float = 0) -> str:
    """Generate ISO datetime string from now with offset."""
    t = datetime(2026, 4, 10, 20, 0, 0) + timedelta(days=offset_days, hours=offset_hours)
    return t.strftime("%Y-%m-%d %H:%M:%S")


def dt_iso(offset_days: float = 0, offset_hours: float = 0) -> str:
    """Generate ISO datetime with T separator."""
    t = datetime(2026, 4, 10, 20, 0, 0) + timedelta(days=offset_days, hours=offset_hours)
    return t.isoformat()


def local_dt_array(offset_days: float = 0, offset_hours: float = 0):
    """Java LocalDateTime array format [year, month, day, hour, minute, second, nano]."""
    t = datetime(2026, 4, 10, 20, 0, 0) + timedelta(days=offset_days, hours=offset_hours)
    return [t.year, t.month, t.day, t.hour, t.minute, t.second, t.microsecond * 1000]


def write_json(path: Path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def append_jsonl(path: Path, records: list):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")


def write_text(path: Path, text: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


# ========== 1. conversation_history.jsonl ==========
def gen_conversation_history():
    """40+ turns of meaningful conversation."""
    turns = [
        (-15, -4, "user", "你好，我是张明，计算机科学与技术专业的大四学生"),
        (-15, -3.9, "assistant", f"你好{PERSONA_NAME}！很高兴认识你。你是计算机专业的大四学生，现在应该在忙毕业设计吧？有什么我可以帮你的？"),
        (-15, -3.5, "user", "对，我的毕设题目是四层智能记忆管理系统，用Java和Spring Boot做"),
        (-15, -3.4, "assistant", "四层记忆架构是个很有意思的方向！你说的四层是指短期记忆、元数据、用户洞察、持续学习知识这几个层级吗？Java + Spring Boot的技术栈很合适"),
        (-14, 1, "user", "我比较喜欢用Java写代码，Python也写一些"),
        (-14, 1.1, "assistant", "了解，你的主力语言是Java，Python作为辅助。这个组合在工程开发中很常见。你平时用什么IDE？"),
        (-14, 1.5, "user", "IntelliJ IDEA，用了三年了"),
        (-14, 1.6, "assistant", "IntelliJ IDEA确实是Java开发的首选。你的四层记忆系统现在进展到哪一步了？"),
        (-12, -2, "user", "我在学Rust，想用Rust重写一些性能敏感的模块"),
        (-12, -1.9, "assistant", "Rust在性能方面确实有优势。不过毕设时间有限，建议先保证Java核心功能完成，Rust可以放到后续优化。你目前系统有哪些核心功能了？"),
        (-10, 0, "user", "最近在学LangChain和RAG相关的技术，想在我的毕设里加入向量检索"),
        (-10, 0.1, "assistant", "LangChain4j在Java生态里是比较成熟的LLM框架，RAG模块也很好用。向量检索可以让记忆系统在语义层面找到相关的历史经验。你打算用什么embedding模型？"),
        (-10, 0.5, "user", "打算用AllMiniLML6V2，本地运行不需要API"),
        (-10, 0.6, "assistant", "AllMiniLML6V2是个好选择，384维的向量，轻量且效果不错，适合本地部署场景。"),
        (-8, -1, "user", "我一般晚上写代码效率最高，大概22点到凌晨2点"),
        (-8, -0.9, "assistant", "晚间编程效率高很正常，安静的环境更适合深度思考。不过也要注意休息，毕竟毕设是持久战。"),
        (-7, 2, "user", "帮我创建一个任务，后天下午3点提醒我提交毕设中期检查报告"),
        (-7, 2.1, "assistant", "好的，已为你创建任务：后天（4月6日）15:00 提交毕设中期检查报告。到时候会提醒你。"),
        (-6, 0, "user", "我不太爱吃鱼，食堂的鱼每次都避开"),
        (-6, 0.1, "assistant", "记住了，你不喜欢吃鱼。说到食堂，你们学校食堂怎么样？"),
        (-5, -3, "user", "你能记住之前我告诉过你的事情吗？"),
        (-5, -2.9, "assistant", "当然可以。根据我的记忆：你叫张明，计算机科学与技术专业大四学生，毕设是四层智能记忆管理系统，用Java和Spring Boot开发，主力IDE是IntelliJ IDEA，目前在学Rust和LangChain/RAG技术，喜欢晚上写代码，不喜欢吃鱼。这些信息我都记着呢。"),
        (-5, -2.5, "user", "太好了！记忆系统确实在工作"),
        (-5, -2.4, "assistant", "是的，这就是记忆系统的核心价值——跨会话记住用户的关键信息。每次对话时，系统会通过记忆反思机制判断是否需要加载长期记忆，确保回复具有个性化。"),
        (-4, 0, "user", "我想了解一下RAG在记忆系统里是怎么工作的"),
        (-4, 0.1, "assistant", "在你的系统里，RAG主要用于Layer 4b。当用户提出新问题时，系统会通过语义检索找到历史上相似的对话或经验，然后将这些检索到的内容注入到prompt中。这样模型就能参考过去的经验来回答当前问题，而不需要每次都从零开始。"),
        (-3, -2, "user", "明天上午10点提醒我开组会"),
        (-3, -1.9, "assistant", "已创建任务：明天（4月8日）10:00 开组会，到时会提醒你。"),
        (-3, 0, "user", "/what-you-know"),
        (-3, 0.05, "assistant", "我来查看一下当前记录的用户画像..."),
        (-2, 0, "user", "你推荐用什么向量数据库？"),
        (-2, 0.1, "assistant", "对于你的毕设场景，建议用本地文件存储向量（JSON格式）。原因：1）不需要额外部署数据库服务；2）数据量不大时性能足够；3）方便查看和调试。你的系统目前也是这么实现的，vector_store.json直接存储embedding向量。"),
        (-2, 3, "user", "对，我用的就是本地文件，简单可靠"),
        (-2, 3.1, "assistant", "本地文件方案在毕设场景下是最务实的选择。后续如果需要扩展，可以考虑迁移到Milvus或Chroma这类轻量级向量数据库。"),
        (-1, -4, "user", "帮我整理一下这周的任务完成情况"),
        (-1, -3.9, "assistant", "好的，让我查看一下你本周的任务记录..."),
        (-1, 0, "user", "你能不能分析一下当前记忆系统的使用情况？"),
        (-1, 0.1, "assistant", "根据记忆追踪数据，系统目前的运行状况：用户画像已建立，包含多条长期记忆；记忆反思机制在多数对话中正确判断了是否需要加载记忆；RAG检索和Skill加载按需工作。整体来看，四层记忆架构已经形成了完整的工作闭环。"),
        (0, -2, "user", "下周一下午2点提醒我去找导师讨论论文"),
        (0, -1.9, "assistant", "已创建任务：下周一（4月14日）14:00 找导师讨论论文。到时会提醒你。"),
        (0, 0, "user", "给我总结一下我们所有的对话记录"),
        (0, 0.1, "assistant", "好的，我来为你总结。我们讨论了很多内容，包括你的个人背景、毕设项目进展、技术选型、日常任务管理等..."),
    ]

    lines = []
    for d_off, h_off, role, msg in turns:
        timestamp = dt(d_off, h_off)
        encoded = b64url(msg)
        lines.append(f"{timestamp}|{role}|{encoded}")
    write_text(MEMORY_DIR / "conversation_history.jsonl", "\n".join(lines) + "\n")


# ========== 2. recent_user_messages.jsonl ==========
def gen_recent_user_messages():
    msgs = [
        (-15, -4, "你好，我是张明，计算机科学与技术专业的大四学生"),
        (-14, 1, "我比较喜欢用Java写代码，Python也写一些"),
        (-14, 1.5, "IntelliJ IDEA，用了三年了"),
        (-12, -2, "我在学Rust，想用Rust重写一些性能敏感的模块"),
        (-10, 0, "最近在学LangChain和RAG相关的技术，想在我的毕设里加入向量检索"),
        (-8, -1, "我一般晚上写代码效率最高，大概22点到凌晨2点"),
        (-7, 2, "帮我创建一个任务，后天下午3点提醒我提交毕设中期检查报告"),
        (-6, 0, "我不太爱吃鱼，食堂的鱼每次都避开"),
        (-5, -3, "你能记住之前我告诉过你的事情吗？"),
        (-4, 0, "我想了解一下RAG在记忆系统里是怎么工作的"),
        (-3, -2, "明天上午10点提醒我开组会"),
        (-3, 0, "/what-you-know"),
        (-2, 0, "你推荐用什么向量数据库？"),
        (-1, -4, "帮我整理一下这周的任务完成情况"),
        (0, -2, "下周一下午2点提醒我去找导师讨论论文"),
        (0, 0, "给我总结一下我们所有的对话记录"),
    ]
    lines = []
    for d_off, h_off, msg in msgs:
        timestamp = dt(d_off, h_off)
        lines.append(f"{timestamp}|{msg}")
    write_text(MEMORY_DIR / "recent_user_messages.jsonl", "\n".join(lines) + "\n")


# ========== 3. user-insights.md ==========
def gen_user_insights():
    content = f"""---
updated_at: {dt_iso(0, 1).replace("+", "+")}+08:00
style: narrative
---

用户名叫{PERSONA_NAME}，{PERSONA_MAJOR}专业大四学生。
目前在做{PERSONA_PROJECT}。
技术方面，主力语言是Java，也写Python，最近在学Rust。
开发工具是{PERSONA_EDITOR}，已使用三年。
对{PERSONA_INTERESTS}有浓厚兴趣。
{PERSONA_COURSE}。
生活习惯上，{PERSONA_HABIT}。
饮食方面，不爱吃鱼。

<!-- memsys:state
{{
  "version" : 1,
  "memories" : {{
    "name" : {{
      "content" : "{PERSONA_NAME}",
      "memoryType" : "USER_INSIGHT",
      "source" : "EXPLICIT",
      "hitCount" : 8,
      "createdAt" : "{dt(-15, -4)}",
      "lastAccessed" : "{dt(0, 1)}",
      "confidence" : 0.95,
      "status" : null,
      "verifiedAt" : "{dt(-15, -4)}",
      "verifiedSource" : "EXPLICIT"
    }},
    "major" : {{
      "content" : "{PERSONA_MAJOR}专业大四学生",
      "memoryType" : "USER_INSIGHT",
      "source" : "EXPLICIT",
      "hitCount" : 6,
      "createdAt" : "{dt(-15, -4)}",
      "lastAccessed" : "{dt(-1, 0)}",
      "confidence" : 0.95,
      "status" : null,
      "verifiedAt" : "{dt(-15, -4)}",
      "verifiedSource" : "EXPLICIT"
    }},
    "graduation_project" : {{
      "content" : "{PERSONA_PROJECT}，使用Java和Spring Boot开发",
      "memoryType" : "USER_INSIGHT",
      "source" : "EXPLICIT",
      "hitCount" : 10,
      "createdAt" : "{dt(-15, -3.5)}",
      "lastAccessed" : "{dt(0, 1)}",
      "confidence" : 0.9,
      "status" : null,
      "verifiedAt" : "{dt(-15, -3.5)}",
      "verifiedSource" : "EXPLICIT"
    }},
    "programming_language_preference" : {{
      "content" : "主力语言是{PERSONA_LANGS}",
      "memoryType" : "USER_INSIGHT",
      "source" : "EXPLICIT",
      "hitCount" : 12,
      "createdAt" : "{dt(-14, 1)}",
      "lastAccessed" : "{dt(0, 1)}",
      "confidence" : 0.95,
      "status" : null,
      "verifiedAt" : "{dt(-14, 1)}",
      "verifiedSource" : "EXPLICIT"
    }},
    "ide_preference" : {{
      "content" : "使用{PERSONA_EDITOR}，已使用三年",
      "memoryType" : "USER_INSIGHT",
      "source" : "EXPLICIT",
      "hitCount" : 5,
      "createdAt" : "{dt(-14, 1.5)}",
      "lastAccessed" : "{dt(-5, -3)}",
      "confidence" : 0.9,
      "status" : null,
      "verifiedAt" : "{dt(-14, 1.5)}",
      "verifiedSource" : "EXPLICIT"
    }},
    "learning_interest" : {{
      "content" : "对{PERSONA_INTERESTS}有浓厚兴趣",
      "memoryType" : "USER_INSIGHT",
      "source" : "IMPLICIT",
      "hitCount" : 7,
      "createdAt" : "{dt(-12, -2)}",
      "lastAccessed" : "{dt(-1, 0)}",
      "confidence" : 0.85,
      "status" : null,
      "verifiedAt" : null,
      "verifiedSource" : null
    }},
    "work_habit" : {{
      "content" : "{PERSONA_HABIT}",
      "memoryType" : "USER_INSIGHT",
      "source" : "EXPLICIT",
      "hitCount" : 4,
      "createdAt" : "{dt(-8, -1)}",
      "lastAccessed" : "{dt(-1, 0)}",
      "confidence" : 0.9,
      "status" : null,
      "verifiedAt" : "{dt(-8, -1)}",
      "verifiedSource" : "EXPLICIT"
    }},
    "diet_preference" : {{
      "content" : "不爱吃鱼",
      "memoryType" : "USER_INSIGHT",
      "source" : "EXPLICIT",
      "hitCount" : 15,
      "createdAt" : "{dt(-6, 0)}",
      "lastAccessed" : "{dt(0, 1)}",
      "confidence" : 0.95,
      "status" : null,
      "verifiedAt" : "{dt(-6, 0)}",
      "verifiedSource" : "EXPLICIT"
    }},
    "current_course" : {{
      "content" : "{PERSONA_COURSE}",
      "memoryType" : "USER_INSIGHT",
      "source" : "IMPLICIT",
      "hitCount" : 3,
      "createdAt" : "{dt(-10, 0)}",
      "lastAccessed" : "{dt(-2, 0)}",
      "confidence" : 0.8,
      "status" : null,
      "verifiedAt" : null,
      "verifiedSource" : null
    }}
  }}
}}
-->"""
    write_text(MEMORY_DIR / "user-insights.md", content)


# ========== 4. metadata.json ==========
def gen_metadata():
    data = {
        "user_metadata": {
            "name": PERSONA_NAME,
            "major": PERSONA_MAJOR,
            "university": "某某大学",
            "grade": "大四",
            "project": PERSONA_PROJECT
        },
        "assistant_preferences": {
            "language": "zh-CN",
            "tone": "friendly",
            "detail_level": "moderate",
            "code_style": "Java"
        },
        "global_controls": {
            "memory_enabled": True,
            "history_enabled": True,
            "rag_enabled": True,
            "skill_enabled": True,
            "summary_enabled": True,
            "reflection_enabled": True,
            "evidence_trace_enabled": True
        }
    }
    write_json(MEMORY_DIR / "metadata.json", data)


# ========== 5. memory_evidence_traces.jsonl ==========
def gen_evidence_traces():
    traces = [
        {
            "timestamp": dt_iso(-5, -3),
            "user_message": "你能记住之前我告诉过你的事情吗？",
            "memory_loaded": True,
            "reflection": {
                "needs_memory": True,
                "memory_purpose": "USER_PROFILE",
                "reason": "用户在验证记忆系统的记忆能力，需要加载所有已记录的用户画像信息来展示记忆效果。",
                "confidence": 0.9,
                "retrieval_hint": "加载全部用户洞察，优先展示显式记忆",
                "evidence_types": ["USER_INSIGHT", "RECENT_HISTORY"],
                "evidence_purposes": ["user_profile", "continuity"]
            },
            "retrieved_insights": [
                "name: 张明",
                "major: 计算机科学与技术专业大四学生",
                "graduation_project: 四层智能记忆管理系统，使用Java和Spring Boot开发",
                "programming_language_preference: Java、Python、正在学Rust",
                "ide_preference: IntelliJ IDEA",
                "learning_interest: 分布式系统、智能对话系统、RAG技术",
                "work_habit: 喜欢晚上写代码，22:00-02:00效率最高",
                "diet_preference: 不爱吃鱼"
            ],
            "used_insights": [
                "name: 张明",
                "major: 计算机科学与技术专业大四学生",
                "graduation_project: 四层智能记忆管理系统",
                "programming_language_preference: Java",
                "ide_preference: IntelliJ IDEA",
                "work_habit: 晚上写代码",
                "diet_preference: 不爱吃鱼"
            ],
            "retrieved_examples": [],
            "used_examples": [],
            "loaded_skills": [],
            "used_skills": [],
            "retrieved_tasks": [
                "[待触发] 提交毕设中期检查报告 @ 2026-04-06 15:00",
                "[待触发] 开组会 @ 2026-04-08 10:00"
            ],
            "used_tasks": ["[待触发] 提交毕设中期检查报告"],
            "used_evidence_summary": "insights 8/8, examples 0/0, skills 0/0, tasks 1/2"
        },
        {
            "timestamp": dt_iso(-4, 0),
            "user_message": "我想了解一下RAG在记忆系统里是怎么工作的",
            "memory_loaded": True,
            "reflection": {
                "needs_memory": True,
                "memory_purpose": "CONTEXT_ENRICHMENT",
                "reason": "用户询问RAG技术细节，属于其毕设项目相关话题，需要加载项目上下文和用户技术背景来给出有针对性的回答。",
                "confidence": 0.85,
                "retrieval_hint": "检索与RAG、向量检索、LangChain相关的记忆和技能",
                "evidence_types": ["USER_INSIGHT", "RECENT_HISTORY", "SKILL"],
                "evidence_purposes": ["context_enrichment"]
            },
            "retrieved_insights": [
                "graduation_project: 四层智能记忆管理系统",
                "learning_interest: 分布式系统、智能对话系统、RAG技术",
                "current_course: 正在学《大语言模型应用开发》课程"
            ],
            "used_insights": [
                "graduation_project: 四层智能记忆管理系统",
                "learning_interest: RAG技术"
            ],
            "retrieved_examples": [],
            "used_examples": [],
            "loaded_skills": ["rag-guide"],
            "used_skills": ["rag-guide"],
            "retrieved_tasks": [],
            "used_tasks": [],
            "used_evidence_summary": "insights 2/3, examples 0/0, skills 1/1, tasks 0/0"
        },
        {
            "timestamp": dt_iso(-2, 0),
            "user_message": "你推荐用什么向量数据库？",
            "memory_loaded": True,
            "reflection": {
                "needs_memory": True,
                "memory_purpose": "CONTEXT_ENRICHMENT",
                "reason": "用户在讨论技术选型，需要了解其项目背景和技术栈来做有针对性的推荐。",
                "confidence": 0.8,
                "retrieval_hint": "加载项目上下文和用户技术偏好",
                "evidence_types": ["USER_INSIGHT", "RECENT_HISTORY"],
                "evidence_purposes": ["context_enrichment"]
            },
            "retrieved_insights": [
                "graduation_project: 四层智能记忆管理系统",
                "programming_language_preference: Java",
                "learning_interest: RAG技术"
            ],
            "used_insights": [
                "graduation_project: 四层智能记忆管理系统",
                "programming_language_preference: Java"
            ],
            "retrieved_examples": [],
            "used_examples": [],
            "loaded_skills": [],
            "used_skills": [],
            "retrieved_tasks": [],
            "used_tasks": [],
            "used_evidence_summary": "insights 2/3, examples 0/0, skills 0/0, tasks 0/0"
        },
        {
            "timestamp": dt_iso(-1, 0),
            "user_message": "你能不能分析一下当前记忆系统的使用情况？",
            "memory_loaded": True,
            "reflection": {
                "needs_memory": True,
                "memory_purpose": "CONTINUITY",
                "reason": "用户要求分析系统使用情况，需要加载所有记忆和追踪数据来提供完整分析。",
                "confidence": 0.9,
                "retrieval_hint": "加载全部用户洞察、会话摘要和任务记录",
                "evidence_types": ["USER_INSIGHT", "SESSION_SUMMARY", "RECENT_HISTORY"],
                "evidence_purposes": ["continuity", "analytics"]
            },
            "retrieved_insights": [
                "name: 张明", "major: 计算机科学与技术",
                "graduation_project: 四层智能记忆管理系统",
                "programming_language_preference: Java",
                "work_habit: 晚上写代码",
                "diet_preference: 不爱吃鱼"
            ],
            "used_insights": [
                "name: 张明", "graduation_project: 四层智能记忆管理系统",
                "programming_language_preference: Java"
            ],
            "retrieved_examples": [],
            "used_examples": [],
            "loaded_skills": [],
            "used_skills": [],
            "retrieved_tasks": [
                "[已完成] 提交毕设中期检查报告",
                "[已触发] 开组会 @ 2026-04-08 10:00"
            ],
            "used_tasks": ["[已完成] 提交毕设中期检查报告"],
            "used_evidence_summary": "insights 3/6, examples 0/0, skills 0/0, tasks 1/2"
        },
        {
            "timestamp": dt_iso(0, 0),
            "user_message": "给我总结一下我们所有的对话记录",
            "memory_loaded": True,
            "reflection": {
                "needs_memory": True,
                "memory_purpose": "CONTINUITY",
                "reason": "用户要求总结所有对话记录，需要加载全部历史上下文和会话摘要。",
                "confidence": 0.95,
                "retrieval_hint": "加载全部会话摘要、用户洞察和对话历史",
                "evidence_types": ["SESSION_SUMMARY", "USER_INSIGHT", "RECENT_HISTORY"],
                "evidence_purposes": ["continuity"]
            },
            "retrieved_insights": [
                "name: 张明", "major: 计算机科学与技术",
                "graduation_project: 四层智能记忆管理系统",
                "programming_language_preference: Java、Python、Rust",
                "learning_interest: 分布式系统、RAG",
                "work_habit: 晚上写代码"
            ],
            "used_insights": [
                "name: 张明", "graduation_project: 四层智能记忆管理系统",
                "programming_language_preference: Java"
            ],
            "retrieved_examples": [],
            "used_examples": [],
            "loaded_skills": [],
            "used_skills": [],
            "retrieved_tasks": [],
            "used_tasks": [],
            "used_evidence_summary": "insights 3/6, examples 0/0, skills 0/0, tasks 0/0"
        },
        {
            "timestamp": dt_iso(-7, 2),
            "user_message": "帮我创建一个任务，后天下午3点提醒我提交毕设中期检查报告",
            "memory_loaded": False,
            "reflection": {
                "needs_memory": False,
                "reason": "用户发出了明确的任务创建指令，包含具体时间和事件，不需要加载历史记忆即可处理。",
                "evidence_purposes": []
            },
            "retrieved_insights": [],
            "used_insights": [],
            "retrieved_examples": [],
            "used_examples": [],
            "loaded_skills": [],
            "used_skills": [],
            "retrieved_tasks": [],
            "used_tasks": [],
            "used_evidence_summary": "反思判断不需要记忆，已跳过长期证据加载。"
        },
        {
            "timestamp": dt_iso(-8, -1),
            "user_message": "我一般晚上写代码效率最高，大概22点到凌晨2点",
            "memory_loaded": True,
            "reflection": {
                "needs_memory": True,
                "memory_purpose": "USER_PROFILE",
                "reason": "用户在分享个人习惯信息，需要加载已有画像来更新或补充用户洞察。",
                "confidence": 0.7,
                "retrieval_hint": "加载已有用户洞察以检测冲突或补充",
                "evidence_types": ["USER_INSIGHT"],
                "evidence_purposes": ["user_profile"]
            },
            "retrieved_insights": [
                "name: 张明", "major: 计算机科学与技术",
                "graduation_project: 四层智能记忆管理系统",
                "programming_language_preference: Java"
            ],
            "used_insights": [],
            "retrieved_examples": [],
            "used_examples": [],
            "loaded_skills": [],
            "used_skills": [],
            "retrieved_tasks": [],
            "used_tasks": [],
            "used_evidence_summary": "insights 0/3 (仅读取，未直接引用), examples 0/0, skills 0/0, tasks 0/0"
        },
        {
            "timestamp": dt_iso(-6, 0),
            "user_message": "我不太爱吃鱼，食堂的鱼每次都避开",
            "memory_loaded": False,
            "reflection": {
                "needs_memory": False,
                "reason": "用户在表达饮食偏好，属于简单的个人喜好陈述，不需要加载历史记忆来处理。",
                "evidence_purposes": []
            },
            "retrieved_insights": [],
            "used_insights": [],
            "retrieved_examples": [],
            "used_examples": [],
            "loaded_skills": [],
            "used_skills": [],
            "retrieved_tasks": [],
            "used_tasks": [],
            "used_evidence_summary": "反思判断不需要记忆，已跳过长期证据加载。"
        },
        {
            "timestamp": dt_iso(0, -2),
            "user_message": "下周一下午2点提醒我找导师讨论论文",
            "memory_loaded": False,
            "reflection": {
                "needs_memory": False,
                "reason": "用户发出任务创建指令，时间明确，不需要历史记忆。",
                "evidence_purposes": []
            },
            "retrieved_insights": [],
            "used_insights": [],
            "retrieved_examples": [],
            "used_examples": [],
            "loaded_skills": [],
            "used_skills": [],
            "retrieved_tasks": [],
            "used_tasks": [],
            "used_evidence_summary": "反思判断不需要记忆，已跳过长期证据加载。"
        },
        {
            "timestamp": dt_iso(-10, 0),
            "user_message": "最近在学LangChain和RAG相关的技术，想在我的毕设里加入向量检索",
            "memory_loaded": True,
            "reflection": {
                "needs_memory": True,
                "memory_purpose": "USER_PROFILE",
                "reason": "用户在更新技术学习方向，与毕设项目相关，需要加载已有画像以补充更新。",
                "confidence": 0.75,
                "retrieval_hint": "加载项目背景和技术栈信息",
                "evidence_types": ["USER_INSIGHT"],
                "evidence_purposes": ["user_profile"]
            },
            "retrieved_insights": [
                "graduation_project: 四层智能记忆管理系统",
                "programming_language_preference: Java"
            ],
            "used_insights": ["graduation_project: 四层智能记忆管理系统"],
            "retrieved_examples": [],
            "used_examples": [],
            "loaded_skills": [],
            "used_skills": [],
            "retrieved_tasks": [],
            "used_tasks": [],
            "used_evidence_summary": "insights 1/2, examples 0/0, skills 0/0, tasks 0/0"
        }
    ]
    append_jsonl(MEMORY_DIR / "memory_evidence_traces.jsonl", traces)


# ========== 6. session_summaries.jsonl ==========
def gen_session_summaries():
    summaries = [
        {
            "generated_at": dt_iso(-14, 2),
            "summary": "初次对话：用户张明自我介绍为计算机科学与技术专业大四学生，正在做四层智能记忆管理系统的毕业设计，使用Java和Spring Boot技术栈。讨论了项目基本架构和技术选型。",
            "key_topics": ["自我介绍", "毕设项目", "技术栈", "Java", "Spring Boot"],
            "turn_count": 6,
            "time_range": "2026-03-27 16:00 ~ 2026-03-27 22:00",
            "from_turn": 1,
            "to_turn": 6,
            "summary_type": "session",
            "trigger": "turn_threshold"
        },
        {
            "generated_at": dt_iso(-10, 2),
            "summary": "技术学习讨论：用户分享了正在学习Rust和LangChain/RAG技术的计划，讨论了在毕设中引入向量检索的方案，选择了AllMiniLML6V2作为embedding模型。用户还提到了日常编程习惯。",
            "key_topics": ["Rust学习", "LangChain", "RAG", "向量检索", "AllMiniLML6V2", "编程习惯"],
            "turn_count": 8,
            "time_range": "2026-03-31 18:00 ~ 2026-04-01 22:00",
            "from_turn": 7,
            "to_turn": 14,
            "summary_type": "session",
            "trigger": "topic_shift",
            "previous_topic": "个人背景介绍",
            "current_topic": "技术学习与选型"
        },
        {
            "generated_at": dt_iso(-5, -2),
            "summary": "记忆系统验证与技术讨论：用户验证了记忆系统是否正常工作，系统成功回忆了之前记录的多条用户画像信息。随后讨论了RAG在记忆系统中的工作原理和向量数据库选型。",
            "key_topics": ["记忆验证", "RAG原理", "向量数据库", "记忆反思"],
            "turn_count": 7,
            "time_range": "2026-04-05 15:00 ~ 2026-04-05 18:00",
            "from_turn": 15,
            "to_turn": 21,
            "summary_type": "session",
            "trigger": "topic_shift",
            "previous_topic": "日常任务管理",
            "current_topic": "系统功能验证"
        },
        {
            "generated_at": dt_iso(-1, 1),
            "summary": "系统分析与任务管理：用户要求分析记忆系统使用情况，系统提供了记忆追踪统计。用户创建了找导师讨论论文的提醒任务。讨论了任务完成情况和下周计划。",
            "key_topics": ["系统分析", "记忆追踪", "任务管理", "导师讨论", "论文"],
            "turn_count": 5,
            "time_range": "2026-04-09 16:00 ~ 2026-04-09 21:00",
            "from_turn": 22,
            "to_turn": 26,
            "summary_type": "session",
            "trigger": "turn_threshold"
        }
    ]
    append_jsonl(MEMORY_DIR / "session_summaries.jsonl", summaries)


# ========== 7. scheduled_tasks.json ==========
def gen_scheduled_tasks():
    tasks = [
        {
            "id": "task-001-midterm",
            "title": "提交毕设中期检查报告",
            "detail": "需要准备中期检查PPT和进展报告，提交到教务系统",
            "dueAt": local_dt_array(-5, 15),
            "createdAt": local_dt_array(-7, 2),
            "triggeredAt": local_dt_array(-5, 15),
            "executedAt": None,
            "status": "triggered",
            "executeCommand": None,
            "executeTimeoutSeconds": None,
            "executionStatus": "skipped",
            "executionExitCode": None,
            "executionOutput": None,
            "recurrenceType": "none",
            "recurrenceInterval": 0,
            "reminderLeadMinutes": None,
            "upcomingReminderSentAt": None,
            "sourceMessage": "帮我创建一个任务，后天下午3点提醒我提交毕设中期检查报告",
            "sourcePlatform": "cli",
            "sourceConversationId": "personal:user_default",
            "sourceSenderId": "user_default"
        },
        {
            "id": "task-002-meeting",
            "title": "开组会",
            "detail": "本周组会，需要汇报毕设进展",
            "dueAt": local_dt_array(-3, 10),
            "createdAt": local_dt_array(-3, -2),
            "triggeredAt": local_dt_array(-3, 10),
            "executedAt": None,
            "status": "triggered",
            "executeCommand": None,
            "executeTimeoutSeconds": None,
            "executionStatus": "skipped",
            "executionExitCode": None,
            "executionOutput": None,
            "recurrenceType": "none",
            "recurrenceInterval": 0,
            "reminderLeadMinutes": None,
            "upcomingReminderSentAt": None,
            "sourceMessage": "明天上午10点提醒我开组会",
            "sourcePlatform": "cli",
            "sourceConversationId": "personal:user_default",
            "sourceSenderId": "user_default"
        },
        {
            "id": "task-003-advisor",
            "title": "找导师讨论论文",
            "detail": "带上中期检查报告和当前进展，讨论论文结构和后续计划",
            "dueAt": local_dt_array(3, 14),
            "createdAt": local_dt_array(0, -2),
            "triggeredAt": None,
            "executedAt": None,
            "status": "pending",
            "executeCommand": None,
            "executeTimeoutSeconds": None,
            "executionStatus": None,
            "executionExitCode": None,
            "executionOutput": None,
            "recurrenceType": "none",
            "recurrenceInterval": 0,
            "reminderLeadMinutes": None,
            "upcomingReminderSentAt": None,
            "sourceMessage": "下周一下午2点提醒我找导师讨论论文",
            "sourcePlatform": "cli",
            "sourceConversationId": "personal:user_default",
            "sourceSenderId": "user_default"
        },
        {
            "id": "task-004-rust",
            "title": "完成Rust第一章节练习",
            "detail": "Rust By Example第1-3章练习",
            "dueAt": local_dt_array(5, 22),
            "createdAt": local_dt_array(-2, 3),
            "triggeredAt": None,
            "executedAt": None,
            "status": "pending",
            "executeCommand": None,
            "executeTimeoutSeconds": None,
            "executionStatus": None,
            "executionExitCode": None,
            "executionOutput": None,
            "recurrenceType": "none",
            "recurrenceInterval": 0,
            "reminderLeadMinutes": None,
            "upcomingReminderSentAt": None,
            "sourceMessage": "帮我创建一个任务，本周五晚上10点前完成Rust第一章节练习",
            "sourcePlatform": "cli",
            "sourceConversationId": "personal:user_default",
            "sourceSenderId": "user_default"
        }
    ]
    write_json(MEMORY_DIR / "scheduled_tasks.json", tasks)


# ========== 8. pending_explicit_memories.jsonl (governance demo) ==========
def gen_pending_memories():
    records = [
        {
            "slot_name": "programming_language_preference",
            "new_content": "最近更偏向用Python做原型开发，Java做正式项目",
            "existing_content": "主力语言是Java、Python、正在学Rust",
            "source": "EXPLICIT",
            "confidence": 0.7,
            "status": "CONFLICT",
            "detected_at": dt_iso(-3, 1)
        },
        {
            "slot_name": "work_schedule",
            "new_content": "最近调整作息，改成早上9点到下午5点写代码",
            "existing_content": "喜欢晚上写代码，一般22:00-02:00效率最高",
            "source": "IMPLICIT",
            "confidence": 0.5,
            "status": "CONFLICT",
            "detected_at": dt_iso(-1, -1)
        }
    ]
    append_jsonl(MEMORY_DIR / "pending_explicit_memories.jsonl", records)


# ========== 9. eval_results.jsonl ==========
def gen_eval_results():
    results = [
        {
            "timestamp": dt_iso(-3, 5),
            "question": "根据我的情况，你觉得我应该用什么技术栈做毕设？",
            "response_without_memory_length": 156,
            "response_with_memory_length": 312,
            "scores_without_memory": {"relevance": 5, "personalization": 2, "accuracy": 6, "helpfulness": 4},
            "scores_with_memory": {"relevance": 8, "personalization": 9, "accuracy": 7, "helpfulness": 8},
            "total_score_without_memory": 4.25,
            "total_score_with_memory": 8.0,
            "improvement_percent": 88.24,
            "justification_without": "回答较为泛化，没有考虑用户的具体背景",
            "justification_with": "结合用户Java背景、毕设项目方向给出了针对性建议"
        },
        {
            "timestamp": dt_iso(-3, 5.5),
            "question": "我之前跟你说过我的专业和项目吗？",
            "response_without_memory_length": 89,
            "response_with_memory_length": 267,
            "scores_without_memory": {"relevance": 4, "personalization": 1, "accuracy": 5, "helpfulness": 3},
            "scores_with_memory": {"relevance": 9, "personalization": 10, "accuracy": 9, "helpfulness": 9},
            "total_score_without_memory": 3.25,
            "total_score_with_memory": 9.25,
            "improvement_percent": 184.62,
            "justification_without": "无法回忆任何用户信息",
            "justification_with": "准确回忆了用户的专业、项目、技术栈等多条信息"
        },
        {
            "timestamp": dt_iso(-3, 6),
            "question": "推荐一些适合我的学习方法",
            "response_without_memory_length": 203,
            "response_with_memory_length": 298,
            "scores_without_memory": {"relevance": 6, "personalization": 2, "accuracy": 6, "helpfulness": 5},
            "scores_with_memory": {"relevance": 8, "personalization": 8, "accuracy": 7, "helpfulness": 8},
            "total_score_without_memory": 4.75,
            "total_score_with_memory": 7.75,
            "improvement_percent": 63.16,
            "justification_without": "提供了通用的学习方法建议",
            "justification_with": "结合用户晚间编程习惯和当前学习Rust/LangChain的背景给出个性化建议"
        }
    ]
    append_jsonl(MEMORY_DIR / "eval_results.jsonl", results)


# ========== 10. benchmark_reports.jsonl ==========
def gen_benchmark_reports():
    reports = [
        {
            "timestamp": dt_iso(-3, 6.5),
            "dataset_source": "default",
            "total_questions": 3,
            "completed_questions": 3,
            "average_score_without_memory": 4.08,
            "average_score_with_memory": 8.33,
            "average_improvement_percent": 111.97,
            "best_question": "我之前跟你说过我的专业和项目吗？",
            "best_improvement_percent": 184.62,
            "worst_question": "推荐一些适合我的学习方法",
            "worst_improvement_percent": 63.16,
            "questions": [
                "根据我的情况，你觉得我应该用什么技术栈做毕设？",
                "我之前跟你说过我的专业和项目吗？",
                "推荐一些适合我的学习方法"
            ]
        }
    ]
    append_jsonl(MEMORY_DIR / "benchmark_reports.jsonl", reports)


# ========== 11. weekly_reviews.jsonl ==========
def gen_weekly_reviews():
    reviews = [
        {
            "generated_at": dt_iso(-8, 0),
            "scope": "personal:user_default",
            "completed_tasks": 1,
            "delayed_tasks": 0,
            "due_next_week": 2,
            "top_topics": ["毕设项目", "RAG技术", "Rust学习"],
            "suggestions": [
                "继续推进毕设核心功能开发",
                "安排时间学习Rust基础语法",
                "准备中期检查材料"
            ],
            "report_text": "本周完成情况：\n- 已完成任务：1项\n- 待办延迟：0项\n- 下周待办：2项\n\n主要话题：毕设项目、RAG技术、Rust学习\n\n建议：\n1. 继续推进毕设核心功能开发\n2. 安排时间学习Rust基础语法\n3. 准备中期检查材料"
        },
        {
            "generated_at": dt_iso(-1, 2),
            "scope": "personal:user_default",
            "completed_tasks": 2,
            "delayed_tasks": 0,
            "due_next_week": 2,
            "top_topics": ["记忆系统验证", "论文讨论", "向量数据库选型"],
            "suggestions": [
                "准备导师会议材料，带上中期检查报告",
                "完成Rust第一章节练习",
                "开始整理论文第三章架构设计内容"
            ],
            "report_text": "本周完成情况：\n- 已完成任务：2项（提交中期检查报告、开组会）\n- 待办延迟：0项\n- 下周待办：2项（找导师讨论论文、Rust练习）\n\n主要话题：记忆系统验证、论文讨论、向量数据库选型\n\n建议：\n1. 准备导师会议材料，带上中期检查报告\n2. 完成Rust第一章节练习\n3. 开始整理论文第三章架构设计内容"
        }
    ]
    append_jsonl(MEMORY_DIR / "weekly_reviews.jsonl", reviews)


# ========== 12. proactive_reminders.jsonl ==========
def gen_proactive_reminders():
    reminders = [
        {
            "generated_at": dt_iso(-6, -1),
            "reminder_text": "张明，你之前提到在学LangChain和RAG技术，建议在学习过程中结合你的毕设项目做实践，比如先用本地向量索引实现一个简单的记忆检索功能，然后再逐步优化。",
            "reminder_type": "learning_suggestion",
            "based_on_memories": ["learning_interest", "graduation_project", "current_course"],
            "suggested_action": "在毕设项目中实现一个RAG检索的原型"
        },
        {
            "generated_at": dt_iso(-2, -1),
            "reminder_text": "根据你的编程习惯，今晚22点后是你效率最高的时间段。如果你已经准备好中期检查报告，可以利用今晚的时间整理一下论文第三章的架构设计内容。",
            "reminder_type": "productivity_tip",
            "based_on_memories": ["work_habit", "graduation_project"],
            "suggested_action": "利用晚间高效时段整理论文架构设计章节"
        }
    ]
    append_jsonl(MEMORY_DIR / "proactive_reminders.jsonl", reminders)


# ========== 13. identity_mappings.json ==========
def gen_identity_mappings():
    mappings = {
        "user_default": {
            "unifiedId": "user_default",
            "displayName": PERSONA_NAME,
            "platformBindings": {"cli": "default"},
            "platformConversations": {},
            "createdAt": local_dt_array(-15, -4)
        },
        "user_feishu_ou_87d0eb26fde3f055bd9263769cd43060": {
            "unifiedId": "user_feishu_ou_87d0eb26fde3f055bd9263769cd43060",
            "displayName": PERSONA_NAME,
            "platformBindings": {"feishu": "ou_87d0eb26fde3f055bd9263769cd43060"},
            "platformConversations": {"feishu": "oc_fc10133d11a2dd42126e4c0f02cc2817"},
            "createdAt": local_dt_array(-15, -3)
        },
        "user_tg_12345678": {
            "unifiedId": "user_tg_12345678",
            "displayName": PERSONA_NAME,
            "platformBindings": {"telegram": "12345678"},
            "platformConversations": {},
            "createdAt": local_dt_array(-10, 0)
        }
    }
    write_json(MEMORY_DIR / "identity_mappings.json", mappings)


# ========== 14. Skills ==========
def gen_skills():
    SKILLS_DIR.mkdir(parents=True, exist_ok=True)

    write_text(SKILLS_DIR / "rag-guide.md", """# RAG 检索指南

## 适用场景
当用户询问与历史经验、知识检索、语义匹配相关的问题时加载此技能。

## 核心概念
- **Embedding**: 将文本转换为固定维度的向量表示
- **Similarity Search**: 通过余弦相似度找到最相关的文档
- **Context Injection**: 将检索结果注入到 prompt 中作为上下文

## 本系统实现
- 使用 AllMiniLML6V2 模型（384维向量）
- 存储在本地 `vector_store.json`
- 支持作用域隔离（personal/team）

## 最佳实践
1. 检索时设置合理的相似度阈值（默认 0.35）
2. 控制返回结果数量（默认最多 5 条）
3. 对检索结果进行相关性排序后再注入
""")

    write_text(SKILLS_DIR / "memory-architecture.md", """# 四层记忆架构说明

## 架构概览
本系统采用四层记忆架构，每层负责不同类型的记忆管理。

## Layer 1: 短期记忆
- 保存最近的对话窗口（recent_user_messages.jsonl）
- 维持会话连续性

## Layer 2: 元数据
- 用户环境信息、助手偏好、全局控制项
- 存储在 metadata.json

## Layer 3: 用户洞察
- 长期画像、偏好、背景信息
- 显式提取（用户直接告知）和隐式提取（从对话中推断）
- 存储在 user-insights.md

## Layer 4: 持续学习知识
- **Layer 4a Skill**: 方法论型知识，按需加载
- **Layer 4b Example/RAG**: 历史案例与语义检索复用

## 关键设计
- 记忆反思（Reflection）：在每轮对话前判断是否需要加载长期记忆
- 证据链（Evidence Trace）：记录每轮对话中记忆的使用情况
""")

    write_text(SKILLS_DIR / "debug-methodology.md", """# 调试方法论

## 适用场景
当用户遇到技术问题需要排查时加载此技能。

## 调试步骤
1. **复现问题**: 确认问题可以稳定复现
2. **隔离变量**: 逐步排除无关因素
3. **日志分析**: 检查相关日志输出
4. **假设验证**: 提出可能的原因并逐一验证
5. **修复确认**: 修复后确认问题不再出现

## 常用工具
- `/memory-debug`: 查看记忆反思与证据链
- `/memory-report`: 查看系统综合状态
- `/memory-governance`: 查看记忆冲突和待审核项
""")


# ========== 15. benchmark_questions.txt ==========
def gen_benchmark_questions():
    questions = """# Benchmark 题集 - Memory Box 评测
# 每行一个问题，# 开头为注释

根据我之前告诉你的信息，我适合做什么方向的研究？
你记得我叫什么名字吗？
我平时用什么IDE写代码？
我有什么饮食习惯需要注意的？
推荐一本适合我当前水平的编程书
根据我的毕设项目，我应该重点学习哪些技术？
帮我分析一下我的技术栈优势和不足
我之前说过我想学什么新语言？
你觉得我应该在什么时间段安排深度学习比较好？
根据我的背景，给我一些毕业后的职业建议
"""
    write_text(MEMORY_DIR / "benchmark_questions.txt", questions)


# ========== 16. vector_store.json ==========
def gen_vector_store():
    # Use dummy embeddings (384 floats) - the real ones would be computed by AllMiniLML6V2
    # For demo purposes, these just need to exist so /rag-stats shows content
    dummy_embedding = [0.01 * (i % 10 - 5) for i in range(384)]

    def make_entry(slot_name: str, content: str, created_offset_days: float):
        entry = {
            "id": f"memory:{slot_name}",
            "content": content,
            "embedding": dummy_embedding,
            "metadata": {
                "memory_type": "USER_INSIGHT",
                "created_at": dt_iso(created_offset_days, 0),
                "source": "EXPLICIT",
                "slot_name": slot_name
            },
            "createdAt": local_dt_array(created_offset_days, 0),
            "lastAccessed": local_dt_array(0, 1),
            "hitCount": 5
        }
        return entry

    def make_scoped_entry(scope: str, slot_name: str, content: str, created_offset_days: float):
        entry = {
            "id": f"{scope}|memory:{slot_name}",
            "content": content,
            "embedding": dummy_embedding,
            "metadata": {
                "memory_type": "USER_INSIGHT",
                "created_at": dt_iso(created_offset_days, 0),
                "source": "EXPLICIT",
                "slot_name": slot_name,
                "scope": scope
            },
            "createdAt": local_dt_array(created_offset_days, 0),
            "lastAccessed": local_dt_array(0, 1),
            "hitCount": 3
        }
        return entry

    store = {}
    insights = [
        ("name", PERSONA_NAME, -15),
        ("major", f"{PERSONA_MAJOR}专业大四学生", -15),
        ("graduation_project", PERSONA_PROJECT, -15),
        ("programming_language_preference", PERSONA_LANGS, -14),
        ("ide_preference", PERSONA_EDITOR, -14),
        ("learning_interest", PERSONA_INTERESTS, -12),
        ("work_habit", PERSONA_HABIT, -8),
        ("diet_preference", "不爱吃鱼", -6),
        ("current_course", PERSONA_COURSE, -10),
    ]

    for slot, content, offset in insights:
        store[f"memory:{slot}"] = make_entry(slot, content, offset)
        scope = "personal:user_default"
        store[f"{scope}|memory:{slot}"] = make_scoped_entry(scope, slot, content, offset)

    write_json(MEMORY_DIR / "vector_store.json", store)


# ========== 17. memory_queues.json ==========
def gen_memory_queues():
    queues = {
        "youngQueue": [],
        "matureQueue": ["diet_preference", "programming_language_preference", "name", "graduation_project"]
    }
    write_json(MEMORY_DIR / "memory_queues.json", queues)


# ========== 18. topic_summaries.jsonl ==========
def gen_topic_summaries():
    summaries = [
        {
            "generated_at": dt_iso(-10, 2),
            "summary": "从个人背景介绍转向技术学习讨论，用户分享了Rust和LangChain/RAG的学习计划。",
            "previous_topic": "个人背景介绍",
            "current_topic": "技术学习与选型",
            "session_summary_generated_at": dt_iso(-10, 2)
        }
    ]
    append_jsonl(MEMORY_DIR / "topic_summaries.jsonl", summaries)


# ========== 19. milestone_summaries.jsonl ==========
def gen_milestone_summaries():
    summaries = [
        {
            "generated_at": dt_iso(-5, -2),
            "summary": "记忆系统验证通过：用户测试了跨会话记忆能力，系统成功回忆了8条用户画像信息，包括姓名、专业、项目、技术栈、IDE、学习兴趣、工作习惯和饮食偏好。",
            "key_topics": ["记忆验证", "跨会话记忆", "用户画像"],
            "session_summary_generated_at": dt_iso(-5, -2),
            "milestone_type": "capability_validation"
        }
    ]
    append_jsonl(MEMORY_DIR / "milestone_summaries.jsonl", summaries)


# ========== Main ==========
def main():
    if "--backup" in sys.argv:
        backup_dir = MEMORY_DIR.parent / ".memory_backup"
        if MEMORY_DIR.exists():
            if backup_dir.exists():
                shutil.rmtree(backup_dir)
            shutil.copytree(MEMORY_DIR, backup_dir)
            print(f"已备份 .memory/ 到 {backup_dir}")

    print("正在生成演示数据...")

    gen_conversation_history()
    print("  conversation_history.jsonl  [40+ 对话轮次]")

    gen_recent_user_messages()
    print("  recent_user_messages.jsonl   [16 条消息]")

    gen_user_insights()
    print("  user-insights.md             [9 条用户画像]")

    gen_metadata()
    print("  metadata.json                [元数据与控制]")

    gen_evidence_traces()
    print("  memory_evidence_traces.jsonl [10 条证据追踪]")

    gen_session_summaries()
    print("  session_summaries.jsonl      [4 条会话摘要]")

    gen_scheduled_tasks()
    print("  scheduled_tasks.json         [4 个任务]")

    gen_pending_memories()
    print("  pending_explicit_memories.jsonl [2 条待审核]")

    gen_eval_results()
    print("  eval_results.jsonl           [3 条评测结果]")

    gen_benchmark_reports()
    print("  benchmark_reports.jsonl      [1 份批次报告]")

    gen_weekly_reviews()
    print("  weekly_reviews.jsonl         [2 份周报]")

    gen_proactive_reminders()
    print("  proactive_reminders.jsonl    [2 条主动提醒]")

    gen_identity_mappings()
    print("  identity_mappings.json       [3 个身份映射]")

    gen_skills()
    print("  skills/                      [3 个技能文件]")

    gen_benchmark_questions()
    print("  benchmark_questions.txt      [10 道评测题]")

    gen_vector_store()
    print("  vector_store.json            [18 条向量记录]")

    gen_memory_queues()
    print("  memory_queues.json           [队列状态]")

    gen_topic_summaries()
    print("  topic_summaries.jsonl        [1 条话题摘要]")

    gen_milestone_summaries()
    print("  milestone_summaries.jsonl    [1 条里程碑]")

    print("\n演示数据生成完毕！")
    print(f"数据目录: {MEMORY_DIR}")
    print("\n现在可以运行 java -jar target/memory-box-1.0.0.jar 查看效果。")
    print("建议展示命令：")
    print("  /what-you-know          - 查看完整用户画像")
    print("  /memory-debug 10        - 查看证据追踪")
    print("  /memory-timeline        - 查看时间线")
    print("  /memory-report          - 综合状态报告")
    print("  /memory-governance      - 记忆治理（冲突审核）")
    print("  /tasks                  - 任务列表")
    print("  /weekly-report          - 周报")
    print("  /proactive-reminders    - 主动提醒")
    print("  /benchmark reports 5    - 评测报告")
    print("  /skills                 - 技能列表")
    print("  /identity               - 统一身份")
    print("  /rag-stats              - 向量索引统计")
    print("  /search Java            - 语义检索")


if __name__ == "__main__":
    main()
