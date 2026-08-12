"""Deterministic Baidu NOX HTTP 405 simulator for Android emulator QA."""

from mitmproxy import ctx, http


YAMIBO_HOST = "bbs.yamibo.com"
SIMULATOR_COOKIE_NAME = "nox_jst_v1"
SIMULATOR_COOKIE_VALUE = "yamibo_http405_simulator"
APP_WEBVIEW_USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
)

CHALLENGE_HTML = f"""<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <script>
    window.__noxExpire=30;window.__noxDomain="";window.__noxImd=1;
    document.cookie="{SIMULATOR_COOKIE_NAME}={SIMULATOR_COOKIE_VALUE}; Path=/; Secure; SameSite=Lax";
  </script>
  <!-- /nox_ gangplank_ markers intentionally mirror the captured Baidu challenge. -->
</head>
<body></body>
</html>""".encode("utf-8")


def _cookie_pairs(raw_header: str) -> list[tuple[str, str]]:
    pairs: list[tuple[str, str]] = []
    for part in raw_header.split(";"):
        name, separator, value = part.strip().partition("=")
        if separator and name:
            pairs.append((name, value))
    return pairs


def _has_simulator_cookie(raw_header: str) -> bool:
    return any(
        name == SIMULATOR_COOKIE_NAME and value == SIMULATOR_COOKIE_VALUE
        for name, value in _cookie_pairs(raw_header)
    )


def _without_simulator_cookie(raw_header: str) -> str:
    return "; ".join(
        f"{name}={value}"
        for name, value in _cookie_pairs(raw_header)
        if not (name == SIMULATOR_COOKIE_NAME and value == SIMULATOR_COOKIE_VALUE)
    )


def _is_app_webview(user_agent: str) -> bool:
    return user_agent.strip() == APP_WEBVIEW_USER_AGENT


def request(flow: http.HTTPFlow) -> None:
    if flow.request.pretty_host.lower() != YAMIBO_HOST:
        return

    cookie_header = flow.request.headers.get("cookie", "")
    if _has_simulator_cookie(cookie_header):
        forwarded_cookie = _without_simulator_cookie(cookie_header)
        if forwarded_cookie:
            flow.request.headers["cookie"] = forwarded_cookie
        else:
            flow.request.headers.pop("cookie", None)
        ctx.log.info(f"WAF-SIM pass {flow.request.method} {flow.request.path}")
        return

    if _is_app_webview(flow.request.headers.get("user-agent", "")):
        ctx.log.info(
            f"WAF-SIM app-webview bypass {flow.request.method} {flow.request.path}"
        )
        return

    ctx.log.info(f"WAF-SIM 405 {flow.request.method} {flow.request.path}")
    flow.response = http.Response.make(
        405,
        CHALLENGE_HTML,
        {
            "Content-Type": "text/html; charset=utf-8",
            "Cache-Control": "no-store",
            "Server": "BAIDU_WAF_SIMULATOR",
            "BDWAF-Request-ID": "yamibo-http405-simulator",
        },
    )
