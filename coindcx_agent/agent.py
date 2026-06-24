"""
CoinDCX Portfolio Agent — main orchestration loop.

Capabilities:
  • Fetches real-time tickers + OHLCV candles for every USDT market
  • Runs 9 technical indicators per coin (RSI, MACD, BB, EMA, Stoch, ATR, OBV, Ichi, VWAP)
  • Multi-timeframe analysis (1h primary + 15m confirmation)
  • Pump-and-dump detection with confidence score
  • Ranked buy/sell/avoid signals
  • Portfolio P&L tracking with price alerts
  • Configurable scan interval and top-N market filtering
"""

import os
import sys
import time
import logging
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import datetime
from typing import Dict, List, Optional, Tuple

from .api_client import CoinDCXClient
from .analyzer import MarketAnalyzer, MarketAnalysis, Signal
from .pump_dump import PumpDumpDetector, PDSignal
from .portfolio import Portfolio

logger = logging.getLogger("coindcx_agent")


# ── ANSI colours for terminal output ─────────────────────────────────────────
GREEN = "\033[92m"
RED   = "\033[91m"
YELLOW= "\033[93m"
CYAN  = "\033[96m"
BOLD  = "\033[1m"
RESET = "\033[0m"
DIM   = "\033[2m"


@dataclass
class AgentConfig:
    api_key: str = ""
    api_secret: str = ""
    # Scan behaviour
    scan_interval_sec: int = 60        # how often to rescan all markets
    top_n_markets: int = 50            # scan top N markets by 24h volume
    quote_currency: str = "USDT"      # filter for USDT pairs
    # Analysis
    primary_tf: str = "1h"
    confirm_tf: str = "15m"
    candle_limit: int = 200
    # Signals
    min_score_buy: int = 25            # minimum score to show as BUY
    min_score_sell: int = -25          # maximum score to show as SELL
    min_confidence: float = 0.30
    # Pump / Dump
    vol_spike_x: float = 3.0
    price_spike_pct: float = 5.0
    # Display
    show_hold: bool = False            # show HOLD signals in output
    max_display: int = 20              # max recommendations to print per cycle
    # Portfolio
    portfolio_file: str = "portfolio.json"


