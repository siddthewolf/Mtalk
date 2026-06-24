"""CoinDCX REST API client — public and authenticated endpoints."""

import hashlib
import hmac
import json
import time
from typing import Dict, List, Optional

import requests


class CoinDCXClient:
    BASE_URL = "https://api.coindcx.com"
    PUBLIC_URL = "https://public.coindcx.com"

    # Candle intervals supported by CoinDCX
    INTERVALS = ["1m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "1d"]

    def __init__(self, api_key: str = "", api_secret: str = "", timeout: int = 10):
        self.api_key = api_key
        self.api_secret = api_secret
        self.timeout = timeout
        self.session = requests.Session()
        self.session.headers.update({"Content-Type": "application/json"})
        # Cache: coindcx_name (e.g. "BTCUSDT") → correct pair (e.g. "B-BTC_USDT")
        self._pair_map: Dict[str, str] = {}

    def _build_pair_map(self):
        """Build coindcx_name → pair mapping from market details (cached)."""
        if self._pair_map:
            return
        details = self.get_market_details()
        for m in details:
            name = m.get("coindcx_name", "")
            pair = m.get("pair", "")
            if name and pair:
                self._pair_map[name.upper()] = pair

    def market_to_pair(self, coindcx_name: str) -> str:
        """Convert e.g. 'BTCUSDT' → 'B-BTC_USDT' or 'TAOUSDT' → 'KC-TAO_USDT'."""
        self._build_pair_map()
        return self._pair_map.get(coindcx_name.upper(), f"B-{coindcx_name.replace('USDT','')}_USDT")

    # ── private auth helpers ──────────────────────────────────────────────────

    def _sign(self, body: dict) -> Dict[str, str]:
        body_json = json.dumps(body, separators=(",", ":"))
        sig = hmac.new(
            self.api_secret.encode("utf-8"),
            body_json.encode("utf-8"),
            hashlib.sha256,
        ).hexdigest()
        return {"X-AUTH-APIKEY": self.api_key, "X-AUTH-SIGNATURE": sig}

    def _post_auth(self, path: str, extra: Optional[dict] = None) -> dict:
        body = {"timestamp": int(time.time() * 1000)}
        if extra:
            body.update(extra)
        # Signature must be computed on the exact bytes sent — no spaces
        body_json = json.dumps(body, separators=(",", ":"))
        sig = hmac.new(
            self.api_secret.encode("utf-8"),
            body_json.encode("utf-8"),
            hashlib.sha256,
        ).hexdigest()
        headers = {
            "Content-Type": "application/json",
            "X-AUTH-APIKEY": self.api_key,
            "X-AUTH-SIGNATURE": sig,
        }
        resp = self.session.post(
            f"{self.BASE_URL}{path}", data=body_json, headers=headers, timeout=self.timeout
        )
        if not resp.ok:
            raise requests.HTTPError(
                f"{resp.status_code} {resp.reason}: {resp.text}", response=resp
            )
        return resp.json()

    # ── public endpoints ──────────────────────────────────────────────────────

    def get_tickers(self) -> List[dict]:
        """All market tickers with last price, volume, bid, ask."""
        r = self.session.get(f"{self.BASE_URL}/exchange/ticker", timeout=self.timeout)
        r.raise_for_status()
        return r.json()

    def get_markets(self) -> List[dict]:
        r = self.session.get(f"{self.BASE_URL}/exchange/v1/markets", timeout=self.timeout)
        r.raise_for_status()
        return r.json()

    def get_market_details(self) -> List[dict]:
        r = self.session.get(
            f"{self.BASE_URL}/exchange/v1/markets_details", timeout=self.timeout
        )
        r.raise_for_status()
        return r.json()

    def get_candles(
        self, pair: str, interval: str = "1h", limit: int = 200
    ) -> List[dict]:
        """
        OHLCV candle data.
        pair format: 'B-BTC_USDT' (futures) or 'I-BTC_INRT' (INR spot)
        Returns list of {time, open, high, low, close, volume}.
        """
        r = self.session.get(
            f"{self.PUBLIC_URL}/market_data/candles",
            params={"pair": pair, "interval": interval, "limit": limit},
            timeout=self.timeout,
        )
        r.raise_for_status()
        data = r.json()
        # Normalise field names (CoinDCX returns o/h/l/c/v in some responses)
        normalised = []
        for c in data:
            normalised.append(
                {
                    "time": c.get("time") or c.get("t"),
                    "open": float(c.get("open") or c.get("o") or 0),
                    "high": float(c.get("high") or c.get("h") or 0),
                    "low": float(c.get("low") or c.get("l") or 0),
                    "close": float(c.get("close") or c.get("c") or 0),
                    "volume": float(c.get("volume") or c.get("v") or 0),
                }
            )
        return normalised

    def get_orderbook(self, market: str) -> dict:
        r = self.session.get(
            f"{self.BASE_URL}/market_data/orderbook",
            params={"pair": market},
            timeout=self.timeout,
        )
        r.raise_for_status()
        return r.json()

    # ── authenticated endpoints ───────────────────────────────────────────────

    def get_balances(self) -> List[dict]:
        """Requires API key & secret. Returns all coin balances."""
        return self._post_auth("/exchange/v1/users/balances")

    def get_active_orders(self) -> List[dict]:
        return self._post_auth("/exchange/v1/orders/active_orders")

    def get_trade_history(self, market: str, limit: int = 50) -> List[dict]:
        return self._post_auth(
            "/exchange/v1/orders/trade_history", {"market": market, "limit": limit}
        )

    def create_limit_order(
        self, market: str, side: str, price: float, quantity: float
    ) -> dict:
        """side: 'buy' or 'sell'. Requires API keys."""
        return self._post_auth(
            "/exchange/v1/orders/create",
            {
                "side": side,
                "order_type": "limit_order",
                "market": market,
                "price_per_unit": price,
                "total_quantity": quantity,
            },
        )

    def create_market_order(
        self, market: str, side: str, quantity: float
    ) -> dict:
        return self._post_auth(
            "/exchange/v1/orders/create",
            {
                "side": side,
                "order_type": "market_order",
                "market": market,
                "total_quantity": quantity,
            },
        )

    def cancel_order(self, order_id: str) -> dict:
        return self._post_auth("/exchange/v1/orders/cancel", {"id": order_id})

    def has_credentials(self) -> bool:
        return bool(self.api_key and self.api_secret)
