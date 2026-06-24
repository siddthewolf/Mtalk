#!/usr/bin/env python3
"""
Entry point for the CoinDCX Portfolio Agent.

Usage:
    python run_agent.py                    # scan top 50 USDT pairs every 60s
    python run_agent.py --top 30           # scan top 30 pairs
    python run_agent.py --interval 30      # scan every 30 seconds
    python run_agent.py --once             # single scan, then exit

Environment variables (set in .env or export):
    COINDCX_API_KEY      — your CoinDCX API key (optional, for portfolio sync)
    COINDCX_API_SECRET   — your CoinDCX API secret (optional)

Portfolio management commands (interactive mode):
    --add-holding BTC 0.5 40000 BTCUSDT   — add a BTC holding
    --add-alert BTC price_below 35000      — set a price alert
"""

import argparse
import logging
import os
import sys

# Load .env if present
try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    pass  # python-dotenv optional

from coindcx_agent import CoinDCXAgent
from coindcx_agent.agent import AgentConfig
from coindcx_agent.portfolio import Portfolio


def parse_args():
    p = argparse.ArgumentParser(description="CoinDCX Portfolio Agent")
    p.add_argument("--top", type=int, default=50, help="Top N markets by volume (default 50)")
    p.add_argument("--interval", type=int, default=60, help="Scan interval in seconds (default 60)")
    p.add_argument("--once", action="store_true", help="Run one scan then exit")
    p.add_argument("--quote", default="USDT", help="Quote currency (default USDT)")
    p.add_argument("--primary-tf", default="1h", help="Primary timeframe (default 1h)")
    p.add_argument("--confirm-tf", default="15m", help="Confirmation timeframe (default 15m)")
    p.add_argument("--min-score", type=int, default=25, help="Min score to show BUY signal")
    p.add_argument("--min-conf", type=float, default=0.30, help="Min confidence 0–1 (default 0.30)")
    p.add_argument("--show-hold", action="store_true", help="Also show HOLD signals")
    p.add_argument("--vol-spike", type=float, default=3.0, help="Volume spike multiplier for P&D (default 3)")
    p.add_argument("--portfolio-file", default="portfolio.json", help="Portfolio data file")
    p.add_argument("--verbose", "-v", action="store_true", help="Verbose logging")

    # Portfolio management
    sub = p.add_subparsers(dest="cmd")
    add_h = sub.add_parser("add-holding", help="Add a portfolio holding")
    add_h.add_argument("symbol", help="Coin symbol, e.g. BTC")
    add_h.add_argument("quantity", type=float)
    add_h.add_argument("buy_price", type=float)
    add_h.add_argument("market", help="Market pair, e.g. BTCUSDT")

    sell_h = sub.add_parser("sell-holding", help="Record a sell")
    sell_h.add_argument("symbol")
    sell_h.add_argument("quantity", type=float)
    sell_h.add_argument("sell_price", type=float)

    add_a = sub.add_parser("add-alert", help="Add a price/PnL alert")
    add_a.add_argument("symbol")
    add_a.add_argument("kind", choices=["price_above", "price_below", "pnl_above", "pnl_below"])
    add_a.add_argument("threshold", type=float)

    sub.add_parser("portfolio", help="Print portfolio summary and exit")

    return p.parse_args()


def main():
    args = parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.WARNING,
        format="%(levelname)s %(name)s: %(message)s",
    )

    # ── Portfolio management sub-commands ─────────────────────────────────────
    if args.cmd == "add-holding":
        p = Portfolio(args.portfolio_file)
        p.add_holding(args.symbol.upper(), args.quantity, args.buy_price, args.market.upper())
        print(f"✔ Added {args.quantity} {args.symbol.upper()} @ {args.buy_price}")
        return

    if args.cmd == "sell-holding":
        p = Portfolio(args.portfolio_file)
        p.remove_holding(args.symbol.upper(), args.quantity, args.sell_price)
        print(f"✔ Recorded sell of {args.quantity} {args.symbol.upper()} @ {args.sell_price}")
        return

    if args.cmd == "add-alert":
        p = Portfolio(args.portfolio_file)
        p.add_alert(args.symbol.upper(), args.kind, args.threshold)
        print(f"✔ Alert set: {args.symbol.upper()} {args.kind} @ {args.threshold}")
        return

    if args.cmd == "portfolio":
        p = Portfolio(args.portfolio_file)
        from coindcx_agent.api_client import CoinDCXClient
        client = CoinDCXClient()
        prices: dict = {}
        try:
            for t in client.get_tickers():
                sym = t["market"].upper().replace("USDT", "")
                prices[sym] = float(t.get("last_price", 0) or 0)
        except Exception as e:
            print(f"Could not fetch live prices: {e}")
        summ = p.summary(prices)
        for row in summ["holdings"]:
            sign = "+" if row["pnl_pct"] >= 0 else ""
            print(
                f"  {row['symbol']:<8}  qty={row['qty']:.4f}  "
                f"avg={row['avg_buy']:.4g}  now={row['current_price']:.4g}  "
                f"P&L: {sign}{row['pnl_abs']:.2f} USDT ({sign}{row['pnl_pct']:.2f}%)"
            )
        sign = "+" if summ["overall_pnl"] >= 0 else ""
        print(
            f"\n  Total: {summ['total_invested']:.2f} → {summ['total_value']:.2f} USDT  "
            f"P&L: {sign}{summ['overall_pnl']:.2f} USDT ({sign}{summ['overall_pnl_pct']:.2f}%)"
        )
        return

    # ── Main agent ────────────────────────────────────────────────────────────
    cfg = AgentConfig(
        api_key=os.getenv("COINDCX_API_KEY", ""),
        api_secret=os.getenv("COINDCX_API_SECRET", ""),
        top_n_markets=args.top,
        scan_interval_sec=args.interval,
        quote_currency=args.quote,
        primary_tf=args.primary_tf,
        confirm_tf=args.confirm_tf,
        min_score_buy=args.min_score,
        min_score_sell=-args.min_score,
        min_confidence=args.min_conf,
        show_hold=args.show_hold,
        vol_spike_x=args.vol_spike,
        portfolio_file=args.portfolio_file,
    )

    agent = CoinDCXAgent(cfg)

    if args.once:
        agent.run_once()
    else:
        agent.run()


if __name__ == "__main__":
    main()
