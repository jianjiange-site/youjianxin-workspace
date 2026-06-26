import os

import httpx
from pydantic import BaseModel, Field
from langchain_core.tools import tool

# NWS（美国国家气象局）要求每个请求带 User-Agent 标识应用，否则返回 403。
# 可通过 NWS_USER_AGENT 覆盖，建议填上真实联系方式。
_USER_AGENT = os.environ.get("NWS_USER_AGENT", "ai-chat (vibe-dating, contact@example.com)")
_NWS_BASE = "https://api.weather.gov"
# NWS 只认经纬度，故用 Open-Meteo geocoding 把城市名解析为坐标（免费、无需 key）。
_GEOCODE_URL = "https://geocoding-api.open-meteo.com/v1/search"


class WeatherInput(BaseModel):
    """Input schema for the get_weather tool."""

    city: str = Field(description="City name, e.g. \"New York\", \"Los Angeles\"")
    unit: str = Field(
        default="fahrenheit",
        description="Temperature unit, fahrenheit or celsius, defaults to fahrenheit",
    )


async def _geocode(client: httpx.AsyncClient, city: str) -> tuple[float, float, str] | None:
    """把美国城市名解析为 (纬度, 经度, 规范名)，找不到返回 None。"""
    resp = await client.get(
        _GEOCODE_URL,
        params={"name": city, "count": 1, "country": "US", "language": "en"},
    )
    resp.raise_for_status()
    results = resp.json().get("results")
    if not results:
        return None
    top = results[0]
    return top["latitude"], top["longitude"], top.get("name", city)


@tool(args_schema=WeatherInput)
async def get_weather(city: str, unit: str = "fahrenheit") -> str:
    """Get the current weather forecast for a US city.

    Call this when the user asks about the weather or temperature, or when you
    need weather to make a suggestion (e.g. a date spot or what to wear).
    Only US cities are supported.
    """
    # NWS forecast 接口用 units=us(华氏) / si(摄氏)
    nws_units = "si" if unit == "celsius" else "us"
    try:
        async with httpx.AsyncClient(
            timeout=10.0,
            headers={"User-Agent": _USER_AGENT},
            follow_redirects=True,
        ) as client:
            geo = await _geocode(client, city)
            if geo is None:
                return f"Sorry, I couldn't find weather for {city}."
            lat, lon, name = geo

            # 1) points 查询，拿到该坐标对应的预报 URL。
            # NWS 限制坐标精度，超过 4 位小数会 301 重定向，故先四舍五入。
            points = await client.get(f"{_NWS_BASE}/points/{lat:.4f},{lon:.4f}")
            points.raise_for_status()
            forecast_url = points.json()["properties"]["forecast"]

            # 2) 取预报
            forecast = await client.get(forecast_url, params={"units": nws_units})
            forecast.raise_for_status()
            periods = forecast.json()["properties"]["periods"]
    except (httpx.HTTPError, KeyError, ValueError):
        # 网络/解析异常一律降级为友好文案，避免把异常抛给 LLM 当 tool error
        return f"Sorry, I couldn't get the weather for {city} right now."

    if not periods:
        return f"Sorry, no forecast is available for {city} right now."

    # 取最近 1–2 个时段拼成简洁摘要
    parts = [
        f"{p['name']} — {p['temperature']}°{p['temperatureUnit']}, {p['shortForecast']}"
        for p in periods[:2]
    ]
    return f"{name}: " + ". ".join(parts) + "."
