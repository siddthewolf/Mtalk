"""All technical indicators — pure NumPy, no external TA library needed."""

from typing import List, Tuple

import numpy as np


class TechnicalIndicators:

    # ── moving averages ───────────────────────────────────────────────────────

    @staticmethod
    def sma(prices: np.ndarray, period: int) -> np.ndarray:
        result = np.full(len(prices), np.nan)
        for i in range(period - 1, len(prices)):
            result[i] = np.mean(prices[i - period + 1 : i + 1])
        return result

    @staticmethod
    def ema(prices: np.ndarray, period: int) -> np.ndarray:
        result = np.full(len(prices), np.nan)
        if len(prices) < period:
            return result
        k = 2.0 / (period + 1)
        result[period - 1] = np.mean(prices[:period])
        for i in range(period, len(prices)):
            result[i] = prices[i] * k + result[i - 1] * (1 - k)
        return result

    @staticmethod
    def wma(prices: np.ndarray, period: int) -> np.ndarray:
        """Weighted Moving Average — more weight on recent prices."""
        result = np.full(len(prices), np.nan)
        weights = np.arange(1, period + 1, dtype=float)
        for i in range(period - 1, len(prices)):
            result[i] = np.dot(prices[i - period + 1 : i + 1], weights) / weights.sum()
        return result

    # ── RSI ──────────────────────────────────────────────────────────────────

    @staticmethod
    def rsi(prices: np.ndarray, period: int = 14) -> np.ndarray:
        result = np.full(len(prices), np.nan)
        if len(prices) <= period:
            return result
        deltas = np.diff(prices)
        gains = np.where(deltas > 0, deltas, 0.0)
        losses = np.where(deltas < 0, -deltas, 0.0)
        avg_gain = np.mean(gains[:period])
        avg_loss = np.mean(losses[:period])
        for i in range(period, len(deltas)):
            avg_gain = (avg_gain * (period - 1) + gains[i]) / period
            avg_loss = (avg_loss * (period - 1) + losses[i]) / period
            rs = avg_gain / avg_loss if avg_loss != 0 else np.inf
            result[i + 1] = 100 - (100 / (1 + rs))
        return result

    # ── MACD ─────────────────────────────────────────────────────────────────

    @staticmethod
    def macd(
        prices: np.ndarray, fast: int = 12, slow: int = 26, signal: int = 9
    ) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
        ema_fast = TechnicalIndicators.ema(prices, fast)
        ema_slow = TechnicalIndicators.ema(prices, slow)
        macd_line = ema_fast - ema_slow
        valid = np.where(~np.isnan(macd_line))[0]
        signal_line = np.full(len(prices), np.nan)
        if len(valid) >= signal:
            sig_ema = TechnicalIndicators.ema(macd_line[valid], signal)
            for i, idx in enumerate(valid):
                if not np.isnan(sig_ema[i]):
                    signal_line[idx] = sig_ema[i]
        histogram = macd_line - signal_line
        return macd_line, signal_line, histogram

    # ── Bollinger Bands ───────────────────────────────────────────────────────

    @staticmethod
    def bollinger_bands(
        prices: np.ndarray, period: int = 20, std_mult: float = 2.0
    ) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
        middle = TechnicalIndicators.sma(prices, period)
        std = np.full(len(prices), np.nan)
        for i in range(period - 1, len(prices)):
            std[i] = np.std(prices[i - period + 1 : i + 1], ddof=0)
        upper = middle + std_mult * std
        lower = middle - std_mult * std
        return upper, middle, lower

    # ── Stochastic ────────────────────────────────────────────────────────────

    @staticmethod
    def stochastic(
        highs: np.ndarray, lows: np.ndarray, closes: np.ndarray,
        k_period: int = 14, d_period: int = 3,
    ) -> Tuple[np.ndarray, np.ndarray]:
        k = np.full(len(closes), np.nan)
        for i in range(k_period - 1, len(closes)):
            hh = np.max(highs[i - k_period + 1 : i + 1])
            ll = np.min(lows[i - k_period + 1 : i + 1])
            if hh - ll != 0:
                k[i] = 100 * (closes[i] - ll) / (hh - ll)
        d = TechnicalIndicators.sma(k, d_period)
        return k, d

    # ── ATR ──────────────────────────────────────────────────────────────────

    @staticmethod
    def atr(
        highs: np.ndarray, lows: np.ndarray, closes: np.ndarray, period: int = 14
    ) -> np.ndarray:
        n = len(closes)
        tr = np.zeros(n)
        tr[0] = highs[0] - lows[0]
        for i in range(1, n):
            tr[i] = max(
                highs[i] - lows[i],
                abs(highs[i] - closes[i - 1]),
                abs(lows[i] - closes[i - 1]),
            )
        return TechnicalIndicators.sma(tr, period)

    # ── OBV ──────────────────────────────────────────────────────────────────

    @staticmethod
    def obv(closes: np.ndarray, volumes: np.ndarray) -> np.ndarray:
        result = np.zeros(len(closes))
        for i in range(1, len(closes)):
            if closes[i] > closes[i - 1]:
                result[i] = result[i - 1] + volumes[i]
            elif closes[i] < closes[i - 1]:
                result[i] = result[i - 1] - volumes[i]
            else:
                result[i] = result[i - 1]
        return result

    # ── VWAP ─────────────────────────────────────────────────────────────────

    @staticmethod
    def vwap(
        highs: np.ndarray, lows: np.ndarray, closes: np.ndarray, volumes: np.ndarray
    ) -> np.ndarray:
        typical = (highs + lows + closes) / 3
        return np.cumsum(typical * volumes) / np.cumsum(volumes)

    # ── Ichimoku ─────────────────────────────────────────────────────────────

    @staticmethod
    def ichimoku(
        highs: np.ndarray, lows: np.ndarray,
        tenkan: int = 9, kijun: int = 26, senkou_b: int = 52,
    ) -> Tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
        def mid(h, l, p):
            out = np.full(len(h), np.nan)
            for i in range(p - 1, len(h)):
                out[i] = (np.max(h[i - p + 1 : i + 1]) + np.min(l[i - p + 1 : i + 1])) / 2
            return out

        tenkan_sen = mid(highs, lows, tenkan)
        kijun_sen = mid(highs, lows, kijun)
        senkou_a = (tenkan_sen + kijun_sen) / 2
        senkou_b_line = mid(highs, lows, senkou_b)
        return tenkan_sen, kijun_sen, senkou_a, senkou_b_line

    # ── Support / Resistance ─────────────────────────────────────────────────

    @staticmethod
    def support_resistance(
        prices: np.ndarray, window: int = 10
    ) -> Tuple[List[float], List[float]]:
        supports, resistances = [], []
        for i in range(window, len(prices) - window):
            chunk = prices[i - window : i + window + 1]
            if prices[i] == np.min(chunk):
                supports.append(float(prices[i]))
            if prices[i] == np.max(chunk):
                resistances.append(float(prices[i]))
        return sorted(supports)[-3:], sorted(resistances)[:3]

    # ── Volume Profile ────────────────────────────────────────────────────────

    @staticmethod
    def volume_profile(
        closes: np.ndarray, volumes: np.ndarray, bins: int = 20
    ) -> Tuple[np.ndarray, np.ndarray]:
        """Returns price levels and their accumulated volume (Point of Control)."""
        price_min, price_max = closes.min(), closes.max()
        edges = np.linspace(price_min, price_max, bins + 1)
        vol_bins = np.zeros(bins)
        for price, vol in zip(closes, volumes):
            idx = np.searchsorted(edges[1:], price)
            vol_bins[min(idx, bins - 1)] += vol
        centers = (edges[:-1] + edges[1:]) / 2
        return centers, vol_bins
