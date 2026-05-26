from __future__ import annotations

from types import SimpleNamespace

from diffguard_agent.agent.pipeline.stages.base import TokenUsageTracker


def _mk_response(*, llm_output=None, generation_info=None, usage_metadata=None):
    gen = SimpleNamespace(
        generation_info=generation_info or {},
        message=SimpleNamespace(usage_metadata=usage_metadata) if usage_metadata is not None else None,
    )
    return SimpleNamespace(
        llm_output=llm_output,
        generations=[[gen]],
    )


def test_tracker_reads_llm_output_token_usage():
    tracker = TokenUsageTracker()
    response = _mk_response(
        llm_output={"token_usage": {"prompt_tokens": 10, "completion_tokens": 7, "total_tokens": 17}},
    )

    tracker.on_llm_end(response, run_id="r1")

    assert tracker.total_prompt_tokens == 10
    assert tracker.total_completion_tokens == 7
    assert tracker.total_tokens == 17
    assert tracker.call_count == 1


def test_tracker_falls_back_to_usage_metadata():
    tracker = TokenUsageTracker()
    response = _mk_response(
        llm_output={},
        usage_metadata={"input_tokens": 12, "output_tokens": 5, "total_tokens": 17},
    )

    tracker.on_llm_end(response, run_id="r2")

    assert tracker.total_prompt_tokens == 12
    assert tracker.total_completion_tokens == 5
    assert tracker.total_tokens == 17
    assert tracker.call_count == 1


def test_tracker_prefers_llm_output_and_does_not_double_count():
    tracker = TokenUsageTracker()
    response = _mk_response(
        llm_output={"token_usage": {"prompt_tokens": 9, "completion_tokens": 4, "total_tokens": 13}},
        usage_metadata={"input_tokens": 100, "output_tokens": 50, "total_tokens": 150},
    )

    tracker.on_llm_end(response, run_id="r3")

    assert tracker.total_prompt_tokens == 9
    assert tracker.total_completion_tokens == 4
    assert tracker.total_tokens == 13
    assert tracker.call_count == 1
