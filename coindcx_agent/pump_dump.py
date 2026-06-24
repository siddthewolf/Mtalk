"""Pump and dump detection engine with multi-layered signal scoring."""

from dataclasses import dataclass, field
from enum import Enum
from typing import List

import numpy as np


class PDSignal(Enum):
    PUMP = "🚀 PUMP"
    DUMP = "💥 DUMP"
    PUMP_AND_DUMP = "⚠️  PUMP & DUMP"
    SUSPICIOUS = "🔴 SUSPICIOUS"
    NORMAL = "✅ NORMAL"


@dataclass
class PumpDumpResult:
    signal: PDSignal
    confidence: float          # 0.0 – 1.0
    price_change_1h: float     # % change last 1 hour worth of candles
    price_change_short: float  # % change last 3 candles
    volume_ratio: float        # recent vol vs 20-period avg
    reasons: List[str] = field(default_factory=list)

    def __str__(self) -> str:
        return (
            f"{self.signal.value}  confidence={self.confidence:.0%}  "
            f"vol×{self.volume_ratio:.1f}  Δ1h={self.price_change_1h:+.2f}%"
        )


class PumpDumpDetector:
    """
    Detects pump-and-dump patterns using:
    - Volume spikes vs rolling average
    - Rapid non-linear price moves
    - Wick analysis (long upper wicks on pump, lower wicks on dump)
    - Price-volume divergence
    - Rolling Z-score of volume
    """

    def __init__(
        self,
        vol_spike_x: float = 3.0,       # volume multiplier threshold for spike
        price_spike_pct: float = 5.0,   # % rapid price change in short window
        dump_pct: float = 4.0,          # % drop qualifying as dump
        lookback: int = 20,             # candles for baseline
    ):
        self.vol_spike_x = vol_spike_x
        self.price_spike_pct = price_spike_pct / 100
        self.dump_pct = dump_pct / 100
        self.lookback = lookback

    def detect(
        self,
        closes: np.ndarray,
        volumes: np.ndarray,
        highs: np.ndarray,
        lows: np.ndarray,
    ) -> PumpDumpResult:
        n = len(closes)
        if n < self.lookback + 5:
            return PumpDumpResult(PDSignal.NORMAL, 0.0, 0.0, 0.0, 1.0, ["Insufficient data"])

        reasons: List[str] = []
        score = 0.0

        # ── volume analysis ───────────────────────────────────────────────────
        baseline_vol = np.mean(volumes[-(self.lookback + 5) : -5])
        recent_vol_avg = np.mean(volumes[-5:])
        volume_ratio = recent_vol_avg / baseline_vol if baseline_vol > 0 else 1.0

        # Z-score of latest candle volume
        vol_std = np.std(volumes[-self.lookback :])
        vol_z = (volumes[-1] - np.mean(volumes[-self.lookback :])) / vol_std if vol_std > 0 else 0

        if volume_ratio > self.vol_spike_x:
            score += min(0.30, 0.10 * (volume_ratio / self.vol_spike_x))
            reasons.append(f"Volume spike ×{volume_ratio:.1f} baseline (z={vol_z:.1f})")

        # ── price change analysis ─────────────────────────────────────────────
        short_change = (closes[-1] - closes[-4]) / closes[-4] if closes[-4] > 0 else 0
        medium_change = (closes[-1] - closes[-self.lookback]) / closes[-self.lookback] if closes[-self.lookback] > 0 else 0

        # 1-hour proxy: last 60 candles if 1m, or last candle if 1h
        hour_idx = min(60, n - 1)
        price_1h = (closes[-1] - closes[-hour_idx]) / closes[-hour_idx] if closes[-hour_idx] > 0 else 0

        is_pumping = short_change > self.price_spike_pct
        is_dumping = short_change < -self.dump_pct

        if is_pumping:
            score += 0.30
            reasons.append(f"Rapid price surge: {short_change*100:+.2f}% in last 3 candles")

        if is_dumping:
            score += 0.25
            reasons.append(f"Rapid price crash: {short_change*100:+.2f}% in last 3 candles")

        # ── wick analysis ─────────────────────────────────────────────────────
        # Long upper wicks = distribution / sell pressure
        last_body = abs(closes[-1] - closes[-2]) if n > 1 else 0
        upper_wick = highs[-1] - max(closes[-1], closes[-2] if n > 1 else closes[-1])
        lower_wick = min(closes[-1], closes[-2] if n > 1 else closes[-1]) - lows[-1]

        if last_body > 0 and upper_wick / (last_body + 1e-10) > 3:
            score += 0.15
            reasons.append("Long upper wick — distribution / sell pressure")

        if last_body > 0 and lower_wick / (last_body + 1e-10) > 3 and is_dumping:
            score += 0.10
            reasons.append("Long lower wick — capitulation / stop hunt")

        # ── pump → dump sequence ──────────────────────────────────────────────
        peak_idx = int(np.argmax(closes[-self.lookback :]))
        peak_price = closes[-self.lookback + peak_idx]
        drop_from_peak = (closes[-1] - peak_price) / peak_price if peak_price > 0 else 0

        pump_then_dump = (
            peak_idx < self.lookback - 3
            and drop_from_peak < -self.dump_pct * 1.5
            and medium_change < 0
        )
        if pump_then_dump:
            score += 0.25
            reasons.append(
                f"Classic P&D: peaked {self.lookback - peak_idx} candles ago, "
                f"dropped {drop_from_peak*100:.1f}% from peak"
            )

        # ── price-volume divergence ───────────────────────────────────────────
        recent_prices_up = closes[-3] < closes[-2] < closes[-1]
        recent_vol_down = volumes[-1] < volumes[-2] < volumes[-3]
        if recent_prices_up and recent_vol_down:
            score += 0.10
            reasons.append("Price rising on falling volume — weak momentum")

        # ── classify ─────────────────────────────────────────────────────────
        confidence = min(score, 1.0)
        if confidence < 0.25:
            sig = PDSignal.NORMAL
        elif pump_then_dump:
            sig = PDSignal.PUMP_AND_DUMP
        elif is_pumping and volume_ratio > self.vol_spike_x:
            sig = PDSignal.PUMP
        elif is_dumping:
            sig = PDSignal.DUMP
        else:
            sig = PDSignal.SUSPICIOUS

        return PumpDumpResult(
            signal=sig,
            confidence=confidence,
            price_change_1h=price_1h * 100,
            price_change_short=short_change * 100,
            volume_ratio=volume_ratio,
            reasons=reasons,
        )
