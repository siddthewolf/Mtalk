"""
Market signal engine — fuses all indicators + pump/dump into a single
scored recommendation with entry, stop-loss, and take-profit levels.
"""

from dataclasses import dataclass, field
from enum import Enum
from typing import List, Optional

import numpy as np

from .indicators import TechnicalIndicators
from .pump_dump import PumpDumpDetector, PumpDumpResult, PDSignal


class Signal(Enum):
    STRONG_BUY = "🟢 STRONG BUY"
    BUY = "🟩 BUY"
    HOLD = "⬜ HOLD"
    SELL = "🟥 SELL"
    STRONG_SELL = "🔴 STRONG SELL"
    AVOID = "⛔ AVOID (P&D)"


@dataclass
class IndicatorSnapshot:
    # RSI
    rsi: float
    rsi_label: str
    # MACD
    macd: float
    macd_signal: float
    macd_hist: float
    macd_label: str
    # Bollinger
    bb_upper: float
    bb_middle: float
    bb_lower: float
    bb_label: str
    bb_pct: float          # 0 = at lower band, 1 = at upper band
    # EMA trend
    ema9: float
    ema21: float
    ema50: float
    ema_label: str
    # Stochastic
    stoch_k: float
    stoch_d: float
    stoch_label: str
    # ATR (volatility)
    atr: float
    atr_pct: float         # ATR as % of price
    # Volume
    volume_ratio: float
    volume_label: str
    # OBV
    obv_trend: str
    # Ichimoku
    above_cloud: Optional[bool]
    ichimoku_label: str


@dataclass
class MarketAnalysis:
    symbol: str
    timeframe: str
    price: float
    score: int              # -100 to +100
    signal: Signal
    confidence: float       # 0 – 1
    pump_dump: PumpDumpResult
    indicators: IndicatorSnapshot
    supports: List[float]
    resistances: List[float]
    entry: Optional[float]
    stop_loss: Optional[float]
    take_profit_1: Optional[float]    # 1:1.5 R:R
    take_profit_2: Optional[float]    # 1:3 R:R
    risk_reward: Optional[float]
    summary: str

    def one_line(self) -> str:
        sl = f"SL={self.stop_loss:.4g}" if self.stop_loss else ""
        tp = f"TP={self.take_profit_1:.4g}" if self.take_profit_1 else ""
        return (
            f"{self.symbol:<14} {self.signal.value:<20}  "
            f"score={self.score:+4d}  conf={self.confidence:.0%}  "
            f"price={self.price:.6g}  {sl}  {tp}"
        )


