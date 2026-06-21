"""
CoinDCX Mobile Dashboard — Flask web server.
Open http://<your-server-ip>:5000 on your phone browser.
"""

import json
import os
import threading
import time
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor, as_completed

from flask import Flask, jsonify, render_template_string, request, redirect, url_for

from coindcx_agent.api_client import CoinDCXClient
from coindcx_agent.analyzer import MarketAnalyzer, Signal
from coindcx_agent.pump_dump import PumpDumpDetector, PDSignal
from coindcx_agent.portfolio import Portfolio

app = Flask(__name__)

# ── state shared between background thread and requests ──────────────────────
_lock = threading.Lock()
_state = {
    "last_scan": None,
    "signals": [],
    "pd_alerts": [],
    "portfolio": {},
    "prices": {},
    "scanning": False,
    "error": None,
}

ENV_FILE = os.path.join(os.path.dirname(__file__), ".env")


# ── credential helpers ────────────────────────────────────────────────────────

def _read_creds():
    creds = {"COINDCX_API_KEY": "", "COINDCX_API_SECRET": ""}
    if os.path.exists(ENV_FILE):
        with open(ENV_FILE) as f:
            for line in f:
                line = line.strip()
                if "=" in line and not line.startswith("#"):
                    k, _, v = line.partition("=")
                    creds[k.strip()] = v.strip()
    return creds


def _save_creds(api_key: str, api_secret: str):
    lines = []
    if os.path.exists(ENV_FILE):
        with open(ENV_FILE) as f:
            lines = f.readlines()
    # Update or append
    keys_written = set()
    new_lines = []
    for line in lines:
        if line.startswith("COINDCX_API_KEY="):
            new_lines.append(f"COINDCX_API_KEY={api_key}\n")
            keys_written.add("key")
        elif line.startswith("COINDCX_API_SECRET="):
            new_lines.append(f"COINDCX_API_SECRET={api_secret}\n")
            keys_written.add("secret")
        else:
            new_lines.append(line)
    if "key" not in keys_written:
        new_lines.append(f"COINDCX_API_KEY={api_key}\n")
    if "secret" not in keys_written:
        new_lines.append(f"COINDCX_API_SECRET={api_secret}\n")
    with open(ENV_FILE, "w") as f:
        f.writelines(new_lines)


def _get_client():
    creds = _read_creds()
    return CoinDCXClient(
        api_key=creds["COINDCX_API_KEY"],
        api_secret=creds["COINDCX_API_SECRET"],
    )


# ── background scanner ────────────────────────────────────────────────────────