class CoinDCXAgent:

    def __init__(self, config: AgentConfig):
        self.cfg = config
        self.client = CoinDCXClient(
            api_key=config.api_key,
            api_secret=config.api_secret,
        )
        self.pd_detector = PumpDumpDetector(
            vol_spike_x=config.vol_spike_x,
            price_spike_pct=config.price_spike_pct,
        )
        self.analyzer = MarketAnalyzer(self.pd_detector)
        self.portfolio = Portfolio(config.portfolio_file)
        self._cycle = 0
        self._price_cache: Dict[str, float] = {}

    # ── helpers ───────────────────────────────────────────────────────────────

    def _ticker_to_pair(self, market: str) -> str:
        """Convert 'BTCUSDT' → correct pair like 'B-BTC_USDT' or 'KC-TAO_USDT'."""
        return self.client.market_to_pair(market)

    def _fetch_and_analyze(
        self, market: str
    ) -> Optional[Tuple[MarketAnalysis, Optional[MarketAnalysis]]]:
        """Fetch candles for primary + confirmation TF and return analysis pair."""
        pair = self._ticker_to_pair(market)
        try:
            candles_1h = self.client.get_candles(pair, self.cfg.primary_tf, self.cfg.candle_limit)
            if len(candles_1h) < 60:
                return None
            result_1h = self.analyzer.analyze(market, candles_1h, self.cfg.primary_tf)
            if result_1h is None:
                return None

            # Confirmation timeframe
            try:
                candles_15m = self.client.get_candles(pair, self.cfg.confirm_tf, self.cfg.candle_limit)
                result_15m = self.analyzer.analyze(market, candles_15m, self.cfg.confirm_tf)
            except Exception:
                result_15m = None

            return result_1h, result_15m
        except Exception as e:
            logger.debug(f"Skip {market}: {e}")
            return None

    def _get_top_markets(self) -> List[str]:
        """Return top N markets by 24h volume, filtered to quote currency."""
        tickers = self.client.get_tickers()
        usdt_pairs = [
            t for t in tickers
            if t.get("market", "").upper().endswith(self.cfg.quote_currency)
        ]
        # Sort by 24h volume
        usdt_pairs.sort(
            key=lambda t: float(t.get("volume", 0) or 0), reverse=True
        )
        markets = [t["market"].upper() for t in usdt_pairs[: self.cfg.top_n_markets]]
        # Cache last prices
        for t in usdt_pairs:
            sym = t["market"].upper().replace(self.cfg.quote_currency, "")
            price = float(t.get("last_price", 0) or 0)
            if price:
                self._price_cache[sym] = price
        return markets

    # ── display helpers ───────────────────────────────────────────────────────

    def _print_header(self):
        now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        print(f"\n{BOLD}{CYAN}{'='*80}{RESET}")
        print(f"{BOLD}{CYAN}  CoinDCX Portfolio Agent  |  Cycle #{self._cycle}  |  {now}{RESET}")
        print(f"{BOLD}{CYAN}{'='*80}{RESET}\n")

    def _print_analysis(self, a: MarketAnalysis, confirm: Optional[MarketAnalysis]):
        sig = a.signal
        if sig in (Signal.STRONG_BUY, Signal.BUY):
            colour = GREEN
        elif sig in (Signal.STRONG_SELL, Signal.SELL):
            colour = RED
        elif sig == Signal.AVOID:
            colour = YELLOW
        else:
            colour = RESET

        confirm_str = ""
        if confirm:
            c_sig = confirm.signal
            if c_sig in (Signal.STRONG_BUY, Signal.BUY) and sig in (Signal.STRONG_BUY, Signal.BUY):
                confirm_str = f"  {GREEN}✔ CONFIRMED by {confirm.timeframe}{RESET}"
            elif c_sig in (Signal.STRONG_SELL, Signal.SELL) and sig in (Signal.STRONG_SELL, Signal.SELL):
                confirm_str = f"  {RED}✔ CONFIRMED by {confirm.timeframe}{RESET}"
            else:
                confirm_str = f"  {DIM}({confirm.timeframe}: {c_sig.value}){RESET}"

        print(
            f"{colour}{BOLD}{a.symbol:<14}{RESET}  "
            f"{colour}{sig.value:<22}{RESET}  "
            f"score={BOLD}{a.score:+4d}{RESET}  "
            f"conf={a.confidence:.0%}  "
            f"price={CYAN}{a.price:.6g}{RESET}"
            f"{confirm_str}"
        )

        ind = a.indicators
        print(
            f"  {DIM}RSI={ind.rsi:.1f}({ind.rsi_label})  "
            f"MACD:{ind.macd_label}  "
            f"BB:{ind.bb_label}({ind.bb_pct:.0%})  "
            f"EMA:{ind.ema_label}{RESET}"
        )
        print(
            f"  {DIM}Stoch:{ind.stoch_label}  "
            f"Vol×{ind.volume_ratio:.1f}({ind.volume_label})  "
            f"OBV:{ind.obv_trend}  "
            f"Ichi:{ind.ichimoku_label}  "
            f"ATR={ind.atr_pct:.2f}%{RESET}"
        )

        # P&D block
        pd = a.pump_dump
        if pd.signal != PDSignal.NORMAL:
            print(
                f"  {YELLOW}⚠  PUMP/DUMP: {pd.signal.value}  "
                f"conf={pd.confidence:.0%}  vol×{pd.volume_ratio:.1f}  "
                f"Δ={pd.price_change_short:+.2f}%{RESET}"
            )
            for r in pd.reasons:
                print(f"     {DIM}• {r}{RESET}")

        # Entry / SL / TP
        if a.entry:
            direction = "LONG" if sig in (Signal.STRONG_BUY, Signal.BUY) else "SHORT"
            print(
                f"  {GREEN if direction=='LONG' else RED}"
                f"► {direction}  Entry={a.entry:.6g}  "
                f"SL={a.stop_loss:.6g}  "
                f"TP1={a.take_profit_1:.6g}  "
                f"TP2={a.take_profit_2:.6g}  "
                f"R:R=1:{a.risk_reward:.1f}"
                f"{RESET}"
            )

        # Support / Resistance
        if a.supports:
            print(f"  {DIM}Support:    {[f'{s:.6g}' for s in a.supports]}{RESET}")
        if a.resistances:
            print(f"  {DIM}Resistance: {[f'{r:.6g}' for r in a.resistances]}{RESET}")

        print()

    def _print_portfolio(self):
        if not self.portfolio.holdings:
            return
        summ = self.portfolio.summary(self._price_cache)
        print(f"{BOLD}━━ Portfolio Summary ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━{RESET}")
        for row in summ["holdings"]:
            pnl_col = GREEN if row["pnl_pct"] >= 0 else RED
            print(
                f"  {row['symbol']:<8}  qty={row['qty']:.4f}  "
                f"avg={row['avg_buy']:.6g}  now={row['current_price']:.6g}  "
                f"P&L: {pnl_col}{row['pnl_abs']:+.2f} USDT ({row['pnl_pct']:+.2f}%){RESET}"
            )

            # Alert check
            fired = self.portfolio.check_alerts(
                row["symbol"], row["current_price"], row["pnl_pct"]
            )
            for a in fired:
                print(f"  {YELLOW}🔔 ALERT FIRED: {a.kind} @ {a.threshold} for {a.symbol}{RESET}")

        total_col = GREEN if summ["overall_pnl"] >= 0 else RED
        print(
            f"\n  {BOLD}Total invested: {summ['total_invested']:.2f} USDT  "
            f"Current value: {summ['total_value']:.2f} USDT  "
            f"Overall P&L: {total_col}{summ['overall_pnl']:+.2f} USDT "
            f"({summ['overall_pnl_pct']:+.2f}%){RESET}"
        )
        print()

    def _print_pump_dump_watchlist(self, results: List[Tuple[MarketAnalysis, Optional[MarketAnalysis]]]):
        suspicious = [
            r[0] for r in results
            if r[0].pump_dump.signal != PDSignal.NORMAL
            and r[0].pump_dump.confidence >= 0.3
        ]
        if not suspicious:
            return
        print(f"{BOLD}{YELLOW}━━ Pump / Dump Watchlist ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━{RESET}")
        for a in suspicious:
            pd = a.pump_dump
            print(
                f"  {YELLOW}{a.symbol:<14}  {pd.signal.value:<28}  "
                f"conf={pd.confidence:.0%}  vol×{pd.volume_ratio:.1f}  "
                f"Δ1h={pd.price_change_1h:+.2f}%{RESET}"
            )
            for r in pd.reasons:
                print(f"    {DIM}• {r}{RESET}")
        print()

    # ── main loop ─────────────────────────────────────────────────────────────

    def run_once(self):
        """Single scan cycle — fetch, analyse, print."""
        self._cycle += 1
        self._print_header()

        # Portfolio from exchange (if credentials available)
        if self.client.has_credentials():
            try:
                balances = self.client.get_balances()
                self.portfolio.sync_from_exchange(balances, self._price_cache)
            except Exception as e:
                logger.warning(f"Could not sync portfolio: {e}")

        print(f"Fetching top {self.cfg.top_n_markets} {self.cfg.quote_currency} markets…")
        try:
            markets = self._get_top_markets()
        except Exception as e:
            print(f"{RED}Failed to fetch tickers: {e}{RESET}")
            return

        print(f"Analysing {len(markets)} markets with {self.cfg.primary_tf} + {self.cfg.confirm_tf} TF…\n")

        results: List[Tuple[MarketAnalysis, Optional[MarketAnalysis]]] = []
        with ThreadPoolExecutor(max_workers=10) as pool:
            futures = {pool.submit(self._fetch_and_analyze, m): m for m in markets}
            for fut in as_completed(futures):
                res = fut.result()
                if res:
                    results.append(res)

        if not results:
            print(f"{YELLOW}No results returned.{RESET}")
            return

        # Sort by score descending
        results.sort(key=lambda r: r[0].score, reverse=True)

        # ── Buy signals ───────────────────────────────────────────────────────
        buys = [r for r in results if r[0].score >= self.cfg.min_score_buy
                and r[0].confidence >= self.cfg.min_confidence
                and r[0].signal not in (Signal.AVOID,)]
        print(f"{BOLD}{GREEN}━━ BUY Signals ({len(buys)}) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━{RESET}")
        for pair in buys[: self.cfg.max_display]:
            self._print_analysis(pair[0], pair[1])

        # ── Sell signals ──────────────────────────────────────────────────────
        sells = [r for r in results if r[0].score <= self.cfg.min_score_sell
                 and r[0].confidence >= self.cfg.min_confidence]
        sells.sort(key=lambda r: r[0].score)
        print(f"{BOLD}{RED}━━ SELL Signals ({len(sells)}) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━{RESET}")
        for pair in sells[: self.cfg.max_display]:
            self._print_analysis(pair[0], pair[1])

        # ── Pump/Dump watchlist ───────────────────────────────────────────────
        self._print_pump_dump_watchlist(results)

        # ── Portfolio P&L ─────────────────────────────────────────────────────
        self._print_portfolio()

        print(f"{DIM}Next scan in {self.cfg.scan_interval_sec}s  |  Total scanned: {len(results)}/{len(markets)}{RESET}\n")

    def run(self):
        """Continuous loop — runs until KeyboardInterrupt."""
        print(f"{BOLD}{CYAN}CoinDCX Portfolio Agent starting…{RESET}")
        if self.client.has_credentials():
            print(f"{GREEN}✔ API credentials loaded — portfolio sync enabled{RESET}")
        else:
            print(f"{YELLOW}ℹ  No API credentials — running in read-only market scan mode{RESET}")
        print(f"  Scanning top {self.cfg.top_n_markets} {self.cfg.quote_currency} pairs"
              f"  every {self.cfg.scan_interval_sec}s\n")

        while True:
            try:
                self.run_once()
                time.sleep(self.cfg.scan_interval_sec)
            except KeyboardInterrupt:
                print(f"\n{CYAN}Agent stopped.{RESET}")
                break
            except Exception as e:
                logger.exception(f"Unexpected error in scan cycle: {e}")
                time.sleep(10)