class MarketAnalyzer:
    IND = TechnicalIndicators()

    def __init__(self, pump_dump_detector: Optional[PumpDumpDetector] = None):
        self.pd = pump_dump_detector or PumpDumpDetector()

    def analyze(
        self, symbol: str, candles: List[dict], timeframe: str = "1h"
    ) -> Optional[MarketAnalysis]:
        if len(candles) < 60:
            return None

        closes = np.array([c["close"] for c in candles], dtype=float)
        highs = np.array([c["high"] for c in candles], dtype=float)
        lows = np.array([c["low"] for c in candles], dtype=float)
        volumes = np.array([c["volume"] for c in candles], dtype=float)

        price = closes[-1]
        score = 0
        labels = []

        # ── RSI ──────────────────────────────────────────────────────────────
        rsi_arr = self.IND.rsi(closes)
        rsi_val = float(rsi_arr[-1]) if not np.isnan(rsi_arr[-1]) else 50.0
        if rsi_val < 25:
            rsi_label, score = "EXTREMELY OVERSOLD", score + 25
        elif rsi_val < 35:
            rsi_label, score = "OVERSOLD", score + 18
        elif rsi_val < 45:
            rsi_label, score = "MILDLY OVERSOLD", score + 8
        elif rsi_val > 80:
            rsi_label, score = "EXTREMELY OVERBOUGHT", score - 25
        elif rsi_val > 70:
            rsi_label, score = "OVERBOUGHT", score - 18
        elif rsi_val > 60:
            rsi_label, score = "MILDLY OVERBOUGHT", score - 8
        else:
            rsi_label = "NEUTRAL"
        labels.append(f"RSI={rsi_val:.1f}({rsi_label})")

        # ── MACD ─────────────────────────────────────────────────────────────
        ml, sl_arr, hist = self.IND.macd(closes)
        mv, sv, hv = float(ml[-1] or 0), float(sl_arr[-1] or 0), float(hist[-1] or 0)
        prev_hv = float(hist[-2] or 0) if not np.isnan(hist[-2]) else hv
        if mv > sv and ml[-2] is not None and not np.isnan(ml[-2]) and float(ml[-2]) <= float(sl_arr[-2] or 0):
            macd_label, score = "BULLISH CROSSOVER", score + 28
        elif mv < sv and ml[-2] is not None and not np.isnan(ml[-2]) and float(ml[-2]) >= float(sl_arr[-2] or 0):
            macd_label, score = "BEARISH CROSSOVER", score - 28
        elif mv > sv:
            macd_label, score = "BULLISH", score + 12
        elif mv < sv:
            macd_label, score = "BEARISH", score - 12
        else:
            macd_label = "NEUTRAL"
        if hv > 0 and prev_hv < 0:
            score += 8
        elif hv < 0 and prev_hv > 0:
            score -= 8
        labels.append(f"MACD:{macd_label}")

        # ── Bollinger Bands ───────────────────────────────────────────────────
        ub, mb, lb = self.IND.bollinger_bands(closes)
        u, m, l = float(ub[-1] or price), float(mb[-1] or price), float(lb[-1] or price)
        band_width = u - l
        bb_pct = (price - l) / band_width if band_width > 0 else 0.5
        if price < l:
            bb_label, score = "BELOW LOWER BAND", score + 22
        elif price > u:
            bb_label, score = "ABOVE UPPER BAND", score - 22
        elif bb_pct < 0.25:
            bb_label, score = "NEAR LOWER BAND", score + 10
        elif bb_pct > 0.75:
            bb_label, score = "NEAR UPPER BAND", score - 10
        else:
            bb_label = "MID BAND"
        labels.append(f"BB:{bb_label}")

        # ── EMA Trend ────────────────────────────────────────────────────────
        ema9 = float(self.IND.ema(closes, 9)[-1] or price)
        ema21 = float(self.IND.ema(closes, 21)[-1] or price)
        ema50 = float(self.IND.ema(closes, 50)[-1] or price)
        prev_ema9 = float(self.IND.ema(closes, 9)[-2] or ema9)
        prev_ema21 = float(self.IND.ema(closes, 21)[-2] or ema21)
        if ema9 > ema21 > ema50:
            ema_label, score = "STRONG UPTREND (9>21>50)", score + 18
        elif ema9 < ema21 < ema50:
            ema_label, score = "STRONG DOWNTREND (9<21<50)", score - 18
        elif ema9 > ema21 and prev_ema9 <= prev_ema21:
            ema_label, score = "GOLDEN CROSS (9/21)", score + 22
        elif ema9 < ema21 and prev_ema9 >= prev_ema21:
            ema_label, score = "DEATH CROSS (9/21)", score - 22
        elif ema9 > ema21:
            ema_label, score = "BULLISH BIAS", score + 8
        else:
            ema_label, score = "BEARISH BIAS", score - 8
        labels.append(f"EMA:{ema_label}")

        # ── Stochastic ────────────────────────────────────────────────────────
        k_arr, d_arr = self.IND.stochastic(highs, lows, closes)
        kv = float(k_arr[-1]) if not np.isnan(k_arr[-1]) else 50.0
        dv = float(d_arr[-1]) if not np.isnan(d_arr[-1]) else 50.0
        pk = float(k_arr[-2]) if not np.isnan(k_arr[-2]) else kv
        pd_val = float(d_arr[-2]) if not np.isnan(d_arr[-2]) else dv
        if kv < 20 and dv < 20:
            stoch_label, score = "OVERSOLD", score + 15
        elif kv > 80 and dv > 80:
            stoch_label, score = "OVERBOUGHT", score - 15
        elif kv > dv and pk <= pd_val and kv < 50:
            stoch_label, score = "BULLISH CROSSOVER", score + 12
        elif kv < dv and pk >= pd_val and kv > 50:
            stoch_label, score = "BEARISH CROSSOVER", score - 12
        else:
            stoch_label = "NEUTRAL"
        labels.append(f"Stoch:{stoch_label}")

        # ── ATR ──────────────────────────────────────────────────────────────
        atr_arr = self.IND.atr(highs, lows, closes)
        atr_val = float(atr_arr[-1]) if not np.isnan(atr_arr[-1]) else price * 0.02
        atr_pct = atr_val / price * 100

        # ── Volume ───────────────────────────────────────────────────────────
        avg_vol = np.mean(volumes[-21:-1])
        vol_ratio = float(volumes[-1] / avg_vol) if avg_vol > 0 else 1.0
        if vol_ratio > 3 and score > 0:
            vol_label, score = "HIGH VOL CONFIRMS BUY", score + 12
        elif vol_ratio > 3 and score < 0:
            vol_label, score = "HIGH VOL CONFIRMS SELL", score - 12
        elif vol_ratio > 2:
            vol_label = "HIGH VOLUME"
            score += 5
        elif vol_ratio < 0.4:
            vol_label = "LOW VOLUME (WEAK)"
            score = int(score * 0.7)
        else:
            vol_label = "NORMAL"

        # ── OBV trend ─────────────────────────────────────────────────────────
        obv = self.IND.obv(closes, volumes)
        obv_sma = self.IND.sma(obv, 10)
        if not np.isnan(obv_sma[-1]) and not np.isnan(obv_sma[-5]):
            if obv[-1] > obv_sma[-1] and obv_sma[-1] > obv_sma[-5]:
                obv_trend, score = "RISING (ACCUMULATION)", score + 8
            elif obv[-1] < obv_sma[-1] and obv_sma[-1] < obv_sma[-5]:
                obv_trend, score = "FALLING (DISTRIBUTION)", score - 8
            else:
                obv_trend = "FLAT"
        else:
            obv_trend = "N/A"

        # ── Ichimoku ─────────────────────────────────────────────────────────
        if len(closes) >= 52:
            ts, ks, sa, sb = self.IND.ichimoku(highs, lows)
            cloud_top = max(float(sa[-1] or 0), float(sb[-1] or 0))
            cloud_bot = min(float(sa[-1] or 0), float(sb[-1] or 0))
            if price > cloud_top:
                above_cloud = True
                ichi_label, score = "ABOVE CLOUD (BULLISH)", score + 10
            elif price < cloud_bot:
                above_cloud = False
                ichi_label, score = "BELOW CLOUD (BEARISH)", score - 10
            else:
                above_cloud = None
                ichi_label = "INSIDE CLOUD (NEUTRAL)"
        else:
            above_cloud = None
            ichi_label = "N/A"

        # ── Pump/Dump override ────────────────────────────────────────────────
        pd_result = self.pd.detect(closes, volumes, highs, lows)
        if pd_result.signal in (PDSignal.PUMP_AND_DUMP, PDSignal.PUMP):
            score = min(score, -15)
            labels.append(str(pd_result))
        elif pd_result.signal == PDSignal.DUMP:
            score = min(score, -30)
            labels.append(str(pd_result))
        elif pd_result.signal == PDSignal.SUSPICIOUS:
            score = int(score * 0.6)
            labels.append(str(pd_result))

        # ── Support / Resistance ──────────────────────────────────────────────
        supports, resistances = self.IND.support_resistance(closes)

        # ── Entry / SL / TP ───────────────────────────────────────────────────
        entry = stop_loss = tp1 = tp2 = rr = None
        if score >= 20:
            entry = price
            stop_loss = round(price - 2 * atr_val, 8)
            risk = price - stop_loss
            tp1 = round(price + 1.5 * risk, 8)
            tp2 = round(price + 3.0 * risk, 8)
            rr = round((tp2 - price) / risk, 2)
        elif score <= -20:
            # Short signals
            entry = price
            stop_loss = round(price + 2 * atr_val, 8)
            risk = stop_loss - price
            tp1 = round(price - 1.5 * risk, 8)
            tp2 = round(price - 3.0 * risk, 8)
            rr = round((price - tp2) / risk, 2)

        # ── Final signal ──────────────────────────────────────────────────────
        score = max(-100, min(100, score))
        if pd_result.signal in (PDSignal.PUMP_AND_DUMP, PDSignal.PUMP) and pd_result.confidence > 0.5:
            signal = Signal.AVOID
        elif score >= 60:
            signal = Signal.STRONG_BUY
        elif score >= 25:
            signal = Signal.BUY
        elif score <= -60:
            signal = Signal.STRONG_SELL
        elif score <= -25:
            signal = Signal.SELL
        else:
            signal = Signal.HOLD

        confidence = min(abs(score) / 100, 1.0)

        ind_snap = IndicatorSnapshot(
            rsi=rsi_val, rsi_label=rsi_label,
            macd=mv, macd_signal=sv, macd_hist=hv, macd_label=macd_label,
            bb_upper=u, bb_middle=m, bb_lower=l, bb_label=bb_label, bb_pct=bb_pct,
            ema9=ema9, ema21=ema21, ema50=ema50, ema_label=ema_label,
            stoch_k=kv, stoch_d=dv, stoch_label=stoch_label,
            atr=atr_val, atr_pct=atr_pct,
            volume_ratio=vol_ratio, volume_label=vol_label,
            obv_trend=obv_trend,
            above_cloud=above_cloud, ichimoku_label=ichi_label,
        )

        return MarketAnalysis(
            symbol=symbol,
            timeframe=timeframe,
            price=price,
            score=score,
            signal=signal,
            confidence=confidence,
            pump_dump=pd_result,
            indicators=ind_snap,
            supports=supports,
            resistances=resistances,
            entry=entry,
            stop_loss=stop_loss,
            take_profit_1=tp1,
            take_profit_2=tp2,
            risk_reward=rr,
            summary=" | ".join(labels),
        )