def _run_scan():
    with _lock:
        if _state["scanning"]:
            return
        _state["scanning"] = True
        _state["error"] = None

    try:
        client = _get_client()
        pd_det = PumpDumpDetector()
        analyzer = MarketAnalyzer(pd_det)
        portfolio = Portfolio()

        # Fetch top 50 USDT tickers
        tickers = client.get_tickers()
        usdt = [t for t in tickers if t.get("market", "").upper().endswith("USDT")]
        usdt.sort(key=lambda t: float(t.get("volume", 0) or 0), reverse=True)
        markets = [t["market"].upper() for t in usdt[:50]]

        prices = {}
        for t in usdt:
            sym = t["market"].upper().replace("USDT", "")
            p = float(t.get("last_price", 0) or 0)
            if p:
                prices[sym] = p

        def fetch_one(market):
            base = market.replace("USDT", "")
            pair = f"B-{base}_USDT"
            try:
                candles = client.get_candles(pair, "1h", 200)
                if len(candles) < 60:
                    return None
                r1h = analyzer.analyze(market, candles, "1h")
                if not r1h:
                    return None
                try:
                    c15 = client.get_candles(pair, "15m", 200)
                    r15 = analyzer.analyze(market, c15, "15m")
                except Exception:
                    r15 = None
                return r1h, r15
            except Exception:
                return None

        results = []
        with ThreadPoolExecutor(max_workers=10) as pool:
            futs = {pool.submit(fetch_one, m): m for m in markets}
            for fut in as_completed(futs):
                res = fut.result()
                if res:
                    results.append(res)

        results.sort(key=lambda r: r[0].score, reverse=True)

        signals = []
        pd_alerts = []

        for r1h, r15 in results:
            a = r1h
            ind = a.indicators
            confirm = None
            if r15:
                if r15.signal in (Signal.BUY, Signal.STRONG_BUY) and a.signal in (Signal.BUY, Signal.STRONG_BUY):
                    confirm = "✔ Confirmed " + r15.timeframe
                elif r15.signal in (Signal.SELL, Signal.STRONG_SELL) and a.signal in (Signal.SELL, Signal.STRONG_SELL):
                    confirm = "✔ Confirmed " + r15.timeframe
                else:
                    confirm = r15.signal.value + " on " + r15.timeframe

            pd = a.pump_dump
            if pd.signal != PDSignal.NORMAL and pd.confidence >= 0.3:
                pd_alerts.append({
                    "symbol": a.symbol,
                    "signal": pd.signal.value,
                    "confidence": round(pd.confidence * 100),
                    "vol_ratio": round(pd.volume_ratio, 2),
                    "change_1h": round(pd.price_change_1h, 2),
                    "change_short": round(pd.price_change_short, 2),
                    "reasons": pd.reasons,
                })

            signals.append({
                "symbol": a.symbol,
                "price": a.price,
                "score": a.score,
                "signal": a.signal.value,
                "signal_key": a.signal.name,
                "confidence": round(a.confidence * 100),
                "confirm": confirm,
                "rsi": round(ind.rsi, 1),
                "rsi_label": ind.rsi_label,
                "macd": ind.macd_label,
                "bb": ind.bb_label,
                "bb_pct": round(ind.bb_pct * 100),
                "ema": ind.ema_label,
                "stoch": ind.stoch_label,
                "vol_x": round(ind.volume_ratio, 2),
                "vol_label": ind.volume_label,
                "obv": ind.obv_trend,
                "ichi": ind.ichimoku_label,
                "atr_pct": round(ind.atr_pct, 2),
                "entry": a.entry,
                "sl": a.stop_loss,
                "tp1": a.take_profit_1,
                "tp2": a.take_profit_2,
                "rr": a.risk_reward,
                "supports": a.supports,
                "resistances": a.resistances,
                "pd_signal": pd.signal.value,
                "pd_conf": round(pd.confidence * 100),
            })

        # Portfolio sync if credentials available
        portfolio_data = {}
        if client.has_credentials():
            try:
                balances = client.get_balances()
                portfolio.sync_from_exchange(balances, prices)
                summ = portfolio.summary(prices)
                portfolio_data = summ
            except Exception as e:
                portfolio_data = {"error": str(e)}

        with _lock:
            _state["signals"] = signals
            _state["pd_alerts"] = pd_alerts
            _state["portfolio"] = portfolio_data
            _state["prices"] = prices
            _state["last_scan"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    except Exception as e:
        with _lock:
            _state["error"] = str(e)
    finally:
        with _lock:
            _state["scanning"] = False


def _background_loop():
    while True:
        _run_scan()
        time.sleep(60)


# ── HTML template ─────────────────────────────────────────────────────────────

HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<title>CoinDCX Agent</title>
<style>
  *{box-sizing:border-box;margin:0;padding:0}
  body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#0d1117;color:#e6edf3;min-height:100vh}
  .header{background:#161b22;border-bottom:1px solid #30363d;padding:12px 16px;position:sticky;top:0;z-index:100;display:flex;align-items:center;justify-content:space-between}
  .header h1{font-size:18px;font-weight:700;color:#58a6ff}
  .badge{font-size:11px;padding:3px 8px;border-radius:12px;font-weight:600}
  .badge-green{background:#1a4731;color:#3fb950}
  .badge-red{background:#4d1818;color:#f85149}
  .badge-yellow{background:#3d2e00;color:#d29922}
  .badge-gray{background:#21262d;color:#8b949e}
  .tabs{display:flex;background:#161b22;border-bottom:1px solid #30363d;overflow-x:auto;-webkit-overflow-scrolling:touch}
  .tab{flex:1;min-width:70px;padding:10px 4px;text-align:center;font-size:12px;font-weight:600;color:#8b949e;cursor:pointer;border-bottom:2px solid transparent;white-space:nowrap}
  .tab.active{color:#58a6ff;border-bottom-color:#58a6ff}
  .page{display:none;padding:12px}
  .page.active{display:block}
  .card{background:#161b22;border:1px solid #30363d;border-radius:8px;padding:12px;margin-bottom:10px}
  .card-header{display:flex;align-items:center;justify-content:space-between;margin-bottom:8px}
  .symbol{font-size:15px;font-weight:700}
  .price{font-size:14px;color:#8b949e}
  .signal-row{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:6px}
  .score{font-size:13px;font-weight:700}
  .score.pos{color:#3fb950}
  .score.neg{color:#f85149}
  .indicators{display:grid;grid-template-columns:1fr 1fr;gap:4px;margin-top:8px}
  .ind-item{background:#0d1117;border-radius:4px;padding:5px 8px;font-size:11px}
  .ind-label{color:#8b949e;font-size:10px}
  .ind-val{font-weight:600;margin-top:1px}
  .trade-box{background:#0d1117;border-radius:6px;padding:8px;margin-top:8px;font-size:12px}
  .trade-row{display:flex;justify-content:space-between;padding:2px 0;border-bottom:1px solid #21262d}
  .trade-row:last-child{border:none}
  .tl{color:#8b949e}
  .tv{font-weight:600}
  .tv.green{color:#3fb950}
  .tv.red{color:#f85149}
  .tv.yellow{color:#d29922}
  .confirm{font-size:11px;color:#3fb950;margin-top:4px}
  .pd-card{background:#1c1505;border:1px solid #d29922;border-radius:8px;padding:12px;margin-bottom:10px}
  .pd-title{color:#d29922;font-weight:700;font-size:14px}
  .pd-reason{font-size:11px;color:#8b949e;margin-top:4px}
  .pd-reason li{margin-left:16px;margin-top:2px}
  .pf-card{background:#161b22;border:1px solid #30363d;border-radius:8px;padding:12px;margin-bottom:10px}
  .pf-total{background:#0d1117;border-radius:6px;padding:10px;margin-top:10px;text-align:center}
  .setup-box{background:#161b22;border:1px solid #30363d;border-radius:12px;padding:20px;margin:20px 12px}
  .setup-box h2{color:#58a6ff;margin-bottom:12px;font-size:16px}
  .setup-box p{color:#8b949e;font-size:13px;margin-bottom:16px;line-height:1.5}
  .form-group{margin-bottom:12px}
  .form-group label{display:block;font-size:12px;color:#8b949e;margin-bottom:4px;font-weight:600}
  .form-group input{width:100%;background:#0d1117;border:1px solid #30363d;border-radius:6px;padding:10px;color:#e6edf3;font-size:14px;font-family:monospace}
  .form-group input:focus{outline:none;border-color:#58a6ff}
  .btn{width:100%;padding:12px;background:#238636;color:#fff;border:none;border-radius:8px;font-size:15px;font-weight:700;cursor:pointer;margin-top:8px}
  .btn:active{background:#2ea043}
  .btn-outline{background:transparent;border:1px solid #30363d;color:#8b949e}
  .step{display:flex;gap:10px;margin-bottom:12px}
  .step-num{background:#1f6feb;color:#fff;border-radius:50%;width:24px;height:24px;display:flex;align-items:center;justify-content:center;font-size:12px;font-weight:700;flex-shrink:0}
  .step-text{font-size:13px;color:#8b949e;line-height:1.5}
  .step-text a{color:#58a6ff}
  .link-btn{display:block;background:#1f6feb;color:#fff;text-align:center;padding:12px;border-radius:8px;text-decoration:none;font-weight:700;font-size:15px;margin:12px 0}
  .scan-btn{position:fixed;bottom:20px;right:16px;background:#238636;color:#fff;border:none;border-radius:50px;padding:12px 20px;font-size:14px;font-weight:700;cursor:pointer;box-shadow:0 4px 12px rgba(0,0,0,0.5);z-index:200}
  .last-scan{font-size:11px;color:#8b949e;text-align:center;padding:6px;margin-bottom:8px}
  .empty{text-align:center;color:#8b949e;padding:40px 20px;font-size:14px}
  .filter-row{display:flex;gap:6px;margin-bottom:10px;overflow-x:auto;-webkit-overflow-scrolling:touch;padding-bottom:4px}
  .filter-btn{padding:6px 14px;border-radius:20px;font-size:12px;font-weight:600;border:1px solid #30363d;background:#161b22;color:#8b949e;cursor:pointer;white-space:nowrap;flex-shrink:0}
  .filter-btn.active{background:#1f6feb;border-color:#1f6feb;color:#fff}
  .spinner{display:inline-block;width:16px;height:16px;border:2px solid #30363d;border-top-color:#58a6ff;border-radius:50%;animation:spin .8s linear infinite;vertical-align:middle;margin-right:6px}
  @keyframes spin{to{transform:rotate(360deg)}}
  .alert-bar{background:#3d2e00;border:1px solid #d29922;border-radius:6px;padding:8px 12px;margin-bottom:10px;font-size:12px;color:#d29922}
</style>
</head>
<body>

<div class="header">
  <h1>⚡ CoinDCX Agent</h1>
  <span id="status-badge" class="badge badge-gray">Loading…</span>
</div>

<div class="tabs">
  <div class="tab active" onclick="switchTab('signals')">📊 Signals</div>
  <div class="tab" onclick="switchTab('pumpDump')">⚠️ P&D</div>
  <div class="tab" onclick="switchTab('portfolio')">💼 Portfolio</div>
  <div class="tab" onclick="switchTab('setup')">🔑 Setup</div>
</div>

<!-- SIGNALS PAGE -->
<div id="page-signals" class="page active">
  <div class="last-scan" id="last-scan-label">Fetching data…</div>
  <div class="filter-row">
    <div class="filter-btn active" onclick="setFilter('all',this)">All</div>
    <div class="filter-btn" onclick="setFilter('STRONG_BUY',this)">🟢 Strong Buy</div>
    <div class="filter-btn" onclick="setFilter('BUY',this)">🟩 Buy</div>
    <div class="filter-btn" onclick="setFilter('SELL',this)">🟥 Sell</div>
    <div class="filter-btn" onclick="setFilter('STRONG_SELL',this)">🔴 Strong Sell</div>
    <div class="filter-btn" onclick="setFilter('AVOID',this)">⛔ Avoid</div>
  </div>
  <div id="signals-list"><div class="empty"><span class="spinner"></span> Scanning markets…</div></div>
</div>

<!-- PUMP & DUMP PAGE -->
<div id="page-pumpDump" class="page">
  <div id="pd-list"><div class="empty"><span class="spinner"></span> Scanning…</div></div>
</div>

<!-- PORTFOLIO PAGE -->
<div id="page-portfolio" class="page">
  <div id="pf-content"><div class="empty"><span class="spinner"></span> Loading…</div></div>
</div>

<!-- SETUP PAGE -->
<div id="page-setup" class="page">
  <div class="setup-box">
    <h2>🔑 Connect Your CoinDCX Account</h2>
    <p>You need to generate an API key from CoinDCX. Tap the button below to open the API dashboard — it takes 2 minutes.</p>

    <a class="link-btn" href="https://coindcx.com/api-dashboard" target="_blank">
      Open CoinDCX API Dashboard →
    </a>

    <p style="margin-bottom:16px"><strong style="color:#e6edf3">Steps on the website:</strong></p>

    <div class="step"><div class="step-num">1</div><div class="step-text">Log in to your CoinDCX account</div></div>
    <div class="step"><div class="step-num">2</div><div class="step-text">Click <strong style="color:#e6edf3">"Create New API Key"</strong></div></div>
    <div class="step"><div class="step-num">3</div><div class="step-text">Give it a name like <em>PortfolioAgent</em></div></div>
    <div class="step"><div class="step-num">4</div><div class="step-text">Enable <strong style="color:#e6edf3">Read</strong> permission (Trade only if you want auto-orders)</div></div>
    <div class="step"><div class="step-num">5</div><div class="step-text">Complete OTP verification</div></div>
    <div class="step"><div class="step-num">6</div><div class="step-text"><strong style="color:#d29922">Copy both the API Key and Secret Key — secret is shown only once!</strong></div></div>

    <hr style="border-color:#30363d;margin:16px 0">
    <p style="margin-bottom:16px">Paste your keys below:</p>

    <form onsubmit="saveKeys(event)">
      <div class="form-group">
        <label>API KEY</label>
        <input type="text" id="inp-key" placeholder="Paste your API Key" autocomplete="off" autocorrect="off" spellcheck="false">
      </div>
      <div class="form-group">
        <label>SECRET KEY</label>
        <input type="password" id="inp-secret" placeholder="Paste your Secret Key" autocomplete="off">
      </div>
      <button type="submit" class="btn">Save & Connect</button>
    </form>

    <div id="setup-msg" style="margin-top:12px;font-size:13px;text-align:center"></div>

    <hr style="border-color:#30363d;margin:16px 0">
    <p style="font-size:12px;color:#8b949e;text-align:center">
      Keys are stored only on this server in .env file.<br>Never shared externally.
    </p>
  </div>
</div>

<button class="scan-btn" onclick="triggerScan()">🔄 Scan Now</button>

<script>
let _signals = [];
let _filter = 'all';

function switchTab(tab) {
  document.querySelectorAll('.tab').forEach((t,i) => {
    t.classList.toggle('active', ['signals','pumpDump','portfolio','setup'][i] === tab);
  });
  document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
  document.getElementById('page-' + tab).classList.add('active');
}

function setFilter(f, el) {
  _filter = f;
  document.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
  el.classList.add('active');
  renderSignals();
}

function colorForKey(key) {
  if (key === 'STRONG_BUY') return '#3fb950';
  if (key === 'BUY') return '#2ea043';
  if (key === 'STRONG_SELL') return '#f85149';
  if (key === 'SELL') return '#da3633';
  if (key === 'AVOID') return '#d29922';
  return '#8b949e';
}

function badgeClass(key) {
  if (key === 'STRONG_BUY' || key === 'BUY') return 'badge-green';
  if (key === 'STRONG_SELL' || key === 'SELL') return 'badge-red';
  if (key === 'AVOID') return 'badge-yellow';
  return 'badge-gray';
}

function fmt(n) {
  if (n == null) return '—';
  if (Math.abs(n) < 0.001) return n.toExponential(4);
  if (Math.abs(n) >= 10000) return n.toLocaleString('en-IN', {maximumFractionDigits:2});
  return n.toPrecision(6).replace(/\.?0+$/, '');
}

function renderSignals() {
  const list = document.getElementById('signals-list');
  let data = _signals;
  if (_filter !== 'all') data = data.filter(s => s.signal_key === _filter);
  if (data.length === 0) {
    list.innerHTML = '<div class="empty">No signals matching filter.</div>';
    return;
  }
  list.innerHTML = data.map(s => {
    const col = colorForKey(s.signal_key);
    const sc = s.score >= 0 ? `<span class="score pos">+${s.score}</span>` : `<span class="score neg">${s.score}</span>`;
    const pdWarn = s.pd_conf > 30 ? `<div style="font-size:11px;color:#d29922;margin-top:4px">${s.pd_signal} (${s.pd_conf}% conf)</div>` : '';
    const confirmHtml = s.confirm ? `<div class="confirm">${s.confirm}</div>` : '';
    const tradeHtml = s.entry ? `
      <div class="trade-box">
        <div class="trade-row"><span class="tl">Entry</span><span class="tv">${fmt(s.entry)}</span></div>
        <div class="trade-row"><span class="tl">Stop Loss</span><span class="tv red">${fmt(s.sl)}</span></div>
        <div class="trade-row"><span class="tl">Take Profit 1</span><span class="tv green">${fmt(s.tp1)}</span></div>
        <div class="trade-row"><span class="tl">Take Profit 2</span><span class="tv green">${fmt(s.tp2)}</span></div>
        <div class="trade-row"><span class="tl">Risk : Reward</span><span class="tv yellow">1 : ${s.rr}</span></div>
      </div>` : '';
    const supp = s.supports.length ? `<div style="font-size:11px;color:#8b949e;margin-top:4px">Support: ${s.supports.map(fmt).join(' / ')}</div>` : '';
    const res = s.resistances.length ? `<div style="font-size:11px;color:#8b949e;margin-top:2px">Resist: ${s.resistances.map(fmt).join(' / ')}</div>` : '';
    return `
      <div class="card">
        <div class="card-header">
          <span class="symbol">${s.symbol.replace('USDT','')}</span>
          <span class="price">${fmt(s.price)} USDT</span>
        </div>
        <div class="signal-row">
          <span class="badge ${badgeClass(s.signal_key)}" style="color:${col}">${s.signal}</span>
          ${sc}
          <span style="font-size:12px;color:#8b949e">${s.confidence}% conf</span>
        </div>
        ${confirmHtml}
        ${pdWarn}
        <div class="indicators">
          <div class="ind-item"><div class="ind-label">RSI</div><div class="ind-val">${s.rsi} — ${s.rsi_label}</div></div>
          <div class="ind-item"><div class="ind-label">MACD</div><div class="ind-val">${s.macd}</div></div>
          <div class="ind-item"><div class="ind-label">Bollinger</div><div class="ind-val">${s.bb} (${s.bb_pct}%)</div></div>
          <div class="ind-item"><div class="ind-label">EMA Trend</div><div class="ind-val">${s.ema}</div></div>
          <div class="ind-item"><div class="ind-label">Stochastic</div><div class="ind-val">${s.stoch}</div></div>
          <div class="ind-item"><div class="ind-label">Volume</div><div class="ind-val">×${s.vol_x} ${s.vol_label}</div></div>
          <div class="ind-item"><div class="ind-label">OBV</div><div class="ind-val">${s.obv}</div></div>
          <div class="ind-item"><div class="ind-label">Ichimoku</div><div class="ind-val">${s.ichi}</div></div>
          <div class="ind-item"><div class="ind-label">ATR (volatility)</div><div class="ind-val">${s.atr_pct}%</div></div>
          <div class="ind-item"><div class="ind-label">ATR period</div><div class="ind-val">14 candles</div></div>
        </div>
        ${tradeHtml}
        ${supp}${res}
      </div>`;
  }).join('');
}

function renderPD(pd_alerts) {
  const list = document.getElementById('pd-list');
  if (!pd_alerts.length) {
    list.innerHTML = '<div class="empty">✅ No pump & dump patterns detected right now.</div>';
    return;
  }
  list.innerHTML = pd_alerts.map(a => `
    <div class="pd-card">
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span class="pd-title">${a.symbol.replace('USDT','')}</span>
        <span class="badge badge-yellow">${a.confidence}% conf</span>
      </div>
      <div style="margin-top:6px;font-size:13px;color:#d29922">${a.signal}</div>
      <div style="margin-top:6px;display:flex;gap:16px;font-size:12px;color:#8b949e">
        <span>Vol ×${a.vol_ratio}</span>
        <span>Δ1h: ${a.change_1h > 0 ? '+' : ''}${a.change_1h}%</span>
        <span>Δshort: ${a.change_short > 0 ? '+' : ''}${a.change_short}%</span>
      </div>
      <ul class="pd-reason">
        ${a.reasons.map(r => `<li>${r}</li>`).join('')}
      </ul>
    </div>`).join('');
}

function renderPortfolio(pf) {
  const el = document.getElementById('pf-content');
  if (!pf || !pf.holdings) {
    el.innerHTML = `
      <div class="setup-box">
        <h2>💼 Portfolio Sync</h2>
        <p>Add your CoinDCX API key in the Setup tab to automatically sync your holdings and see real-time P&L.</p>
        <button class="btn" onclick="switchTab('setup')">Go to Setup →</button>
      </div>`;
    return;
  }
  if (pf.error) {
    el.innerHTML = `<div class="alert-bar">⚠ Could not sync portfolio: ${pf.error}</div>`;
    return;
  }
  const rows = pf.holdings.map(r => {
    const pnlCol = r.pnl_pct >= 0 ? 'green' : 'red';
    const sign = r.pnl_pct >= 0 ? '+' : '';
    return `
      <div class="pf-card">
        <div style="display:flex;justify-content:space-between;align-items:center">
          <span class="symbol">${r.symbol}</span>
          <span class="badge ${r.pnl_pct >= 0 ? 'badge-green' : 'badge-red'}">${sign}${r.pnl_pct.toFixed(2)}%</span>
        </div>
        <div style="margin-top:8px;display:grid;grid-template-columns:1fr 1fr;gap:4px">
          <div class="ind-item"><div class="ind-label">Qty</div><div class="ind-val">${r.qty.toPrecision(4)}</div></div>
          <div class="ind-item"><div class="ind-label">Current Price</div><div class="ind-val">${fmt(r.current_price)} USDT</div></div>
          <div class="ind-item"><div class="ind-label">Avg Buy</div><div class="ind-val">${fmt(r.avg_buy)} USDT</div></div>
          <div class="ind-item"><div class="ind-label">Current Value</div><div class="ind-val">${r.current_value.toFixed(2)} USDT</div></div>
          <div class="ind-item"><div class="ind-label">Invested</div><div class="ind-val">${r.invested.toFixed(2)} USDT</div></div>
          <div class="ind-item"><div class="ind-label">P&L</div><div class="ind-val tv ${pnlCol}">${sign}${r.pnl_abs.toFixed(2)} USDT</div></div>
        </div>
      </div>`;
  }).join('');

  const totalSign = pf.overall_pnl >= 0 ? '+' : '';
  el.innerHTML = rows + `
    <div class="pf-total">
      <div style="font-size:12px;color:#8b949e;margin-bottom:4px">Total Portfolio</div>
      <div style="font-size:18px;font-weight:700;color:${pf.overall_pnl >= 0 ? '#3fb950' : '#f85149'}">
        ${totalSign}${pf.overall_pnl.toFixed(2)} USDT
        <span style="font-size:13px">(${totalSign}${pf.overall_pnl_pct.toFixed(2)}%)</span>
      </div>
      <div style="font-size:12px;color:#8b949e;margin-top:4px">
        Invested: ${pf.total_invested.toFixed(2)} → Now: ${pf.total_value.toFixed(2)} USDT
      </div>
    </div>`;
}

async function loadData() {
  try {
    const r = await fetch('/api/state');
    const data = await r.json();
    _signals = data.signals || [];
    const badge = document.getElementById('status-badge');
    const label = document.getElementById('last-scan-label');
    if (data.scanning) {
      badge.className = 'badge badge-yellow';
      badge.textContent = '⏳ Scanning…';
    } else if (data.error) {
      badge.className = 'badge badge-red';
      badge.textContent = '⚠ Error';
    } else if (data.last_scan) {
      badge.className = 'badge badge-green';
      badge.textContent = '● Live';
      label.textContent = 'Last scan: ' + data.last_scan;
    }
    renderSignals();
    renderPD(data.pd_alerts || []);
    renderPortfolio(data.portfolio || {});
  } catch(e) {
    console.error(e);
  }
}

async function triggerScan() {
  const btn = document.querySelector('.scan-btn');
  btn.textContent = '⏳ Scanning…';
  btn.disabled = true;
  await fetch('/api/scan', {method:'POST'});
  await new Promise(r => setTimeout(r, 3000));
  await loadData();
  btn.textContent = '🔄 Scan Now';
  btn.disabled = false;
}

async function saveKeys(e) {
  e.preventDefault();
  const key = document.getElementById('inp-key').value.trim();
  const secret = document.getElementById('inp-secret').value.trim();
  const msg = document.getElementById('setup-msg');
  if (!key || !secret) { msg.textContent = '⚠ Both fields required.'; msg.style.color='#f85149'; return; }
  msg.textContent = 'Saving…';
  msg.style.color = '#8b949e';
  const r = await fetch('/api/save-keys', {
    method: 'POST',
    headers: {'Content-Type':'application/json'},
    body: JSON.stringify({api_key: key, api_secret: secret})
  });
  const data = await r.json();
  if (data.ok) {
    msg.textContent = '✔ Connected! Scanning your account now…';
    msg.style.color = '#3fb950';
    triggerScan();
    setTimeout(() => switchTab('portfolio'), 3000);
  } else {
    msg.textContent = '✖ Error: ' + data.error;
    msg.style.color = '#f85149';
  }
}

// auto-refresh every 30s
loadData();
setInterval(loadData, 30000);
</script>
</body>
</html>"""


# ── Flask routes ──────────────────────────────────────────────────────────────

@app.route("/")
def index():
    return render_template_string(HTML)


@app.route("/api/state")
def api_state():
    with _lock:
        return jsonify(dict(_state))


@app.route("/api/scan", methods=["POST"])
def api_scan():
    t = threading.Thread(target=_run_scan, daemon=True)
    t.start()
    return jsonify({"ok": True, "message": "Scan started"})


@app.route("/api/save-keys", methods=["POST"])
def api_save_keys():
    data = request.get_json()
    api_key = (data.get("api_key") or "").strip()
    api_secret = (data.get("api_secret") or "").strip()
    if not api_key or not api_secret:
        return jsonify({"ok": False, "error": "Both key and secret required"})
    try:
        _save_creds(api_key, api_secret)
        return jsonify({"ok": True})
    except Exception as e:
        return jsonify({"ok": False, "error": str(e)})


@app.route("/api/portfolio/add", methods=["POST"])
def api_add_holding():
    data = request.get_json()
    p = Portfolio()
    p.add_holding(
        data["symbol"].upper(),
        float(data["quantity"]),
        float(data["buy_price"]),
        data["market"].upper(),
    )
    return jsonify({"ok": True})


if __name__ == "__main__":
    # Start background scan immediately
    t = threading.Thread(target=_background_loop, daemon=True)
    t.start()
    print("\n" + "="*50)
    print("  CoinDCX Agent Dashboard")
    print("="*50)
    print("  Open on your phone:")
    print("  http://<your-ip>:5000")
    print()
    print("  Or locally: http://localhost:5000")
    print("="*50 + "\n")
    app.run(host="0.0.0.0", port=5000, debug=False, use_reloader=False)
