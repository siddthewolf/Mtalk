"""Portfolio tracker — tracks holdings, P&L, and alerts."""

import json
import os
import time
from dataclasses import dataclass, field, asdict
from typing import Dict, List, Optional, Tuple


@dataclass
class Holding:
    symbol: str          # e.g. "BTC"
    quantity: float
    avg_buy_price: float
    market: str          # e.g. "BTCUSDT"

    @property
    def invested(self) -> float:
        return self.quantity * self.avg_buy_price

    def pnl(self, current_price: float) -> Tuple[float, float]:
        """Returns (absolute P&L, percentage P&L)."""
        current_value = self.quantity * current_price
        abs_pnl = current_value - self.invested
        pct_pnl = (abs_pnl / self.invested * 100) if self.invested > 0 else 0
        return abs_pnl, pct_pnl


@dataclass
class Alert:
    symbol: str
    kind: str       # "price_above", "price_below", "pnl_above", "pnl_below"
    threshold: float
    triggered: bool = False
    created_at: float = field(default_factory=time.time)


class Portfolio:
    """
    Manages manual + API-synced portfolio positions.
    Persists to a JSON file so state survives agent restarts.
    """

    def __init__(self, data_file: str = "portfolio.json"):
        self.data_file = data_file
        self.holdings: Dict[str, Holding] = {}
        self.alerts: List[Alert] = []
        self.trade_log: List[dict] = []
        self._load()

    # ── persistence ───────────────────────────────────────────────────────────

    def _load(self):
        if os.path.exists(self.data_file):
            with open(self.data_file) as f:
                data = json.load(f)
            for h in data.get("holdings", []):
                self.holdings[h["symbol"]] = Holding(**h)
            for a in data.get("alerts", []):
                self.alerts.append(Alert(**a))
            self.trade_log = data.get("trade_log", [])

    def save(self):
        with open(self.data_file, "w") as f:
            json.dump(
                {
                    "holdings": [asdict(h) for h in self.holdings.values()],
                    "alerts": [asdict(a) for a in self.alerts],
                    "trade_log": self.trade_log[-500:],
                },
                f,
                indent=2,
            )

    # ── holdings management ───────────────────────────────────────────────────

    def add_holding(self, symbol: str, quantity: float, buy_price: float, market: str):
        """Add or average-down an existing position."""
        if symbol in self.holdings:
            old = self.holdings[symbol]
            total_qty = old.quantity + quantity
            avg_price = (old.quantity * old.avg_buy_price + quantity * buy_price) / total_qty
            self.holdings[symbol] = Holding(symbol, total_qty, avg_price, market)
        else:
            self.holdings[symbol] = Holding(symbol, quantity, buy_price, market)
        self._log_trade("BUY", symbol, quantity, buy_price)
        self.save()

    def remove_holding(self, symbol: str, quantity: float, sell_price: float):
        if symbol not in self.holdings:
            return
        h = self.holdings[symbol]
        abs_pnl, pct_pnl = h.pnl(sell_price)
        self._log_trade("SELL", symbol, quantity, sell_price, abs_pnl, pct_pnl)
        remaining = h.quantity - quantity
        if remaining <= 0:
            del self.holdings[symbol]
        else:
            self.holdings[symbol].quantity = remaining
        self.save()

    def sync_from_exchange(self, balances: List[dict], prices: Dict[str, float]):
        """
        Sync holdings from CoinDCX /balances response.
        Only syncs coins that already have a price and non-zero balance.
        """
        for b in balances:
            symbol = b.get("currency", "").upper()
            qty = float(b.get("balance", 0))
            locked = float(b.get("locked_balance", 0))
            total = qty + locked
            if total <= 0 or symbol in ("INR", "USDT"):
                continue
            price = prices.get(symbol, 0)
            if price <= 0:
                continue
            if symbol not in self.holdings:
                self.holdings[symbol] = Holding(
                    symbol=symbol,
                    quantity=total,
                    avg_buy_price=price,
                    market=f"{symbol}USDT",
                )
        self.save()

    # ── alerts ────────────────────────────────────────────────────────────────

    def add_alert(self, symbol: str, kind: str, threshold: float):
        self.alerts.append(Alert(symbol, kind, threshold))
        self.save()

    def check_alerts(self, symbol: str, price: float, pnl_pct: float) -> List[Alert]:
        fired = []
        for a in self.alerts:
            if a.triggered or a.symbol != symbol:
                continue
            hit = False
            if a.kind == "price_above" and price >= a.threshold:
                hit = True
            elif a.kind == "price_below" and price <= a.threshold:
                hit = True
            elif a.kind == "pnl_above" and pnl_pct >= a.threshold:
                hit = True
            elif a.kind == "pnl_below" and pnl_pct <= a.threshold:
                hit = True
            if hit:
                a.triggered = True
                fired.append(a)
        if fired:
            self.save()
        return fired

    # ── summary ───────────────────────────────────────────────────────────────

    def summary(self, prices: Dict[str, float]) -> dict:
        rows = []
        total_invested = 0.0
        total_current = 0.0
        for sym, h in self.holdings.items():
            cur_price = prices.get(sym, h.avg_buy_price)
            abs_pnl, pct_pnl = h.pnl(cur_price)
            total_invested += h.invested
            total_current += h.quantity * cur_price
            rows.append(
                {
                    "symbol": sym,
                    "qty": h.quantity,
                    "avg_buy": h.avg_buy_price,
                    "current_price": cur_price,
                    "invested": h.invested,
                    "current_value": h.quantity * cur_price,
                    "pnl_abs": abs_pnl,
                    "pnl_pct": pct_pnl,
                }
            )
        rows.sort(key=lambda r: r["pnl_pct"], reverse=True)
        overall_pnl = total_current - total_invested
        overall_pnl_pct = (overall_pnl / total_invested * 100) if total_invested > 0 else 0
        return {
            "holdings": rows,
            "total_invested": total_invested,
            "total_value": total_current,
            "overall_pnl": overall_pnl,
            "overall_pnl_pct": overall_pnl_pct,
        }

    def _log_trade(self, side, symbol, qty, price, pnl=None, pnl_pct=None):
        self.trade_log.append(
            {
                "time": time.strftime("%Y-%m-%d %H:%M:%S"),
                "side": side,
                "symbol": symbol,
                "qty": qty,
                "price": price,
                "pnl": pnl,
                "pnl_pct": pnl_pct,
            }
        )
